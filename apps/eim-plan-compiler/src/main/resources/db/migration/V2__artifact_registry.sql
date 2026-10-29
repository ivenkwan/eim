-- TG3-RUL-6: immutable artifact registry. Every publish writes one row
-- keyed by the canonical plan-version hash; versions coexist (NFR-62) and
-- rows are append-only (UPDATE/DELETE rejected like the audit plane).
CREATE TABLE IF NOT EXISTS plan_artifact (
    plan_version_hash VARCHAR(64) PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    plan_name         VARCHAR(200) NOT NULL,
    plan_json         JSONB NOT NULL,
    drl               TEXT NOT NULL,
    helper_java       TEXT NOT NULL,
    dmn               TEXT NOT NULL,
    published_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_plan_artifact_tenant ON plan_artifact (tenant_id);

CREATE SCHEMA IF NOT EXISTS eim_guard;
CREATE OR REPLACE FUNCTION eim_guard.reject_artifact_mutation() RETURNS trigger AS $fn$
BEGIN
    RAISE EXCEPTION 'plan artifacts are immutable (NFR-62): publish a new version instead'
        USING ERRCODE = 'restrict_violation';
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_plan_artifact_append_only ON plan_artifact;
CREATE TRIGGER trg_plan_artifact_append_only
    BEFORE UPDATE OR DELETE ON plan_artifact
    FOR EACH ROW EXECUTE FUNCTION eim_guard.reject_artifact_mutation();
