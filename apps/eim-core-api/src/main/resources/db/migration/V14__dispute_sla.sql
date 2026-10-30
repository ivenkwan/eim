-- TG7-GOV-2 (FR-705.2): dispute SLA escalation tracking.
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS escalated_to VARCHAR(64);
