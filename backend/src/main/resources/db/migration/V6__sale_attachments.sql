CREATE TABLE sale_attachments (
    id                BIGSERIAL PRIMARY KEY,
    sale_id           BIGINT       NOT NULL,
    type              VARCHAR(20)  NOT NULL,
    file_url          VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255),
    content_type      VARCHAR(100),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(80),
    CONSTRAINT fk_sale_attachments_sale FOREIGN KEY (sale_id) REFERENCES credit_sales (id) ON DELETE CASCADE
);

CREATE INDEX idx_sale_attachments_sale ON sale_attachments (sale_id);
