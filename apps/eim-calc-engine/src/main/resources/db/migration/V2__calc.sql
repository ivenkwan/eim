-- TG3-CAL: calculation run history (the ledger itself lives in the core
-- schema's partitioned commission_ledger_entry; every run commits all-or-
-- nothing, so a FAILED run leaves zero rows there).
CREATE TABLE IF NOT EXISTS calc_run (
    calc_run_id      UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    payout_period_id UUID NOT NULL,
    plan_version_hash VARCHAR(64) NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'RUNNING', -- RUNNING|COMPLETED|FAILED
    waves            INTEGER,
    payees           INTEGER,
    transactions     INTEGER,
    ledger_entries   INTEGER,
    duration_ms      BIGINT,
    error            TEXT,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS ix_calc_run_period ON calc_run (payout_period_id);
