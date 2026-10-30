-- TG7 (FR §12 / arch §9.1): the Phase 2 accounting module schema.
-- Multi-book subledgers over the same commission ledger, DMN-driven
-- classification, capitalized assets with historical FX lock, amortization
-- schedules with posted-line immutability, and balanced GL journal batches
-- with ERP sync state.

-- ---------------------------------------------------------------- books (FR-904)
CREATE TABLE IF NOT EXISTS accounting_book_policy (
    book_id                       UUID PRIMARY KEY,
    tenant_id                     UUID NOT NULL,
    book_code                     VARCHAR(64) NOT NULL,
    description                   VARCHAR(256),
    functional_currency           CHAR(3) NOT NULL,
    amortization_convention       VARCHAR(32) NOT NULL DEFAULT 'EXACT_DAILY',
        -- EXACT_DAILY | FULL_MONTH | MID_MONTH | PO_WEIGHTED (FR-902)
    expedient_threshold_months    INT NOT NULL DEFAULT 12,
    fringe_rate                   NUMERIC(7,5) NOT NULL DEFAULT 0.08500,
    capitalize_manager_overrides  BOOLEAN NOT NULL DEFAULT TRUE,
    se_spiff_policy               VARCHAR(16) NOT NULL DEFAULT 'FORMULAIC_ONLY',
        -- FORMULAIC_ONLY | ALWAYS | NEVER (FR-901.1)
    renewal_policy                VARCHAR(24) NOT NULL DEFAULT 'CAP_NON_COMMENSURATE',
        -- CAP_NON_COMMENSURATE | CAP_ALWAYS | EXPENSE
    downgrade_policy              VARCHAR(24) NOT NULL DEFAULT 'PRO_RATE_IMPAIR',
        -- CONTINUE | PRO_RATE_IMPAIR (FR-903.2)
    classification_dmn            TEXT,
    classification_dmn_version    INT NOT NULL DEFAULT 0,
    status                        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, book_code)
);

-- ------------------------------------------------------- classification (FR-901)
CREATE TABLE IF NOT EXISTS ledger_classification (
    classification_id  UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    book_id            UUID NOT NULL REFERENCES accounting_book_policy (book_id),
    ledger_id          UUID NOT NULL,
    classification     VARCHAR(16) NOT NULL,        -- CAPITALIZE | EXPENSE
    reason_code        VARCHAR(64) NOT NULL,        -- e.g. NEW_LOGO_COMMISSION, PRACTICAL_EXPEDIENT, FRINGE_RATE, DMN_RULE
    fringe_amount      NUMERIC(18,4) NOT NULL DEFAULT 0,
    amortization_months INT,
    contract_start     DATE,
    contract_end       DATE,
    dmn_rule_id        VARCHAR(128),
    dmn_version        INT NOT NULL DEFAULT 0,
    classified_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    classified_by      VARCHAR(128) NOT NULL,
    UNIQUE (book_id, ledger_id)
);
CREATE INDEX IF NOT EXISTS ix_ledger_class_period ON ledger_classification (book_id, classified_at);

