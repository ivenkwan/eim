-- TG1-AUD-1/3: audit plane — append-only stores + transactional outbox.
-- Tamper-evidence: UPDATE/DELETE raise; privileges revoked in V4 (NFR-60).
CREATE SCHEMA IF NOT EXISTS eim_audit;

-- ---------------------------------------------------------------- audit_log (action stream)
CREATE TABLE audit_log (
    audit_id        BIGSERIAL PRIMARY KEY,
    tenant_id       UUID,
    actor           VARCHAR(128) NOT NULL,
    actor_type      VARCHAR(16) NOT NULL DEFAULT 'USER', -- USER|SERVICE|PROCESS|SYSTEM
    action_type     VARCHAR(32) NOT NULL,  -- FR-601.2 enum
    target_entity   VARCHAR(128) NOT NULL,
    target_record_id VARCHAR(128),
    before_state    JSONB,
    after_state     JSONB,
    reason_code     VARCHAR(32),
    reason_comment  TEXT,
    correlation_id  VARCHAR(128),
    source_channel  VARCHAR(16) NOT NULL DEFAULT 'API', -- UI|API|EVENT|PROCESS
    outcome         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_time ON audit_log (occurred_at);
CREATE INDEX ix_audit_actor ON audit_log (actor);
CREATE INDEX ix_audit_target ON audit_log (target_entity, target_record_id);

-- ---------------------------------------------------------------- event_audit_log (event stream)
CREATE TABLE event_audit_log (
    event_audit_id  BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(128),
    topic           VARCHAR(128) NOT NULL,
    kafka_partition INTEGER,
    kafka_offset    BIGINT,
    payload         JSONB NOT NULL,
    payload_hash    VARCHAR(64) NOT NULL, -- sha256
    schema_version  INTEGER,
    producer        VARCHAR(128),
    consumer        VARCHAR(128),
    correlation_id  VARCHAR(128),
    tenant_id       UUID,
    outcome         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS', -- SUCCESS|QUARANTINED|RETRIED|DEAD_LETTERED
    error_detail    TEXT,
    occurred_at     TIMESTAMP WITH TIME ZONE,
    consumed_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_audit_topic ON event_audit_log (topic, occurred_at);
CREATE UNIQUE INDEX uq_event_audit_dedupe ON event_audit_log (topic, event_id) WHERE event_id IS NOT NULL;

-- ---------------------------------------------------------------- configuration registry (AUD-5)
CREATE TABLE configuration (
    configuration_id UUID PRIMARY KEY,
    tenant_id        UUID,
    entity           VARCHAR(128) NOT NULL, -- e.g. connector.salesforce, policy.asc606
    version          INTEGER NOT NULL,
    payload          JSONB NOT NULL,
    governed         BOOLEAN NOT NULL DEFAULT FALSE,
    status           VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|PENDING_APPROVAL|SUPERSEDED
    created_by       VARCHAR(128) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (entity, version)
);

CREATE TABLE configuration_change (
    change_id       BIGSERIAL PRIMARY KEY,
    tenant_id       UUID,
    entity          VARCHAR(128) NOT NULL,
    record_id       VARCHAR(128),
    version_before  JSONB,
    version_after   JSONB NOT NULL,
    actor           VARCHAR(128) NOT NULL,
    actor_type      VARCHAR(16) NOT NULL DEFAULT 'USER',
    reason          TEXT,
    governed        BOOLEAN NOT NULL DEFAULT FALSE,
    approval_ref    VARCHAR(128),
    correlation_id  VARCHAR(128),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_config_change_entity ON configuration_change (entity, created_at);

-- ---------------------------------------------------------------- transactional outbox (AUD-1)
CREATE TABLE transactional_outbox (
    outbox_id      BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(128) NOT NULL, -- e.g. configuration.changed
    aggregate_id   VARCHAR(128) NOT NULL,
    payload        JSONB NOT NULL,
    correlation_id VARCHAR(128),
    tenant_id      UUID,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON transactional_outbox (outbox_id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------- audit seals (AUD-6)
CREATE TABLE audit_seals (
    seal_id    BIGSERIAL PRIMARY KEY,
    seal_day   DATE NOT NULL UNIQUE,
    row_count  BIGINT NOT NULL,
    seal_hash  CHAR(64) NOT NULL,  -- sha256(prev_seal || day rows in audit_id order)
    prev_seal  CHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- append-only enforcement
CREATE OR REPLACE FUNCTION eim_audit.reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only violation: % on % is forbidden (NFR-60)',
        TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION eim_audit.reject_mutation();
CREATE TRIGGER trg_event_audit_append_only
    BEFORE UPDATE OR DELETE ON event_audit_log
    FOR EACH ROW EXECUTE FUNCTION eim_audit.reject_mutation();
CREATE TRIGGER trg_configuration_change_append_only
    BEFORE UPDATE OR DELETE ON configuration_change
    FOR EACH ROW EXECUTE FUNCTION eim_audit.reject_mutation();
CREATE TRIGGER trg_audit_seals_append_only
    BEFORE UPDATE OR DELETE ON audit_seals
    FOR EACH ROW EXECUTE FUNCTION eim_audit.reject_mutation();
