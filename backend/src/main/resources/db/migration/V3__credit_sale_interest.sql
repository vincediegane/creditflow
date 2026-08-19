ALTER TABLE credit_sales
    ADD COLUMN interest_rate   NUMERIC(5, 2),
    ADD COLUMN interest_amount NUMERIC(15, 2) NOT NULL DEFAULT 0;

ALTER TABLE credit_sales
    ADD CONSTRAINT chk_credit_sales_interest_rate
        CHECK (interest_rate IS NULL OR (interest_rate >= 0 AND interest_rate <= 100)),
    ADD CONSTRAINT chk_credit_sales_interest_amount
        CHECK (interest_amount >= 0);
