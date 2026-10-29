-- TG1-DAT-4: AGE graph projection — MANAGES_OF / CREDITED_TO / DEPENDS_ON.
-- Relational tables remain the source of truth (AD-3); projection functions
-- sync the graph and a reconciler detects drift (NFR-93).
-- NOTE: ag_catalog.cypher takes cstring parameters, which cannot be bound —
-- dynamic cypher goes through EXECUTE; function bodies use $fn$ tags so the
-- inner $cy$ dollar-quotes stay intact.
CREATE SCHEMA IF NOT EXISTS eim_ops;
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';

-- Idempotent graph creation (initdb may have created it already).
DO $fn$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM ag_catalog.ag_graph WHERE name = 'eim_graph') THEN
        PERFORM ag_catalog.create_graph('eim_graph'::name);
    END IF;
END $fn$;

-- Idempotent label creation.
DO $fn$
DECLARE
    lbl text;
BEGIN
    FOREACH lbl IN ARRAY ARRAY['Payee','Transaction','PlanComponent'] LOOP
        IF NOT EXISTS (
            SELECT 1 FROM ag_catalog.ag_label
            WHERE name = lbl AND kind = 'v'
              AND graph = (SELECT graphid FROM ag_catalog.ag_graph WHERE name = 'eim_graph')
        ) THEN
            EXECUTE format('SELECT ag_catalog.create_vlabel(''%s'', ''%s'')', 'eim_graph', lbl);
        END IF;
    END LOOP;
    FOREACH lbl IN ARRAY ARRAY['MANAGES_OF','CREDITED_TO','DEPENDS_ON'] LOOP
        IF NOT EXISTS (
            SELECT 1 FROM ag_catalog.ag_label
            WHERE name = lbl AND kind = 'e'
              AND graph = (SELECT graphid FROM ag_catalog.ag_graph WHERE name = 'eim_graph')
        ) THEN
            EXECUTE format('SELECT ag_catalog.create_elabel(''%s'', ''%s'')', 'eim_graph', lbl);
        END IF;
    END LOOP;
END $fn$;

-- ---------------------------------------------------------------- helpers
CREATE OR REPLACE FUNCTION eim_ops.payee_in_graph(p_payee uuid) RETURNS boolean AS $fn$
DECLARE
    found boolean;
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM ag_catalog.cypher(''eim_graph'', '
        || '$cy$ MATCH (n:Payee {payee_id: "''''%s''''"}) RETURN n $cy$'
        || ') AS (n ag_catalog.agtype))',
        p_payee::text) INTO found;
    RETURN found;
END;
$fn$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION eim_ops.manages_of_edge_in_graph(p_child uuid, p_manager uuid)
RETURNS boolean AS $fn$
DECLARE
    found boolean;
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM ag_catalog.cypher(''eim_graph'', '
        || '$cy$ MATCH (r:Payee {payee_id: "''''%1$s''''"})-[:MANAGES_OF]->'
        || '(m:Payee {payee_id: "''''%2$s''''"}) RETURN 1 $cy$'
        || ') AS (one ag_catalog.agtype))',
        p_child::text, p_manager::text) INTO found;
    RETURN found;
END;
$fn$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION eim_ops.detach_delete_payee(p_payee uuid) RETURNS void AS $fn$
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    EXECUTE format(
        'SELECT * FROM ag_catalog.cypher(''eim_graph'', '
        || '$cy$ MATCH (n:Payee {payee_id: "''''%s''''"}) DETACH DELETE n $cy$'
        || ') AS (r ag_catalog.agtype)',
        p_payee::text);
END;
$fn$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------- projection
-- Upsert a Payee vertex. Values come from typed columns (uuid/date) or are
-- literal-quoted — no string concatenation of untyped input.
CREATE OR REPLACE FUNCTION eim_ops.project_payee(p payee) RETURNS void AS $fn$
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    EXECUTE format(
        'SELECT * FROM ag_catalog.cypher(''eim_graph'', '
        || '$cy$ MERGE (n:Payee {payee_id: "''''%1$s''''"}) '
        || 'SET n.tenant_id = "''''%2$s''''", n.full_name = %3$L, n.role_title = %4$L, '
        || 'n.effective_start_date = "''''%5$s''''" $cy$'
        || ') AS (n ag_catalog.agtype)',
        p.payee_id::text, p.tenant_id::text,
        coalesce(p.full_name, ''), coalesce(p.role_title, ''),
        p.effective_start_date::text);
