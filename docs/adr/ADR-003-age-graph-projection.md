# ADR-003: AGE graph as a maintained projection of relational truth

- **Status:** Accepted (ratifies AD-3)
- **Date:** 2026-10-28

## Context

Effective-dated reporting trees, credit-split graphs, and plan-dependency
DAGs are graph-shaped; recursive CTEs do not scale readably (NFR-24).

## Decision

Apache AGE models `MANAGES_OF` / `CREDITED_TO` / `DEPENDS_ON` in a dedicated
database of the same cluster. Relational tables remain the source of truth;
the graph is a projection with a reconciliation job and drift alerts. RLS does
not apply inside AGE — graph access is service-layer only (documented
exception to NFR-52).

## Consequences

- Rollups, wavefront layering, and cycle detection via openCypher.
- Rebuildable projection contains the R-2 backup/restore risk.