-- ------------------------------------------------------------------ assets (FR-905/909)
CREATE TABLE IF NOT EXISTS capitalized_cost_asset (
    asset_id             UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    book_id              UUID NOT NULL REFERENCES accounting_book_policy (book_id),
    classification_id    UUID NOT NULL REFERENCES ledger_classification (classification_id),
    ledger_id            UUID NOT NULL,
    payee_id             UUID,
    transaction_id       UUID,
    contract_ref         VARCHAR(128),
    contract_start       DATE NOT NULL,
    contract_end         DATE NOT NULL,
    amortization_months  INT NOT NULL,
    source_currency      CHAR(3),
    source_amount        NUMERIC(18,4),
    fx_rate              NUMERIC(18,6),            -- historical spot lock (ASC 830): never revalued
    capitalized_amount   NUMERIC(18,4) NOT NULL,   -- functional currency, fringe included
    status               VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | FULLY_AMORTIZED | IMPAIRED | CHURNED | DOWNGRADED
    parent_asset_id      UUID REFERENCES capitalized_cost_asset (asset_id),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_asset_book_status ON capitalized_cost_asset (book_id, status);
CREATE INDEX IF NOT EXISTS ix_asset_ledger ON capitalized_cost_asset (ledger_id);

-- --------------------------------------------------------- schedule lines (FR-902/906)
CREATE TABLE IF NOT EXISTS amortization_schedule_line (
    line_id          UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    asset_id         UUID NOT NULL REFERENCES capitalized_cost_asset (asset_id),
    book_id          UUID NOT NULL,
    period_key       VARCHAR(16) NOT NULL,
    period_start     DATE NOT NULL,
    period_end       DATE NOT NULL,
    line_type        VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
        -- SCHEDULED | CATCH_UP | IMPAIRMENT
    amount           NUMERIC(18,4) NOT NULL,
    st_balance       NUMERIC(18,4) NOT NULL DEFAULT 0,
    lt_balance       NUMERIC(18,4) NOT NULL DEFAULT 0,
    posted           BOOLEAN NOT NULL DEFAULT FALSE,
    posted_period_key VARCHAR(16),
    source_ledger_id UUID,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (asset_id, period_key, line_type)
);
CREATE INDEX IF NOT EXISTS ix_sched_book_period ON amortization_schedule_line (book_id, period_key);

-- Posted schedule lines are immutable (FR-907.3): once a line's amounts hit
-- the GL, no UPDATE may touch it.
CREATE OR REPLACE FUNCTION eim_audit.reject_posted_schedule_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.posted AND (NEW.amount <> OLD.amount OR NEW.posted <> OLD.posted
                       OR NEW.line_type <> OLD.line_type) THEN
        RAISE EXCEPTION 'posted amortization schedule lines are immutable (FR-907.3): %', OLD.line_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sched_posted_immutable ON amortization_schedule_line;
CREATE TRIGGER trg_sched_posted_immutable
    BEFORE UPDATE ON amortization_schedule_line
    FOR EACH ROW EXECUTE FUNCTION eim_audit.reject_posted_schedule_mutation();

-- ------------------------------------------------------------ GL journals (FR-907)
CREATE TABLE IF NOT EXISTS gl_journal_batch (
    batch_id      UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    book_id       UUID NOT NULL REFERENCES accounting_book_policy (book_id),
    period_key    VARCHAR(16) NOT NULL,
    je_type       VARCHAR(32) NOT NULL,
        -- PAYROLL_ACCRUAL | CAPITALIZATION | AMORTIZATION | IMPAIRMENT | ST_LT_RECLASS | CATCH_UP
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
        -- PENDING -> EXPORTED -> SYNCED | FAILED (FR-907.4)
    erp_target    VARCHAR(24) NOT NULL DEFAULT 'NETSUITE',   -- NETSUITE | SAP | WORKDAY
    erp_reference VARCHAR(128),
    retry_count   INT NOT NULL DEFAULT 0,
    total_dr      NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_cr      NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_by    VARCHAR(128),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    pushed_at     TIMESTAMP WITH TIME ZONE,
    synced_at     TIMESTAMP WITH TIME ZONE,
    last_error    TEXT,
    UNIQUE (book_id, period_key, je_type)
);

CREATE TABLE IF NOT EXISTS gl_journal_entry_line (
    gl_line_id   UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    batch_id     UUID NOT NULL REFERENCES gl_journal_batch (batch_id),
    -- zero-orphan lineage chain (FR-909): gl -> schedule -> asset -> ledger -> transaction
    schedule_line_id UUID,
    asset_id     UUID,
    ledger_id    UUID,
    transaction_id UUID,
    gl_account   VARCHAR(32) NOT NULL,
    cost_center  VARCHAR(32),
    subsidiary   VARCHAR(32),
    currency     CHAR(3) NOT NULL,
    dr_amount    NUMERIC(18,4) NOT NULL DEFAULT 0,
    cr_amount    NUMERIC(18,4) NOT NULL DEFAULT 0,
    memo         VARCHAR(256)
);
CREATE INDEX IF NOT EXISTS ix_gl_line_batch ON gl_journal_entry_line (batch_id);
CREATE INDEX IF NOT EXISTS ix_gl_line_ledger ON gl_journal_entry_line (ledger_id);

-- ERP revenue events (FR-902.3 PO-weighted amortization input; landed by the
-- NetSuite/SAP reads from TG7-ING-1): recognized revenue per contract per
-- accounting period.
CREATE TABLE IF NOT EXISTS erp_revenue_event (
    event_id      UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    contract_ref  VARCHAR(128) NOT NULL,
    period_key    VARCHAR(16) NOT NULL,
    revenue_amount NUMERIC(18,4) NOT NULL,
    source_system VARCHAR(24) NOT NULL DEFAULT 'NETSUITE',
    ingested_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, contract_ref, period_key, source_system)
);
CREATE INDEX IF NOT EXISTS ix_erp_rev_contract ON erp_revenue_event (tenant_id, contract_ref);