END;
$fn$ LANGUAGE plpgsql;

-- MANAGES_OF edge with effective dates (FR-302/303).
CREATE OR REPLACE FUNCTION eim_ops.project_manages_of(p payee) RETURNS void AS $fn$
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    IF p.manager_payee_id IS NULL THEN
        RETURN;
    END IF;
    PERFORM eim_ops.project_payee(p);
    EXECUTE format(
        'SELECT * FROM ag_catalog.cypher(''eim_graph'', '
        || '$cy$ MATCH (m:Payee {payee_id: "''''%1$s''''"}), '
        || '(r:Payee {payee_id: "''''%2$s''''"}) MERGE (r)-[e:MANAGES_OF]->(m) '
        || 'SET e.from_date = "''''%3$s''''" $cy$'
        || ') AS (e ag_catalog.agtype)',
        p.manager_payee_id::text, p.payee_id::text, p.effective_start_date::text);
END;
$fn$ LANGUAGE plpgsql;

-- Table trigger: every payee write mirrors into the graph (same tx).
CREATE OR REPLACE FUNCTION eim_ops.payee_graph_trigger() RETURNS trigger AS $fn$
BEGIN
    PERFORM eim_ops.project_payee(COALESCE(NEW, OLD));
    PERFORM eim_ops.project_manages_of(COALESCE(NEW, OLD));
    RETURN COALESCE(NEW, OLD);
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payee_graph ON payee;
CREATE TRIGGER trg_payee_graph
    AFTER INSERT OR UPDATE ON payee
    FOR EACH ROW EXECUTE FUNCTION eim_ops.payee_graph_trigger();

-- ---------------------------------------------------------------- reconciliation
-- Drift = payees missing in the graph or missing MANAGES_OF edges (NFR-93).
CREATE OR REPLACE FUNCTION eim_ops.reconcile_graph() RETURNS TABLE(drift text)
    LANGUAGE plpgsql AS $fn$
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    RETURN QUERY
    SELECT 'payee missing in graph: ' || p.payee_id::text
    FROM payee p
    WHERE NOT eim_ops.payee_in_graph(p.payee_id);

    RETURN QUERY
    SELECT 'manages_of edge missing: ' || p.payee_id::text || ' -> ' || p.manager_payee_id::text
    FROM payee p
    WHERE p.manager_payee_id IS NOT NULL
      AND NOT eim_ops.manages_of_edge_in_graph(p.payee_id, p.manager_payee_id);

    -- Orphan vertices: in the graph but not in the table. Constant statement
    -- (no parameters), so dollar-quoting avoids nested-quote escaping entirely.
    RETURN QUERY EXECUTE
        $sql$SELECT 'orphan graph vertex: ' || btrim(gid::text, ' ''"')
        FROM ag_catalog.cypher('eim_graph', $cy$ MATCH (n:Payee) RETURN n.payee_id AS gid $cy$)
        AS (gid ag_catalog.agtype)
        WHERE btrim(gid::text, ' ''"') NOT IN (SELECT payee_id::text FROM payee)$sql$;
END;
$fn$;

-- Rollup scope (FR-303): all payees under a manager (transitive).
CREATE OR REPLACE FUNCTION eim_ops.rollup_scope(manager_id uuid)
    RETURNS TABLE(payee_id uuid)
    LANGUAGE plpgsql AS $fn$
BEGIN
    EXECUTE 'LOAD ''age''';
    EXECUTE 'SET search_path TO public, ag_catalog';
    RETURN QUERY EXECUTE format(
        'SELECT rid::text::uuid FROM ag_catalog.cypher(''eim_graph'', '
        || '$cy$ MATCH (m:Payee {payee_id: "''''%1$s''''"})<-[:MANAGES_OF*1..5]-(r:Payee) '
        || 'RETURN r.payee_id AS rid $cy$'
        || ') AS (rid ag_catalog.agtype)',
        manager_id::text);
END;
$fn$;
