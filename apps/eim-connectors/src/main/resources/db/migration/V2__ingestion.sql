-- TG2-ING: ingestion plane (owned by eim-connectors; own Flyway history, ADR-010).
CREATE TABLE connector_state (
    connector_name  VARCHAR(64) NOT NULL,
    object_name     VARCHAR(64) NOT NULL,
    watermark_column VARCHAR(64),
    watermark_value TEXT,
    last_run_at     TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (connector_name, object_name)
);

CREATE TABLE ingestion_batch (
    ingestion_batch_id UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    source_system      VARCHAR(32) NOT NULL, -- SALESFORCE|HUBSPOT|SNOWFLAKE|CSV
    object_name        VARCHAR(64) NOT NULL,
    status             VARCHAR(32) NOT NULL, -- RUNNING|COMPLETED|FAILED
    triggered_by       VARCHAR(128) NOT NULL,
    correlation_id     VARCHAR(128),
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at        TIMESTAMP WITH TIME ZONE,
    total_rows         INTEGER NOT NULL DEFAULT 0,
    accepted_rows      INTEGER NOT NULL DEFAULT 0,
    quarantined_rows   INTEGER NOT NULL DEFAULT 0,
    watermark_after    TEXT,
    snapshot_object_key TEXT
);

CREATE TABLE ingestion_record (
    record_id     UUID PRIMARY KEY,
    batch_id      UUID NOT NULL REFERENCES ingestion_batch (ingestion_batch_id),
    external_id   VARCHAR(128),
    status        VARCHAR(16) NOT NULL, -- ACCEPTED|QUARANTINED
    raw_payload   JSONB NOT NULL,
    errors        JSONB,
    transaction_id UUID, -- set when ACCEPTED (FK added after raw_transaction exists)
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_ing_record_batch ON ingestion_record (batch_id);
CREATE INDEX ix_ing_record_ext ON ingestion_record (external_id);

-- Late-arriving detection support: look up locked periods quickly.
CREATE INDEX ix_period_status ON payout_period (tenant_id, status);
