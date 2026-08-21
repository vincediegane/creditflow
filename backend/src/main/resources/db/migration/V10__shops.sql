-- =====================================================================
-- V10 - Consolidation multi-boutiques (#10)
-- =====================================================================

CREATE TABLE shops (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    address     VARCHAR(255),
    phone       VARCHAR(30),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    CONSTRAINT uk_shops_name UNIQUE (name)
);

-- Boutique par defaut : recoit toutes les donnees existantes lors du retro-remplissage.
INSERT INTO shops (name, active, created_at) VALUES ('Boutique principale', TRUE, NOW());

CREATE TABLE user_shops (
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, shop_id),
    CONSTRAINT fk_user_shops_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_shops_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE
);

-- Rattache les vendeurs existants a la boutique par defaut (regle metier : un SELLER
-- doit toujours avoir au moins une boutique). Les ADMIN restent sans assignation :
-- ils deviennent automatiquement super-admin (acces a toutes les boutiques).
INSERT INTO user_shops (user_id, shop_id)
SELECT u.id, (SELECT id FROM shops ORDER BY id LIMIT 1)
FROM users u
WHERE u.role = 'SELLER';

-- customers.shop_id
ALTER TABLE customers ADD COLUMN shop_id BIGINT;
UPDATE customers SET shop_id = (SELECT id FROM shops ORDER BY id LIMIT 1);
ALTER TABLE customers ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE customers ADD CONSTRAINT fk_customers_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_customers_shop ON customers (shop_id);

-- products.shop_id
ALTER TABLE products ADD COLUMN shop_id BIGINT;
UPDATE products SET shop_id = (SELECT id FROM shops ORDER BY id LIMIT 1);
ALTER TABLE products ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT fk_products_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_products_shop ON products (shop_id);

-- credit_sales.shop_id
ALTER TABLE credit_sales ADD COLUMN shop_id BIGINT;
UPDATE credit_sales SET shop_id = (SELECT id FROM shops ORDER BY id LIMIT 1);
ALTER TABLE credit_sales ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE credit_sales ADD CONSTRAINT fk_credit_sales_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_credit_sales_shop ON credit_sales (shop_id);
