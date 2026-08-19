-- =====================================================================
-- CreditFlow - schema initial
-- Une seule boutique pour le MVP. Les tables sont concues pour accueillir
-- plus tard une colonne shop_id (SaaS multi-boutiques) sans refonte.
-- =====================================================================

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(80)  NOT NULL,
    password    VARCHAR(255) NOT NULL,
    full_name   VARCHAR(150) NOT NULL,
    role        VARCHAR(30)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    first_name  VARCHAR(80)  NOT NULL,
    last_name   VARCHAR(80)  NOT NULL,
    phone       VARCHAR(30)  NOT NULL,
    address     VARCHAR(255),
    cni_number  VARCHAR(50),
    profession  VARCHAR(120),
    photo_url   VARCHAR(255),
    notes       TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    CONSTRAINT uk_customers_phone UNIQUE (phone),
    CONSTRAINT uk_customers_cni UNIQUE (cni_number)
);

CREATE INDEX idx_customers_last_name ON customers (LOWER(last_name));
CREATE INDEX idx_customers_phone ON customers (phone);

CREATE TABLE products (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(150)   NOT NULL,
    category     VARCHAR(60)    NOT NULL,
    cash_price   NUMERIC(15, 2) NOT NULL,
    credit_price NUMERIC(15, 2) NOT NULL,
    stock        INTEGER        NOT NULL DEFAULT 0,
    description  TEXT,
    status       VARCHAR(20)    NOT NULL,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP,
    CONSTRAINT chk_products_prices CHECK (cash_price >= 0 AND credit_price >= 0),
    CONSTRAINT chk_products_stock CHECK (stock >= 0)
);

CREATE INDEX idx_products_name ON products (LOWER(name));
CREATE INDEX idx_products_category ON products (category);

CREATE TABLE credit_sales (
    id                BIGSERIAL PRIMARY KEY,
    reference         VARCHAR(30)    NOT NULL,
    customer_id       BIGINT         NOT NULL,
    product_id        BIGINT         NOT NULL,
    total_price       NUMERIC(15, 2) NOT NULL,
    down_payment      NUMERIC(15, 2) NOT NULL DEFAULT 0,
    financed_amount   NUMERIC(15, 2) NOT NULL,
    installment_count INTEGER        NOT NULL,
    monthly_amount    NUMERIC(15, 2) NOT NULL,
    amount_paid       NUMERIC(15, 2) NOT NULL DEFAULT 0,
    remaining_amount  NUMERIC(15, 2) NOT NULL,
    start_date        DATE           NOT NULL,
    end_date          DATE           NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    notes             TEXT,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP,
    CONSTRAINT uk_credit_sales_reference UNIQUE (reference),
    CONSTRAINT fk_credit_sales_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_credit_sales_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_credit_sales_count CHECK (installment_count > 0),
    CONSTRAINT chk_credit_sales_amounts CHECK (total_price > 0 AND down_payment >= 0)
);

CREATE INDEX idx_credit_sales_customer ON credit_sales (customer_id);
CREATE INDEX idx_credit_sales_product ON credit_sales (product_id);
CREATE INDEX idx_credit_sales_status ON credit_sales (status);

CREATE TABLE installments (
    id          BIGSERIAL PRIMARY KEY,
    sale_id     BIGINT         NOT NULL,
    number      INTEGER        NOT NULL,
    due_date    DATE           NOT NULL,
    amount      NUMERIC(15, 2) NOT NULL,
    amount_paid NUMERIC(15, 2) NOT NULL DEFAULT 0,
    status      VARCHAR(20)    NOT NULL,
    paid_at     DATE,
    CONSTRAINT fk_installments_sale FOREIGN KEY (sale_id) REFERENCES credit_sales (id) ON DELETE CASCADE,
    CONSTRAINT uk_installments_sale_number UNIQUE (sale_id, number)
);

CREATE INDEX idx_installments_due_date ON installments (due_date);
CREATE INDEX idx_installments_status ON installments (status);

CREATE TABLE payments (
    id           BIGSERIAL PRIMARY KEY,
    sale_id      BIGINT         NOT NULL,
    amount       NUMERIC(15, 2) NOT NULL,
    payment_date DATE           NOT NULL,
    method       VARCHAR(30)    NOT NULL,
    reference    VARCHAR(60),
    notes        TEXT,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payments_sale FOREIGN KEY (sale_id) REFERENCES credit_sales (id) ON DELETE CASCADE,
    CONSTRAINT chk_payments_amount CHECK (amount > 0)
);

CREATE INDEX idx_payments_sale ON payments (sale_id);
CREATE INDEX idx_payments_date ON payments (payment_date);
