CREATE TABLE penalty_settings (
    id          BIGINT PRIMARY KEY,
    enabled     BOOLEAN        NOT NULL DEFAULT FALSE,
    rate_type   VARCHAR(20)    NOT NULL DEFAULT 'FIXED',
    rate        NUMERIC(15, 2) NOT NULL DEFAULT 0,
    period      VARCHAR(10)    NOT NULL DEFAULT 'DAY',
    cap_percent NUMERIC(5, 2),
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80)
);

INSERT INTO penalty_settings (id, enabled, rate_type, rate, period, cap_percent)
VALUES (1, FALSE, 'FIXED', 0, 'DAY', NULL);

ALTER TABLE installments ADD COLUMN penalty_paid NUMERIC(15, 2) NOT NULL DEFAULT 0;
