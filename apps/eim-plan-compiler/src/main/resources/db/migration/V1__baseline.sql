-- TG-0 baseline only. The real EIM schema (FR doc section 12) lands in TG-1.
CREATE TABLE IF NOT EXISTS schema_baseline (
    id BIGSERIAL PRIMARY KEY,
    service VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
INSERT INTO schema_baseline (service) VALUES ('eim-plan-compiler');
