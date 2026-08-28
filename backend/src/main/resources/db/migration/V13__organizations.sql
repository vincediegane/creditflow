-- =====================================================================
-- V13 - Fondation multi-tenant : entite Organization (#34)
-- =====================================================================

CREATE TABLE organizations (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80)
);

-- Organisation par defaut : recoit toutes les boutiques et tous les utilisateurs
-- existants lors du retro-remplissage (instance mono-tenant).
INSERT INTO organizations (name, created_at) VALUES ('Organisation par defaut', NOW());

-- shops.organization_id
ALTER TABLE shops ADD COLUMN organization_id BIGINT;
UPDATE shops SET organization_id = (SELECT id FROM organizations ORDER BY id LIMIT 1);
ALTER TABLE shops ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE shops ADD CONSTRAINT fk_shops_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
CREATE INDEX idx_shops_organization ON shops (organization_id);

-- users.organization_id
ALTER TABLE users ADD COLUMN organization_id BIGINT;
UPDATE users SET organization_id = (SELECT id FROM organizations ORDER BY id LIMIT 1);
ALTER TABLE users ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
CREATE INDEX idx_users_organization ON users (organization_id);
