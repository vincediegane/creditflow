ALTER TABLE credit_sales
    ADD COLUMN guarantor_full_name  VARCHAR(160),
    ADD COLUMN guarantor_phone      VARCHAR(30),
    ADD COLUMN guarantor_address    VARCHAR(255),
    ADD COLUMN guarantor_cni_number VARCHAR(50);

CREATE INDEX idx_credit_sales_guarantor_phone ON credit_sales (guarantor_phone);
CREATE INDEX idx_credit_sales_guarantor_name ON credit_sales (LOWER(guarantor_full_name));
