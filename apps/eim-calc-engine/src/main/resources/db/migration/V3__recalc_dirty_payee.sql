-- TG9-C2 (FR-509 hardening): the incremental-recalc dirty set. The TG-7
-- endpoint recomputed the dirty set by timestamp diff on every call —
-- correct, but it rescans allocations and can drift at cohort scale. This
-- is the materialized cache: ingestion/markDirty writers insert one row
-- per dirty payee (ON CONFLICT refresh), the incremental runner drains it
-- (consumed_at) and falls back to the timestamp diff when empty — so the
-- cache is an accelerator, never the source of truth.
CREATE TABLE IF NOT EXISTS recalc_dirty_payee (
    tenant_id   UUID NOT NULL,
    payee_id    UUID NOT NULL,
    dirtied_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    source      VARCHAR(32) NOT NULL DEFAULT 'INGEST',
    consumed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, payee_id, dirtied_at)
);
CREATE INDEX IF NOT EXISTS ix_dirty_unconsumed ON recalc_dirty_payee (tenant_id)
    WHERE consumed_at IS NULL;
