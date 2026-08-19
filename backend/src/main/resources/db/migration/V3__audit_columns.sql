-- =====================================================================
-- Journal d'audit
-- Trace l'auteur des creations/modifications et historise les actions
-- sensibles (suppression, annulation, changement de prix) de facon
-- append-only, independamment du cycle de vie des comptes utilisateurs.
-- =====================================================================

ALTER TABLE customers ADD COLUMN created_by VARCHAR(80);
ALTER TABLE customers ADD COLUMN updated_by VARCHAR(80);
ALTER TABLE products ADD COLUMN created_by VARCHAR(80);
ALTER TABLE products ADD COLUMN updated_by VARCHAR(80);
ALTER TABLE credit_sales ADD COLUMN created_by VARCHAR(80);
ALTER TABLE credit_sales ADD COLUMN updated_by VARCHAR(80);
ALTER TABLE payments ADD COLUMN created_by VARCHAR(80);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(30)  NOT NULL,
    entity_id   BIGINT       NOT NULL,
    entity_label VARCHAR(255) NOT NULL,
    action      VARCHAR(30)  NOT NULL,
    details     TEXT,
    actor       VARCHAR(80),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
