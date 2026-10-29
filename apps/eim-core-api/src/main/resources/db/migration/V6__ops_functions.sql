-- TG1-DAT-2 / AUD-6 / AUD-9: operational functions.
-- Partition lifecycle, period locking, audit hash-chain sealing + verification,
-- and the mutation-vs-outbox-vs-event-audit reconciliation (NFR-64).

-- ---------------------------------------------------------------- partitions
-- Create the LIST partition for a payout period (idempotent).
CREATE OR REPLACE FUNCTION eim_ops.ensure_period_partition(p_period_id uuid, p_period_key text)
RETURNS text AS $$
DECLARE
    partition_name text := 'ledger_' || regexp_replace(p_period_key, '[^a-zA-Z0-9_]', '_', 'g');
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = partition_name) THEN
        RETURN partition_name;
    END IF;
    EXECUTE format('CREATE TABLE %I PARTITION OF commission_ledger_entry FOR VALUES IN (%L)',
                   partition_name, p_period_id);
    RETURN partition_name;
END;
$$ LANGUAGE plpgsql;

-- Lock a period partition (FR-603 / NFR-44): revoke writes from the app role
-- and mark the period LOCKED.
CREATE OR REPLACE FUNCTION eim_ops.lock_period_partition(p_period_id uuid)
RETURNS text AS $$
DECLARE
    target text;
BEGIN
    SELECT DISTINCT c.relname INTO target
    FROM commission_ledger_entry l
    JOIN pg_class c ON c.oid = l.tableoid
    WHERE l.payout_period_id = lock_period_partition.p_period_id;

    IF target IS NULL THEN
        SELECT c.relname INTO target
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'commission_ledger_entry'::regclass
          AND pg_get_expr(c.relpartbound, c.oid)
              LIKE format('%%(%L)%%', p_period_id::text)
          AND c.relname <> 'commission_ledger_entry_default';
    END IF;

    IF target IS NULL OR target = 'commission_ledger_entry_default' THEN
        RAISE EXCEPTION 'no dedicated partition for period %', p_period_id;
    END IF;

    EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON %I FROM eim_app', target);
    UPDATE payout_period
       SET status = 'LOCKED', locked_at = now()
     WHERE payout_period_id = lock_period_partition.p_period_id;
    RETURN target;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------- audit seals (AUD-6)
-- Seal one day: sha256 chain over audit_log rows in audit_id order.
CREATE OR REPLACE FUNCTION eim_ops.seal_audit_day(p_day date)
RETURNS text AS $$
DECLARE
    v_prev text;
    v_rows bigint;
    v_hash text;
    v_running text;
    r record;
BEGIN
    IF EXISTS (SELECT 1 FROM audit_seals WHERE seal_day = p_day) THEN
        RETURN 'already-sealed';
    END IF;

    SELECT coalesce((
        SELECT seal_hash FROM audit_seals WHERE seal_day < p_day ORDER BY seal_day DESC LIMIT 1
    ), 'GENESIS') INTO v_prev;

    SELECT count(*) INTO v_rows FROM audit_log
    WHERE occurred_at::date = p_day;

    IF v_rows = 0 THEN
        RAISE EXCEPTION 'refusing to seal an empty day: %', p_day;
    END IF;

    v_running := v_prev;
    FOR r IN
        SELECT audit_id::text AS id,
               md5(coalesce(actor,'') || '|' || coalesce(action_type,'') || '|'
                   || coalesce(target_entity,'') || '|' || coalesce(target_record_id,'')
                   || '|' || coalesce(occurred_at::text,'')) AS row_hash
        FROM audit_log
        WHERE occurred_at::date = p_day
        ORDER BY audit_id
    LOOP
        v_running := encode(digest(v_running || '|' || r.id || '|' || r.row_hash, 'sha256'), 'hex');
    END LOOP;
    v_hash := v_running;

    INSERT INTO audit_seals (seal_day, row_count, seal_hash, prev_seal)
    VALUES (p_day, v_rows, v_hash, CASE WHEN v_prev = 'GENESIS' THEN NULL ELSE v_prev END);
    RETURN v_hash;
END;
$$ LANGUAGE plpgsql;

-- Verify all seals by recomputation (NFR-60 evidence tool).
CREATE OR REPLACE FUNCTION eim_ops.verify_audit_seals()
RETURNS TABLE(seal_day date, ok boolean, detail text)
    LANGUAGE plpgsql AS $$
DECLARE
    v_prev text := 'GENESIS';
    v_running text;
    r record;
    a record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgcrypto') THEN
        CREATE EXTENSION IF NOT EXISTS pgcrypto;
    END IF;
    FOR a IN SELECT * FROM audit_seals ORDER BY seal_day LOOP
        v_running := v_prev;
        FOR r IN
            SELECT audit_id::text AS id,
                   md5(coalesce(actor,'') || '|' || coalesce(action_type,'') || '|'
                       || coalesce(target_entity,'') || '|' || coalesce(target_record_id,'')
                       || '|' || coalesce(occurred_at::text,'')) AS row_hash
            FROM audit_log
            WHERE occurred_at::date = a.seal_day
            ORDER BY audit_id
        LOOP
            v_running := encode(digest(v_running || '|' || r.id || '|' || r.row_hash, 'sha256'), 'hex');
        END LOOP;
        seal_day := a.seal_day;
        ok := (v_running = a.seal_hash) AND (coalesce(a.prev_seal, 'GENESIS') = v_prev);
        detail := CASE WHEN ok THEN 'verified'
                       ELSE format('recomputed %s but seal says %s', v_running, a.seal_hash) END;
        RETURN NEXT;
        v_prev := a.seal_hash;
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------- reconciliation (AUD-9)
-- Compare audited mutations vs outbox events vs published event-audit rows
-- for a time window; non-zero gaps indicate lost audit (NFR-64 alert source).
CREATE OR REPLACE FUNCTION eim_ops.reconcile_audit(since timestamp with time zone)
RETURNS TABLE(audited_mutations bigint, outbox_rows bigint, published_events bigint, gap bigint)
    LANGUAGE sql AS $$
    WITH m AS (SELECT count(*) c FROM audit_log WHERE occurred_at >= since),
         o AS (SELECT count(*) c FROM transactional_outbox WHERE occurred_at >= since),
         e AS (SELECT count(*) c FROM event_audit_log WHERE consumed_at >= since)
    SELECT m.c, o.c, e.c, (m.c - e.c)
    FROM m, o, e
$$;

-- ---------------------------------------------------------------- app login role
-- Prod posture: service connects as this LOGIN role (RLS-enforced).
CREATE OR REPLACE FUNCTION eim_ops.create_app_login_role(p_password text)
RETURNS void AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eim_app_login') THEN
        EXECUTE format('CREATE ROLE eim_app_login LOGIN PASSWORD %L', p_password);
    ELSE
        EXECUTE format('ALTER ROLE eim_app_login PASSWORD %L', p_password);
    END IF;
    EXECUTE 'GRANT eim_app TO eim_app_login';
END;
$$ LANGUAGE plpgsql;

-- pgcrypto for digest() used by the sealer.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
