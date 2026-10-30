-- TG6 (SOC2 evidence gate): audit seal days are now pinned to UTC.
-- Previously seal_audit_day/verify_audit_seals used occurred_at::date,
-- which resolves in the SESSION timezone: the sealer's JDBC session (JVM
-- default tz) could seal a different row set than a verifier running in the
-- database's timezone — a same-day re-verification then legitimately
-- reported a mismatch that was not tampering. All seal-day boundaries are
-- now explicit UTC so sealer, verifier and manual runs agree by definition.

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
    WHERE (occurred_at AT TIME ZONE 'UTC')::date = p_day;

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
        WHERE (occurred_at AT TIME ZONE 'UTC')::date = p_day
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
            WHERE (occurred_at AT TIME ZONE 'UTC')::date = a.seal_day
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
