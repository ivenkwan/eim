-- TG2-DAT/HIE: as-of rollup + wavefront layering for the ingestion tollgate.
--
-- Rollups recurse over the payee VERSION table (relational source of truth,
-- AD-3): the AGE graph is the validated projection -- reconcile_graph (V5)
-- gates drift to zero, so the relational tree IS the graph tree, read at
-- NFR-24 latency (the AGE-native edge dump costs ~0.5ms/edge in agtype
-- conversion and blows the 2s @ 5000-payee budget; decision recorded in the
-- TG-2 build log).
CREATE OR REPLACE FUNCTION eim_ops.rollup_scope_asof(p_manager uuid, p_as_of date)
    RETURNS TABLE(payee_id uuid)
    LANGUAGE plpgsql AS $fn$
BEGIN
    RETURN QUERY
    WITH RECURSIVE active AS (
        SELECT DISTINCT p.manager_payee_id AS a_mgr, p.payee_id AS a_child
        FROM payee p
        WHERE p.manager_payee_id IS NOT NULL
          AND p.effective_start_date <= p_as_of
          AND (p.effective_end_date IS NULL OR p.effective_end_date > p_as_of)
    ), walk AS (
        SELECT a.a_child AS w_payee FROM active a WHERE a.a_mgr = p_manager
        UNION
        SELECT a.a_child FROM active a JOIN walk w ON a.a_mgr = w.w_payee
    )
    SELECT w_payee FROM walk;
END;
$fn$;

-- Current scope: only OPEN versions count.
CREATE OR REPLACE FUNCTION eim_ops.rollup_scope(manager_id uuid)
    RETURNS TABLE(payee_id uuid)
    LANGUAGE plpgsql AS $fn$
BEGIN
    RETURN QUERY
    WITH RECURSIVE walk AS (
        SELECT p.payee_id AS w_payee FROM payee p
        WHERE p.manager_payee_id = manager_id AND p.effective_end_date IS NULL
        UNION
        SELECT p.payee_id FROM payee p JOIN walk w ON p.manager_payee_id = w.w_payee
        WHERE p.effective_end_date IS NULL
    )
    SELECT w_payee FROM walk;
END;
$fn$;

-- Wavefront layering (FR-503): topological depth of every payee (0 = leaf).
-- Levels drive parallel calculation scheduling.
CREATE OR REPLACE FUNCTION eim_ops.wavefront_levels()
    RETURNS TABLE(payee_id uuid, level bigint)
    LANGUAGE plpgsql AS $fn$
BEGIN
    RETURN QUERY
    WITH RECURSIVE tree AS (
        -- Leaves = payees who manage nobody; walk UP the manager chain.
        SELECT p.payee_id, p.manager_payee_id, 0 AS lvl
        FROM payee p
        WHERE NOT EXISTS (SELECT 1 FROM payee r WHERE r.manager_payee_id = p.payee_id)
        UNION ALL
        SELECT m.payee_id, m.manager_payee_id, t.lvl + 1
        FROM payee m JOIN tree t ON m.payee_id = t.manager_payee_id
    )
    SELECT tree.payee_id, max(tree.lvl)::bigint FROM tree GROUP BY tree.payee_id;
END;
$fn$;

-- Hierarchy validation helpers (FR-302.2): cycles and impossible overlaps.
CREATE OR REPLACE FUNCTION eim_ops.hierarchy_has_cycle()
    RETURNS boolean
    LANGUAGE plpgsql AS $fn$
BEGIN
    RETURN EXISTS (
        WITH RECURSIVE walk AS (
            SELECT payee_id, manager_payee_id, ARRAY[payee_id] AS path FROM payee WHERE manager_payee_id IS NOT NULL
            UNION ALL
            SELECT p.payee_id, p.manager_payee_id, w.path || p.payee_id
            FROM payee p JOIN walk w ON p.payee_id = w.manager_payee_id
            WHERE NOT p.payee_id = ANY(w.path)
        )
        -- Cycle = the manager we are about to visit is already on the path.
        SELECT 1 FROM walk WHERE manager_payee_id = ANY(path)
    );
END;
$fn$;

-- ---------------------------------------------------------------- SCD-2 graph
-- V5's projection never CLOSED a MANAGES_OF edge: closing a payee version or
-- switching managers left the old edge open forever, breaking as-of rollups.
CREATE OR REPLACE FUNCTION eim_ops.close_manages_of(p_child uuid, p_manager uuid, p_to date)
    RETURNS void AS $fn$
BEGIN
    IF p_manager IS NULL OR p_to IS NULL THEN
        RETURN;
    END IF;
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    EXECUTE format(
        $q$SELECT * FROM ag_catalog.cypher('eim_graph',
        $cy$ MATCH (r:Payee {payee_id: "''%1$s''"})-[e:MANAGES_OF]->(m:Payee {payee_id: "''%2$s''"})
        WHERE e.to_date IS NULL
        SET e.to_date = "''%3$s''" $cy$) AS (e ag_catalog.agtype)$q$,
        p_child::text, p_manager::text, p_to::text);
END;
$fn$ LANGUAGE plpgsql;

-- Version-aware edge projection: SET both ends of the effective range.
CREATE OR REPLACE FUNCTION eim_ops.project_manages_of(p payee) RETURNS void AS $fn$
DECLARE
    v_to text;
BEGIN
    IF p.manager_payee_id IS NULL THEN
        RETURN;
    END IF;
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    PERFORM eim_ops.project_payee(p);
    v_to := CASE WHEN p.effective_end_date IS NULL THEN 'NULL'
                 ELSE '"' || '''' || p.effective_end_date::text || '''' || '"' END;
    EXECUTE format(
        $q$SELECT * FROM ag_catalog.cypher('eim_graph',
        $cy$ MATCH (m:Payee {payee_id: "''%1$s''"}), (r:Payee {payee_id: "''%2$s''"})
        MERGE (r)-[e:MANAGES_OF]->(m)
        SET e.from_date = "''%3$s''", e.to_date = %4$s $cy$) AS (e ag_catalog.agtype)$q$,
        p.manager_payee_id::text, p.payee_id::text, p.effective_start_date::text, v_to);
END;
$fn$ LANGUAGE plpgsql;

-- Trigger rewrite: an UPDATE that changes (or closes) a version must close
-- the old manager's edge the day before the new assignment starts.
CREATE OR REPLACE FUNCTION eim_ops.payee_graph_trigger() RETURNS trigger AS $fn$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.manager_payee_id IS NOT NULL
       AND OLD.manager_payee_id IS DISTINCT FROM NEW.manager_payee_id THEN
        PERFORM eim_ops.close_manages_of(OLD.payee_id, OLD.manager_payee_id,
            COALESCE(NEW.effective_start_date, NEW.effective_end_date, current_date) - 1);
    END IF;
    PERFORM eim_ops.project_payee(NEW);
    PERFORM eim_ops.project_manages_of(NEW);
    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;
