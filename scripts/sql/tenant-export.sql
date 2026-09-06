-- =====================================================================
-- Export CSV d'une organisation, table par table.
-- Invoque exclusivement par scripts/tenant-export.sh, qui fournit les
-- variables psql suivantes :
--   :org_id              entier, organisation a exporter.
--   :out_organizations, :out_users, :out_shops, :out_user_shops,
--   :out_customers, :out_products, :out_credit_sales,
--   :out_installments, :out_payments, :out_sale_attachments,
--   :out_stock_receptions, :out_stock_reception_lines,
--   :out_stock_movements, :out_audit_log
--                         chemins absolus cote conteneur "db" (pas
--                         l'hote), sous le repertoire cree au prealable
--                         par tenant-export.sh (mkdir -p).
--
-- Garde principale : set_config positionne app.current_org_id pour
-- toute la session -- les policies RLS de V15__row_level_security.sql
-- filtrent alors automatiquement les onze tables couvertes (shops,
-- customers, products, credit_sales, installments, payments,
-- sale_attachments, stock_receptions, stock_reception_lines,
-- stock_movements, user_shops) : un SELECT * suffit, la policy
-- s'applique meme sans filtre explicite dans la requete.
--
-- organizations, users, audit_log sont hors RLS : filtre explicite
-- ci-dessous. suppliers et penalty_settings (les deux autres tables
-- hors RLS) sont des tables globales, entierement exclues de cet
-- export -- voir design.md, Decisions cles.
-- =====================================================================

SELECT set_config('app.current_org_id', :'org_id', false)
\g /dev/null

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = :org_id) THEN
        RAISE EXCEPTION 'Organisation % introuvable', :org_id;
    END IF;
END $$;

\copy (SELECT * FROM organizations WHERE id = :org_id) TO :out_organizations WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM users WHERE organization_id = :org_id ORDER BY id) TO :out_users WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM shops ORDER BY id) TO :out_shops WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM user_shops ORDER BY user_id, shop_id) TO :out_user_shops WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM customers ORDER BY id) TO :out_customers WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM products ORDER BY id) TO :out_products WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM credit_sales ORDER BY id) TO :out_credit_sales WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM installments ORDER BY id) TO :out_installments WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM payments ORDER BY id) TO :out_payments WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM sale_attachments ORDER BY id) TO :out_sale_attachments WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM stock_receptions ORDER BY id) TO :out_stock_receptions WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM stock_reception_lines ORDER BY id) TO :out_stock_reception_lines WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM stock_movements ORDER BY id) TO :out_stock_movements WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM audit_log WHERE (entity_type = 'CUSTOMER' AND entity_id IN (SELECT id FROM customers)) OR (entity_type = 'PRODUCT' AND entity_id IN (SELECT id FROM products)) OR (entity_type = 'CREDIT_SALE' AND entity_id IN (SELECT id FROM credit_sales)) ORDER BY id) TO :out_audit_log WITH (FORMAT csv, HEADER)
