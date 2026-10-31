-- TG8: Phase 3 — full SPM & channel expansion. Territories & quotas over
-- AGE, account-routing rules feeding credit assignment, Monte Carlo
-- simulation runs, channel claims, partner statements/disputes reuse,
-- AI plan diagnostics, and the NL-modeling compile queue.

-- ---------------------------------------------------------------- territories & quotas (FR-1002)
CREATE TABLE IF NOT EXISTS territory (
    territory_id  UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    name          VARCHAR(128) NOT NULL,
    parent_territory_id UUID REFERENCES territory (territory_id),
    effective_start DATE NOT NULL DEFAULT DATE '2026-01-01',
    effective_end  DATE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name, effective_start)
);

CREATE TABLE IF NOT EXISTS territory_assignment (
    assignment_id UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    territory_id  UUID NOT NULL REFERENCES territory (territory_id),
    payee_id      UUID,
    account_key   VARCHAR(128),          -- account-based routing (FR-1003): external CRM account id
    split_pct     NUMERIC(7,6) NOT NULL DEFAULT 1.000000,
    effective_start DATE NOT NULL,
    effective_end  DATE
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_terr_assign_target
    ON territory_assignment (territory_id,
       COALESCE(payee_id::text, account_key), effective_start);
CREATE INDEX IF NOT EXISTS ix_terr_assign_acct ON territory_assignment (tenant_id, account_key) WHERE account_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_terr_assign_payee ON territory_assignment (tenant_id, payee_id) WHERE payee_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS quota_allocation (
    quota_id      UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    territory_id  UUID NOT NULL REFERENCES territory (territory_id),
    period_key    VARCHAR(16) NOT NULL,
    quota_amount  NUMERIC(18,4) NOT NULL,
    UNIQUE (territory_id, period_key)
);

-- ---------------------------------------------------------------- routing rules (FR-1003)
-- Order of evaluation: account match → territory payee match → default.
CREATE TABLE IF NOT EXISTS routing_rule (
    rule_id       UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    priority      INT NOT NULL DEFAULT 100,
    match_account_key VARCHAR(128),      -- NULL = catch-all
    match_component VARCHAR(128),        -- optional component filter
    route_to_territory_id UUID REFERENCES territory (territory_id),
    route_to_payee_id UUID,
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_routing_active ON routing_rule (tenant_id, priority) WHERE active;

-- ---------------------------------------------------------------- simulation (FR-1001)
CREATE TABLE IF NOT EXISTS simulation_run (
    simulation_id UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    plan_version_hash VARCHAR(64) NOT NULL,
    period_key    VARCHAR(16) NOT NULL,
    iterations    INT NOT NULL,
    seed          BIGINT NOT NULL,
    status        VARCHAR(24) NOT NULL DEFAULT 'RUNNING', -- RUNNING|COMPLETED|FAILED
    p50_liability NUMERIC(18,4),
    p95_liability NUMERIC(18,4),
    p99_liability NUMERIC(18,4),
    mean_liability NUMERIC(18,4),
    windfall_payees INT,
    started_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at  TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS simulation_iteration (
    iteration_id  UUID PRIMARY KEY,
    simulation_id UUID NOT NULL REFERENCES simulation_run (simulation_id),
    iteration     INT NOT NULL,
    total_payout  NUMERIC(18,4) NOT NULL,
    max_payee_payout NUMERIC(18,4),
    UNIQUE (simulation_id, iteration)
);

CREATE TABLE IF NOT EXISTS simulation_payee_outcome (
    simulation_id UUID NOT NULL REFERENCES simulation_run (simulation_id),
    payee_id      UUID NOT NULL,
    p50_payout    NUMERIC(18,4) NOT NULL,
    p95_payout    NUMERIC(18,4) NOT NULL,
    windfall_ratio NUMERIC(7,3),         -- p95 / (mean historical payout) — FR-1001 windfall detection
    PRIMARY KEY (simulation_id, payee_id)
);

-- ---------------------------------------------------------------- channel claims (FR-1005)
CREATE TABLE IF NOT EXISTS channel_claim (
    claim_id      UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    partner_payee_id UUID NOT NULL,      -- the channel partner entity
    program_code  VARCHAR(64) NOT NULL,  -- rebate/spiff program
    claim_period_key VARCHAR(16) NOT NULL,
    claimed_amount NUMERIC(18,4) NOT NULL,
    status        VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
        -- SUBMITTED → ELIGIBILITY_CHECKED → APPROVED → ACCRUED | REJECTED
    eligibility_detail JSONB,
    accrual_ledger_id UUID,              -- the CALCULATED entry the accrual produced
    submitted_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decided_by    VARCHAR(128),
    decided_at    TIMESTAMP WITH TIME ZONE,
    UNIQUE (tenant_id, partner_payee_id, program_code, claim_period_key)
);

CREATE TABLE IF NOT EXISTS channel_program (
    program_code  VARCHAR(64) PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    name          VARCHAR(128) NOT NULL,
    kind          VARCHAR(24) NOT NULL DEFAULT 'REBATE',   -- REBATE | SPIFF
    rate          NUMERIC(7,6) NOT NULL DEFAULT 0.050000,  -- accrual rate on claimed amount
    min_claim     NUMERIC(18,4) NOT NULL DEFAULT 0,
    requires_proof BOOLEAN NOT NULL DEFAULT true,
    active        BOOLEAN NOT NULL DEFAULT true
);

-- ---------------------------------------------------------------- partner identities (FR-1004)
-- Partner accounts are separate from internal payees: statements/disputes
-- reuse the machinery but the identity plane is distinct (mTLS + partner
-- role), so a partner can never hold internal roles.
CREATE TABLE IF NOT EXISTS partner_account (
    partner_payee_id UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    org_name      VARCHAR(200) NOT NULL,
    cert_cn       VARCHAR(256),          -- mTLS client-cert CN mapped at the gateway
    contact_email VARCHAR(256),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- NL modeling + diagnostics (FR-408/1006)
CREATE TABLE IF NOT EXISTS nl_model_request (
    request_id    UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    prompt        TEXT NOT NULL,
    generated_plan_json JSONB NOT NULL,   -- the draft PlanModel the NL compiler produced
    compile_status VARCHAR(24) NOT NULL DEFAULT 'PENDING', -- PENDING|COMPILED|FAILED
    compile_error TEXT,
    approval_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_HUMAN',
        -- PENDING_HUMAN → APPROVED → published through FR-406 | REJECTED
    approved_by   VARCHAR(128),
    approved_at   TIMESTAMP WITH TIME ZONE,
    created_by    VARCHAR(128) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS plan_diagnostic (
    diagnostic_id UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    plan_version_hash VARCHAR(64) NOT NULL,
    kind          VARCHAR(32) NOT NULL,  -- WINDFALL | CLIFF | GAMING
    component     VARCHAR(128),
    detail        JSONB NOT NULL,
    severity      VARCHAR(16) NOT NULL DEFAULT 'WARN',   -- INFO | WARN | CRITICAL
    detected_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
