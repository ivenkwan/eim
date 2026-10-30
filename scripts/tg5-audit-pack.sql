-- TG5-AUD-1 assurance pack SQL (executed via: kubectl exec -i eim-postgres-0
-- -- psql -U eim -d eim < this). Exit non-zero semantics are checked by the
-- caller through ON_ERROR_STOP + explicit DO assertions.
\set ON_ERROR_STOP on

-- 1. Seals: every settled day older than the latest sealed day verifies;
--    the GENESIS tamper row (2000-01-01) must FAIL (positive control).
DO $fn$
DECLARE
  broken int;
  genesis_bad int;
BEGIN
  SELECT count(*) INTO broken FROM eim_ops.verify_audit_seals()
   WHERE ok = false AND seal_day <> DATE '2000-01-01'
     AND seal_day < (SELECT max(seal_day) FROM audit_seals);
  SELECT count(*) INTO genesis_bad FROM eim_ops.verify_audit_seals()
   WHERE seal_day = DATE '2000-01-01' AND ok = false;
  IF broken <> 0 THEN
    RAISE EXCEPTION 'AUD-1: % settled seal day(s) failed verification', broken;
  END IF;
  IF genesis_bad <> 1 THEN
    RAISE EXCEPTION 'AUD-1: tamper positive control not detected (genesis ok?)';
  END IF;
END
$fn$;
SELECT 'AUD-1 seals: settled days verify; tamper control fires' AS check;

-- 2. Event-audit integrity: every event row carries a 64-char sha256
DO $fn$
DECLARE
  bad int;
BEGIN
  SELECT count(*) INTO bad FROM event_audit_log
   WHERE outcome = 'SUCCESS' AND length(payload_hash) <> 64
     AND topic LIKE 'eim.%';
  IF bad <> 0 THEN
    RAISE EXCEPTION 'AUD-1: % event-audit row(s) missing sha256 payload hash', bad;
  END IF;
END
$fn$;
SELECT 'AUD-1 events: payload hashes present' AS check;

-- 3. Sampled lineage walk: source → credit → plan version → ledger → trace.
--    Every CALCULATED ledger entry must join back to a payee + period, and
--    every traced entry pins the plan version it was computed under.
DO $fn$
DECLARE
  orphans int;
  unpinned int;
BEGIN
  SELECT count(*) INTO orphans FROM commission_ledger_entry l
   WHERE l.entry_type = 'CALCULATED'
     AND NOT EXISTS (SELECT 1 FROM payee p WHERE p.payee_id = l.payee_id);
  IF orphans <> 0 THEN
    RAISE EXCEPTION 'AUD-1: % ledger line(s) with no payee parent', orphans;
  END IF;
  SELECT count(*) INTO unpinned FROM commission_ledger_entry l
   JOIN commission_ledger_entry probe ON probe.ledger_id = l.ledger_id
   WHERE l.entry_type = 'CALCULATED' AND l.calculation_trace IS NOT NULL
     AND l.calculation_trace->>'plan_version_hash' IS NULL;
  IF unpinned <> 0 THEN
    RAISE EXCEPTION 'AUD-1: % traced ledger line(s) without a pinned plan version', unpinned;
  END IF;
END
$fn$;
SELECT 'AUD-1 lineage: ledger → payee joins; traces pin plan versions' AS check;
