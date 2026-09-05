-- =====================================================================
-- V15 - Row-Level Security (#40)
-- =====================================================================

-- app.current_org_id est positionne par le pool applicatif (voir
-- TenantConnectionConfig) via set_config(..., false) -- jamais par le
-- client SQL lui-meme. STABLE : la valeur ne change pas au sein d'une
-- meme requete, autorise Postgres a optimiser les policies.
CREATE FUNCTION app_current_org_id() RETURNS BIGINT AS $$
    SELECT NULLIF(current_setting('app.current_org_id', true), '')::BIGINT;
$$ LANGUAGE sql STABLE;

-- shops : jointure directe sur organization_id (V13).
ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE shops FORCE ROW LEVEL SECURITY;
CREATE POLICY shops_tenant_isolation ON shops
    USING (organization_id = app_current_org_id());

-- customers, products, credit_sales, stock_receptions : shop_id direct (V10, V14).
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
CREATE POLICY customers_tenant_isolation ON customers
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
CREATE POLICY products_tenant_isolation ON products
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

ALTER TABLE credit_sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_sales FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_sales_tenant_isolation ON credit_sales
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

ALTER TABLE stock_receptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_receptions FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_receptions_tenant_isolation ON stock_receptions
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

-- installments, payments, sale_attachments : deux sauts via credit_sales.shop_id.
ALTER TABLE installments ENABLE ROW LEVEL SECURITY;
ALTER TABLE installments FORCE ROW LEVEL SECURITY;
CREATE POLICY installments_tenant_isolation ON installments
    USING (sale_id IN (
        SELECT cs.id FROM credit_sales cs
        JOIN shops s ON s.id = cs.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;
CREATE POLICY payments_tenant_isolation ON payments
    USING (sale_id IN (
        SELECT cs.id FROM credit_sales cs
        JOIN shops s ON s.id = cs.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

ALTER TABLE sale_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY sale_attachments_tenant_isolation ON sale_attachments
    USING (sale_id IN (
        SELECT cs.id FROM credit_sales cs
        JOIN shops s ON s.id = cs.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

-- stock_reception_lines : via reception_id -> stock_receptions.shop_id (V14),
-- plus direct que via product_id maintenant que l'en-tete porte shop_id.
-- (Precision par rapport a design.md, qui groupait ce cas avec stock_movements
-- sans trancher explicitement le chemin de jointure -- voir Ecarts identifies.)
ALTER TABLE stock_reception_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_reception_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_reception_lines_tenant_isolation ON stock_reception_lines
    USING (reception_id IN (
        SELECT sr.id FROM stock_receptions sr
        JOIN shops s ON s.id = sr.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

-- stock_movements : pas de lien direct vers stock_receptions (source
-- polymorphe source_type/source_id) -- seul chemin fiable : product_id.
ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_movements FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_movements_tenant_isolation ON stock_movements
    USING (product_id IN (
        SELECT p.id FROM products p
        JOIN shops s ON s.id = p.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

-- user_shops : meme forme que customers (shop_id direct).
ALTER TABLE user_shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_shops FORCE ROW LEVEL SECURITY;
CREATE POLICY user_shops_tenant_isolation ON user_shops
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));
