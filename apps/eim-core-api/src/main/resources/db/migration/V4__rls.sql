-- TG1-DAT-3: Row-level security keyed on session GUCs set per request from
-- validated JWT claims (FR-103, NFR-52). The application connects as
-- eim_app; table ownership stays with the migration role.

-- ---------------------------------------------------------------- app role
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eim_app') THEN
        CREATE ROLE eim_app NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO eim_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO eim_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO eim_app;

-- Append-only: the app may only INSERT into the audit plane (FR-601.3).
REVOKE UPDATE, DELETE ON audit_log, event_audit_log, configuration_change, audit_seals FROM eim_app;

-- Ledger: the app may read + insert; ledger rows are immutable via policy.
REVOKE UPDATE, DELETE ON commission_ledger_entry FROM eim_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE ON TABLES TO eim_app;

-- ---------------------------------------------------------------- RLS: tenant scope
ALTER TABLE payee ENABLE ROW LEVEL SECURITY;
ALTER TABLE comp_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE raw_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE commission_ledger_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispute_ticket ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE configuration ENABLE ROW LEVEL SECURITY;
ALTER TABLE configuration_change ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON payee
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON comp_plan
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON plan_version
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON plan_assignment
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON payout_period
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON raw_transaction
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON credit_allocation
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON commission_ledger_entry
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON dispute_ticket
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id IS NULL OR tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON event_audit_log
    USING (tenant_id IS NULL OR tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON configuration
    USING (tenant_id IS NULL OR tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY tenant_isolation ON configuration_change
    USING (tenant_id IS NULL OR tenant_id::text = current_setting('app.current_tenant_id', true));

-- ---------------------------------------------------------------- RLS: payee scope
-- Payees (and managers via graph, TG-2) see only their own ledger/disputes.
-- Admin roles see all rows of their tenant: the service sets
-- app.current_payee_id = '' (empty) for comp-admin/finance sessions.
CREATE POLICY payee_scope ON commission_ledger_entry
    USING (
        current_setting('app.current_payee_id', true) = ''
        OR payee_id::text = current_setting('app.current_payee_id', true)
    );
CREATE POLICY payee_scope ON dispute_ticket
    USING (
        current_setting('app.current_payee_id', true) = ''
        OR payee_id::text = current_setting('app.current_payee_id', true)
    );

-- RLS applies to the eim_app role; the owner bypasses it (migrations/ops).
-- Tests assert enforcement with `SET ROLE eim_app`; the prod posture connects
-- the service as a eim_app LOGIN role created via eim_ops.create_app_login_role.
-- Service request paths escalate per-transaction via `SET LOCAL ROLE eim_app`.
GRANT eim_app TO CURRENT_USER;
