-- =====================================================================
-- V14 - stock_receptions.shop_id (#40)
-- Prerequis a la policy RLS de V15 : la colonne doit exister et etre
-- fiable avant que la policy WITH CHECK ne s'applique a l'INSERT de
-- l'en-tete de reception (voir design.md, decision "stock_receptions
-- recoit un shop_id propre").
-- =====================================================================

ALTER TABLE stock_receptions ADD COLUMN shop_id BIGINT;

UPDATE stock_receptions sr
SET shop_id = (
    SELECT p.shop_id
    FROM stock_reception_lines srl
    JOIN products p ON p.id = srl.product_id
    WHERE srl.reception_id = sr.id
    ORDER BY srl.id
    LIMIT 1
);

-- Garde-fou explicite (risque signale par design.md) : une reception sans
-- ligne exploitable ne peut pas etre rattachee automatiquement a une
-- boutique. On echoue bruyamment plutot que de laisser passer un NULL
-- silencieusement jusqu'a la contrainte NOT NULL ci-dessous.
DO $$
DECLARE
    orphan_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO orphan_count FROM stock_receptions WHERE shop_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V14: % reception(s) sans ligne exploitable pour deduire shop_id -- '
            'resoudre manuellement (rattacher une ligne ou supprimer la reception) '
            'avant de rejouer cette migration.', orphan_count;
    END IF;
END $$;

ALTER TABLE stock_receptions ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE stock_receptions ADD CONSTRAINT fk_stock_receptions_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_stock_receptions_shop ON stock_receptions (shop_id);
