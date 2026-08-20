CREATE TABLE suppliers (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    contact_name VARCHAR(120),
    phone        VARCHAR(30),
    email        VARCHAR(120),
    address      VARCHAR(255),
    notes        TEXT,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP,
    created_by   VARCHAR(80),
    updated_by   VARCHAR(80)
);

CREATE INDEX idx_suppliers_name ON suppliers (LOWER(name));

CREATE TABLE stock_receptions (
    id          BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT    NOT NULL,
    received_at DATE      NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    CONSTRAINT fk_stock_receptions_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE INDEX idx_stock_receptions_supplier ON stock_receptions (supplier_id);
CREATE INDEX idx_stock_receptions_received_at ON stock_receptions (received_at);

CREATE TABLE stock_reception_lines (
    id           BIGSERIAL PRIMARY KEY,
    reception_id BIGINT  NOT NULL,
    product_id   BIGINT  NOT NULL,
    quantity     INTEGER NOT NULL,
    CONSTRAINT fk_stock_reception_lines_reception FOREIGN KEY (reception_id) REFERENCES stock_receptions (id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_reception_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_stock_reception_lines_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_reception_lines_reception ON stock_reception_lines (reception_id);
CREATE INDEX idx_stock_reception_lines_product ON stock_reception_lines (product_id);

CREATE TABLE stock_movements (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT      NOT NULL,
    type        VARCHAR(10) NOT NULL,
    quantity    INTEGER     NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id   BIGINT,
    occurred_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(80),
    CONSTRAINT fk_stock_movements_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_stock_movements_type CHECK (type IN ('IN', 'OUT')),
    CONSTRAINT chk_stock_movements_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_movements_product ON stock_movements (product_id, occurred_at DESC);
