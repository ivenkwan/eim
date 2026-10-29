-- TG1 fix-up migrations:
-- Parent-level lock enforcement. Revoking privileges on a partition does NOT
-- block INSERTs routed through the partitioned parent (PostgreSQL checks the
-- parent), so a BEFORE trigger on the parent guards every write against
-- LOCKED periods (FR-603, NFR-44). The partition-level REVOKE stays as
-- defense-in-depth.

CREATE OR REPLACE FUNCTION eim_ops.guard_locked_period() RETURNS trigger AS $$
DECLARE
    v_status text;
BEGIN
    SELECT status INTO v_status FROM payout_period
    WHERE payout_period_id = NEW.payout_period_id;
    IF v_status = 'LOCKED' THEN
        RAISE EXCEPTION 'period % is LOCKED: writes forbidden (FR-603, NFR-44)',
            NEW.payout_period_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ledger_lock_guard ON commission_ledger_entry;
CREATE TRIGGER trg_ledger_lock_guard
    BEFORE INSERT OR UPDATE ON commission_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION eim_ops.guard_locked_period();
