-- TG-0 baseline only. The real EIM schema (FR doc section 12) lands in TG-1.
-- NOTE: this file MUST be byte-identical across all five services — they share
-- one database and Flyway history; per-service schemas get per-service history
-- tables in TG-1.
CREATE TABLE IF NOT EXISTS schema_baseline (
    id BIGSERIAL PRIMARY KEY,
    service VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
INSERT INTO schema_baseline (service) VALUES ('eim-platform');
