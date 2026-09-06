-- =====================================================================
-- Suppression complete des donnees d'une organisation. IRREVERSIBLE.
-- Invoque par scripts/tenant-delete.sh, apres export obligatoire et
-- confirmation interactive, via :
--   docker compose exec -T db psql -v ON_ERROR_STOP=1 -v org_id=<id> \
--       -U "$DB_APP_USERNAME" -d "$DB_NAME" < scripts/sql/tenant-delete.sql
--
-- Transaction unique (BEGIN/COMMIT) : en cas d'erreur (ON_ERROR_STOP=1),
-- psql s'arrete et la session se ferme sans avoir commite -- Postgres
-- annule alors automatiquement la transaction ouverte.
--
-- Ordre de suppression verifie contrainte par contrainte sur les
-- migrations reelles (V1, V6, V9, V10, V13, V14) -- voir design.md,
-- Decisions cles, pour la justification complete de chaque etape.
-- suppliers et penalty_settings ne sont jamais touches (tables
-- globales).
-- =====================================================================

BEGIN;

SELECT set_config('app.current_org_id', :'org_id', false)
\g /dev/null

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = :org_id) THEN
        RAISE EXCEPTION 'Organisation % introuvable', :org_id;
    END IF;
END $$;

-- 1. audit_log : hors RLS, filtre derive vers customers/products/
--    credit_sales (eux-memes filtres par RLS dans cette session) --
--    doit preceder leur suppression (etapes 4-6), sinon les
--    sous-requetes n'ont plus de lignes a joindre. Les entrees
--    PENALTY_SETTINGS (parametre global) ne sont jamais visees.
DELETE FROM audit_log
WHERE (entity_type = 'CUSTOMER' AND entity_id IN (SELECT id FROM customers))
   OR (entity_type = 'PRODUCT' AND entity_id IN (SELECT id FROM products))
   OR (entity_type = 'CREDIT_SALE' AND entity_id IN (SELECT id FROM credit_sales));

-- 2. stock_movements : couverte par RLS (policy via products/shops).
--    fk_stock_movements_product n'a pas de ON DELETE CASCADE -- doit
--    preceder products (etape 6).
DELETE FROM stock_movements;

-- 3. stock_receptions : couverte par RLS. Cascade automatiquement
--    stock_reception_lines (fk_stock_reception_lines_reception
--    ON DELETE CASCADE, V9).
DELETE FROM stock_receptions;

-- 4. credit_sales : couverte par RLS. Cascade automatiquement
--    installments, payments, sale_attachments (ON DELETE CASCADE vers
--    credit_sales, V1/V6). Doit preceder customers/products (etapes
--    5-6) : fk_credit_sales_customer/fk_credit_sales_product sans
--    CASCADE.
DELETE FROM credit_sales;

-- 5. customers : couverte par RLS.
DELETE FROM customers;

-- 6. products : couverte par RLS.
DELETE FROM products;

-- 7. shops : couverte par RLS. Cascade automatiquement user_shops
--    (ON DELETE CASCADE, V10). Doit preceder organizations (etape 9) :
--    fk_shops_organization sans CASCADE.
DELETE FROM shops;

-- 8. users : hors RLS, filtre explicite. Doit preceder organizations :
--    fk_users_organization sans CASCADE.
DELETE FROM users WHERE organization_id = :org_id;

-- 9. organizations : hors RLS, filtre explicite. Derniere etape --
--    echoue avec une erreur de contrainte FK si une etape precedente
--    n'a pas realise la suppression attendue (garde-fou involontaire
--    mais verifie : voir design.md, Risques, et note "fail-closed"
--    ci-dessus).
DELETE FROM organizations WHERE id = :org_id;

COMMIT;
