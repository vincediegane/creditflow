-- =====================================================================
-- V17 - Plan par organisation (#42)
-- Absence de ligne = fallback integral sur AppProperties.Plan (mono-tenant
-- inchange). Un seul flag ici : multi_shop. whatsappAuto reste un attribut
-- d'instance, assume, non couvert par cette table (voir design.md #42,
-- section Decisions cles).
-- =====================================================================

CREATE TABLE organization_plan (
    organization_id BIGINT PRIMARY KEY REFERENCES organizations (id),
    multi_shop      BOOLEAN   NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80)
);

ALTER TABLE organization_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_plan_tenant_isolation ON organization_plan
    USING (organization_id = app_current_org_id());
