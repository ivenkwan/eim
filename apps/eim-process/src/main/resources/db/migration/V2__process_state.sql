-- TG4 process state: the Kogito-era workflow tables (period close,
-- dispute lifecycle, true-up review) for the dev-posture process service.
-- Kogito persistence rides the staging/prod profile; the dev service keeps
-- the same state machine in these tables so the tollgate gates run on the
-- real database, not an in-memory stub.

CREATE TABLE IF NOT EXISTS process_instance (
    process_id     UUID PRIMARY KEY,
    process_type   VARCHAR(64) NOT NULL,  -- period_close|dispute_lifecycle|trueup_review
    tenant_id      UUID NOT NULL,
    business_key   VARCHAR(128),
    state          VARCHAR(32) NOT NULL DEFAULT 'STARTED', -- STARTED|WAITING|COMPLETED|ABORTED
    current_step   VARCHAR(64),
    variables      JSONB NOT NULL DEFAULT '{}'::jsonb,
    stall_deadline TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_process_type_state ON process_instance (process_type, state);

CREATE TABLE IF NOT EXISTS process_task (
    task_id      UUID PRIMARY KEY,
    process_id   UUID NOT NULL REFERENCES process_instance (process_id),
    name         VARCHAR(64) NOT NULL,
    assignee_role VARCHAR(64) NOT NULL,
    state        VARCHAR(32) NOT NULL DEFAULT 'READY', -- READY|COMPLETED|SKIPPED
    comment      TEXT,
    decided_by   VARCHAR(128),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS ix_task_process ON process_task (process_id);
