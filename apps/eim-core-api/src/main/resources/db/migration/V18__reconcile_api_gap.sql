-- TG9 (A2 / NFR-64): the legacy reconcile_audit alert fires on
-- `mutations − events` over ALL audit rows. Two categories inflate that
-- gap without being integrity failures:
--   1. process transitions (source_channel='PROCESS') are audited-only by
--      design — the process engine records APPROVAL trail rows that
--      deliberately emit no domain event;
--   2. unit tests seed audit rows directly via SQL (no outbox) on the
--      shared dev database.
-- The alert contract stays: every API-plane mutation (the channel carrying
-- the FR-601.4 outbox guarantee) must pair with an outbox row. This adds
-- reconcile_audit_full — the legacy function is untouched (its return type
-- cannot change under CREATE OR REPLACE and the TG-5 assurance pack still
-- calls it).

CREATE OR REPLACE FUNCTION eim_ops.reconcile_audit_full(since timestamp with time zone)
RETURNS TABLE(audited_mutations bigint, outbox_rows bigint, published_events bigint,
             raw_gap bigint, api_mutations bigint, api_paired_outbox bigint, api_gap bigint)
    LANGUAGE sql AS $$
    WITH m AS (SELECT count(*) c FROM audit_log WHERE occurred_at >= since),
         o AS (SELECT count(*) c FROM transactional_outbox WHERE occurred_at >= since),
         e AS (SELECT count(*) c FROM event_audit_log WHERE consumed_at >= since),
         ma AS (SELECT count(*) c FROM audit_log
                  WHERE occurred_at >= since AND source_channel = 'API'),
         oa AS (SELECT count(*) c FROM transactional_outbox o
                  WHERE o.occurred_at >= since
                  AND EXISTS (SELECT 1 FROM audit_log a
                               WHERE a.source_channel = 'API'
                                 AND a.occurred_at >= since
                                 AND a.target_record_id = o.aggregate_id))
    SELECT m.c, o.c, e.c, (m.c - e.c), ma.c, oa.c, (ma.c - oa.c)
    FROM m, o, e, ma, oa
$$;
