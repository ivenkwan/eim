-- TG4: governance objects. Manual overrides sit beside the calculated
-- entries with a preserved original; true-up queue rows route retro changes
-- to Finance review; export runs are hashed for byte-identical regeneration
-- of locked periods.

CREATE TABLE IF NOT EXISTS manual_override (
    override_id     UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    payout_period_id UUID NOT NULL REFERENCES payout_period (payout_period_id),
    payee_id        UUID NOT NULL,
    component_name  VARCHAR(128),
    original_amount NUMERIC(18,4) NOT NULL,
    adjusted_amount NUMERIC(18,4) NOT NULL,
    reason_code     VARCHAR(64) NOT NULL,
    reason_comment  TEXT,
    dispute_id      UUID REFERENCES dispute_ticket (dispute_id),
    ledger_id       UUID,
    created_by      VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_override_period ON manual_override (payout_period_id);

CREATE TABLE IF NOT EXISTS trueup_review (
    trueup_id       UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    origin_period_id UUID NOT NULL REFERENCES payout_period (payout_period_id),
    open_period_id  UUID NOT NULL REFERENCES payout_period (payout_period_id),
    payee_id        UUID NOT NULL,
    diff_amount     NUMERIC(18,4) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING|APPROVED|REJECTED|POSTED
    report          JSONB,
    decided_by      VARCHAR(128),
    decision_note   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decided_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS ix_trueup_status ON trueup_review (status);

CREATE TABLE IF NOT EXISTS export_run (
    export_id       UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    payout_period_id UUID NOT NULL REFERENCES payout_period (payout_period_id),
    template        VARCHAR(32) NOT NULL,           -- ADP|WORKDAY|NETSUITE_JOURNAL
    file_hash       VARCHAR(64) NOT NULL,           -- sha256 of the emitted bytes
    row_count       INTEGER NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    created_by      VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_template CHECK (template IN ('ADP', 'WORKDAY', 'NETSUITE_JOURNAL'))
);
CREATE INDEX IF NOT EXISTS ix_export_period ON export_run (payout_period_id);
