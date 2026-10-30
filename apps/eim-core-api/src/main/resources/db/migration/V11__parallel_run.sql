-- TG6: parallel run & Phase 1 GA machinery. Partner onboarding registry,
-- legacy shadow statements, generated cent-for-cent diffs with a triage
-- workflow, recorded sign-offs, and close-cycle telemetry source columns.

CREATE TABLE IF NOT EXISTS partner_tenant (
    partner_id    UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL UNIQUE,
    name          VARCHAR(200) NOT NULL,
    idp_entity_id VARCHAR(256),          -- partner IdP federation (SAML entity / OIDC issuer)
    idp_posture  VARCHAR(32) NOT NULL DEFAULT 'GATEWAY_OIDC', -- GATEWAY_OIDC|SAML_FEDERATED
    payee_target  INTEGER NOT NULL DEFAULT 100,
    status        VARCHAR(32) NOT NULL DEFAULT 'ONBOARDING', -- ONBOARDING|BACKFILLED|PARALLEL_RUN|SIGNED_OFF|LIVE
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS legacy_statement (
    legacy_id    UUID PRIMARY KEY,
    partner_id   UUID NOT NULL REFERENCES partner_tenant (partner_id),
    period_key   VARCHAR(16) NOT NULL,
    payee_id     UUID NOT NULL,
    legacy_payout NUMERIC(18,4) NOT NULL,
    uploaded_by  VARCHAR(128) NOT NULL,
    uploaded_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (partner_id, period_key, payee_id)
);

CREATE TABLE IF NOT EXISTS parallel_diff (
    diff_id      UUID PRIMARY KEY,
    partner_id   UUID NOT NULL REFERENCES partner_tenant (partner_id),
    period_key   VARCHAR(16) NOT NULL,
    payee_id     UUID NOT NULL,
    eim_payout   NUMERIC(18,4) NOT NULL,
    legacy_payout NUMERIC(18,4) NOT NULL,
    delta_cents  NUMERIC(18,4) GENERATED ALWAYS AS
                    ((eim_payout - legacy_payout) * 100) STORED,
    status       VARCHAR(32) NOT NULL DEFAULT 'MATCH', -- MATCH|DISCREPANT|RESOLVED_ADJUSTED|RESOLVED_EXPLAINED
    resolution_note TEXT,
    resolved_by  VARCHAR(128),
    resolved_at  TIMESTAMP WITH TIME ZONE,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (partner_id, period_key, payee_id)
);
CREATE INDEX IF NOT EXISTS ix_pdiff_partner_period ON parallel_diff (partner_id, period_key, status);

CREATE TABLE IF NOT EXISTS parallel_signoff (
    signoff_id   UUID PRIMARY KEY,
    partner_id   UUID NOT NULL REFERENCES partner_tenant (partner_id),
    period_key   VARCHAR(16) NOT NULL,
    payees       INTEGER NOT NULL,
    matched      INTEGER NOT NULL,
    match_rate   NUMERIC(7,4) NOT NULL,
    signed_off_by VARCHAR(128) NOT NULL,
    signed_off_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    evidence_hash VARCHAR(64) NOT NULL,
    UNIQUE (partner_id, period_key)
);

-- MI-18 break-glass: reopening a locked period needs BOTH the
-- finance_approver and the system_admin to approve (dual control), with a
-- documented impact memo. The reopen only executes once both signatures are
-- on the same request row.
CREATE TABLE IF NOT EXISTS period_reopen_request (
    request_id     UUID PRIMARY KEY,
    payout_period_id UUID NOT NULL,
    impact_memo    TEXT NOT NULL,
    requested_by   VARCHAR(128) NOT NULL,
    finance_approved_by VARCHAR(128),
    finance_approved_at TIMESTAMP WITH TIME ZONE,
    sysadmin_approved_by VARCHAR(128),
    sysadmin_approved_at TIMESTAMP WITH TIME ZONE,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING|EXECUTED
    executed_at    TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_reopen_one_pending
    ON period_reopen_request (payout_period_id) WHERE status = 'PENDING';

-- Re-grant app writes on a locked partition and mark the period OPEN again
-- (MI-18). Only ever called after dual approval; every call is audited by the
-- caller with PERIOD_REOPEN.
CREATE OR REPLACE FUNCTION eim_ops.reopen_period_partition(p_period_id uuid)
RETURNS text AS $$
DECLARE
    target text;
BEGIN
    SELECT c.relname INTO target
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'commission_ledger_entry'::regclass
      AND pg_get_expr(c.relpartbound, c.oid)
          LIKE format('%%(%L)%%', p_period_id::text)
      AND c.relname <> 'commission_ledger_entry_default';

    IF target IS NULL OR target = 'commission_ledger_entry_default' THEN
        RAISE EXCEPTION 'no dedicated partition for period %', p_period_id;
    END IF;

    EXECUTE format('GRANT INSERT, UPDATE, DELETE ON %I TO eim_app', target);
    UPDATE payout_period
       SET status = 'OPEN', locked_at = NULL
     WHERE payout_period_id = reopen_period_partition.p_period_id;
    RETURN target;
END;
$$ LANGUAGE plpgsql;
