-- TG1-DAT-1: EIM core schema per FR doc section 12.
-- Owned by eim-core-api (per-service Flyway history tables — see ADR-010).

-- ---------------------------------------------------------------- payee
CREATE TABLE payee (
    payee_id           UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    external_crm_id    VARCHAR(128),
    employee_number    VARCHAR(64),
    full_name          VARCHAR(256) NOT NULL,
    email              VARCHAR(320) NOT NULL,
    role_title         VARCHAR(128),
    base_currency      CHAR(3) NOT NULL DEFAULT 'USD',
    manager_payee_id   UUID REFERENCES payee (payee_id),
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_payee_employee_number ON payee (tenant_id, employee_number) WHERE employee_number IS NOT NULL;
CREATE INDEX ix_payee_crm ON payee (tenant_id, external_crm_id);
CREATE INDEX ix_payee_manager ON payee (manager_payee_id);

-- ---------------------------------------------------------------- comp_plan / plan_version
CREATE TABLE comp_plan (
    plan_id     UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    name        VARCHAR(256) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE plan_version (
    plan_version_id    UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    plan_id            UUID NOT NULL REFERENCES comp_plan (plan_id),
    version            INTEGER NOT NULL,
    plan_version_hash  VARCHAR(128) NOT NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT|PUBLISHED|RETIRED
    effective_start    DATE NOT NULL,
    effective_end      DATE,
    published_by       VARCHAR(128),
    published_at       TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (plan_id, version)
);
CREATE UNIQUE INDEX uq_plan_version_hash ON plan_version (plan_version_hash);

-- ---------------------------------------------------------------- plan_assignment
CREATE TABLE plan_assignment (
    assignment_id           UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    payee_id                UUID NOT NULL REFERENCES payee (payee_id),
    plan_version_id         UUID NOT NULL REFERENCES plan_version (plan_version_id),
    quota_amount            NUMERIC(18,4),
    target_incentive_amount NUMERIC(18,4),
    proration_factor        NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
    effective_start_date    DATE NOT NULL,
    effective_end_date      DATE
);
CREATE INDEX ix_plan_assignment_payee ON plan_assignment (payee_id, effective_start_date, effective_end_date);

-- ---------------------------------------------------------------- payout_period
CREATE TABLE payout_period (
    payout_period_id UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    period_key       VARCHAR(16) NOT NULL, -- e.g. 2026-10
    starts_on        DATE NOT NULL,
    ends_on          DATE NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'OPEN', -- OPEN|IN_REVIEW|APPROVED|LOCKED|REOPENED
    locked_at        TIMESTAMP WITH TIME ZONE,
    UNIQUE (tenant_id, period_key)
);

-- ---------------------------------------------------------------- raw_transaction
CREATE TABLE raw_transaction (
    transaction_id    UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    source_system     VARCHAR(32) NOT NULL, -- SALESFORCE|HUBSPOT|SNOWFLAKE|CSV
    external_deal_id  VARCHAR(128),
    transaction_date  DATE NOT NULL,
    booking_amount    NUMERIC(18,4) NOT NULL,
    currency_code     CHAR(3) NOT NULL,
    fx_rate_applied   NUMERIC(12,6),
    custom_attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    ingestion_batch_id UUID NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_raw_txn_deal ON raw_transaction (tenant_id, external_deal_id);
CREATE INDEX ix_raw_txn_date ON raw_transaction (transaction_date);

-- ---------------------------------------------------------------- credit_allocation
CREATE TABLE credit_allocation (
    credit_allocation_id UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    transaction_id       UUID NOT NULL REFERENCES raw_transaction (transaction_id),
    payee_id             UUID NOT NULL REFERENCES payee (payee_id),
    split_pct            NUMERIC(9,6) NOT NULL,
    effective_date       DATE NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_txn ON credit_allocation (transaction_id);
CREATE INDEX ix_credit_payee ON credit_allocation (payee_id);

-- ---------------------------------------------------------------- commission_ledger_entry (partitioned)
-- Partitioning by payout period (FR-507 / NFR-31): partitions are created and
-- locked by eim_ops.ensure_period_partition() / lock_period_partition().
CREATE TABLE commission_ledger_entry (
    ledger_id        UUID NOT NULL,
    tenant_id        UUID NOT NULL,
    payout_period_id UUID NOT NULL REFERENCES payout_period (payout_period_id),
    payee_id         UUID NOT NULL REFERENCES payee (payee_id),
    transaction_id   UUID REFERENCES raw_transaction (transaction_id),
    plan_version_id  UUID REFERENCES plan_version (plan_version_id),
    component_name   VARCHAR(128),
    credited_amount  NUMERIC(18,4),
    rate_applied     NUMERIC(8,6),
    payout_amount    NUMERIC(18,4) NOT NULL,
    entry_type       VARCHAR(32) NOT NULL, -- CALCULATED|MANUAL_OVERRIDE|DRAW_ADVANCE|DRAW_RECOVERY|CLAWBACK|PRIOR_PERIOD_TRUEUP
    calculation_trace JSONB,
    is_locked        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (ledger_id, payout_period_id)
) PARTITION BY LIST (payout_period_id);
CREATE TABLE commission_ledger_entry_default PARTITION OF commission_ledger_entry DEFAULT;
CREATE INDEX ix_ledger_payee ON commission_ledger_entry (payee_id);
CREATE INDEX ix_ledger_period ON commission_ledger_entry (payout_period_id);

-- ---------------------------------------------------------------- dispute_ticket
CREATE TABLE dispute_ticket (
    dispute_id     UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    payee_id       UUID NOT NULL REFERENCES payee (payee_id),
    transaction_id UUID REFERENCES raw_transaction (transaction_id),
    period_key     VARCHAR(16),
    category       VARCHAR(32) NOT NULL, -- SOURCE_DATA|CREDIT_SPLIT|WINDOW_STATE|PLAN_LOGIC|OTHER
    notes          TEXT,
    status         VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED|UNDER_REVIEW|APPROVED|REJECTED|ADJUSTED
    resolution_note TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_dispute_payee ON dispute_ticket (payee_id);
