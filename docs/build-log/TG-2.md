# TG-2 Build & Verification Log

| | |
|---|---|
| **Date** | Built & verified 2026-10-29 |
| **Harness** | `bash scripts/verify-tg2.sh` (23 gates: unit + static + live cluster) |
| **Result** | ✅ **ALL 23 GATES GREEN** — connectors unit suite 10/10, frontend regression green, live E2E ingest/hierarchy probe through the gateway |
| **Companion** | [todo.md](../../todo.md) (TG-2 section) · [TG-1 build log](TG-1.md) |

---

## 1. Gate results — 23/23 PASS

**Unit / static gates**

| Item | Gate | Result |
|---|---|---|
| DAT-x | Migration policy lint (naming, duplicates, destructive guard — V8 additive + `V9__drop_…` contract file, immutability) | ✅ |
| CONTRACT | OpenAPI freshness (client + zod regenerated from 5 specs incl. the expanded connectors API) | ✅ |
| CONTRACT | OpenAPI spectral lint | ✅ |
| AUD-x | Audit-coverage gate + self-test (all mutating ops carry `x-audit`; every events.yaml topic mapped — `eim.hierarchy.changed` added) | ✅ |
| OPS-x | Frontend regression (strict TS lint/typecheck/test) | ✅ |
| ING-x | **connectors unit suite 10/10** — CSV batch (quarantine, late-arriving→true-up, watermark persistence), Salesforce + HubSpot via WireMock, JDBC query connector (+ non-SELECT rejection), hierarchy (SCD-2 as-of rollups, wavefronts, 5,000-payee benchmark) | ✅ |
| INF-x | Helm lint ×4 postures; substitution matrix | ✅ |

**Live cluster gates (kind dev platform)**

| Gate | Evidence | Result |
|---|---|---|
| All workloads ready | rollout status incl. **eim-connectors** + re-keyed core-api (flyway v9) | ✅ |
| SEC gateway 401 | APISIX `openid-connect` on `/api/v1/ingestion/*` + `/api/v1/org/*` rejects tokenless POSTs | ✅ |
| **E2E probe** (`scripts/probe-tg2.sh`) | Keycloak client-credentials token → **CSV batch through the gateway**: 2 ingested / 1 quarantined / 1 late-arriving → **payee create under Sarah** → **SCD-2 move to Alex effective 2026-05-15** → **as-of rollups** (May 1 → Sarah, June 1 → Alex, rep gone from Sarah) → **wavefronts layered** | ✅ |
| ING-3 | accepted rows in `raw_transaction` | ✅ |
| CR-1 | `credit_allocation` attached to Sarah for the probe deal (email resolution) | ✅ |
| ING-4 | bad row in `ingestion_record` QUARANTINED with per-field reason + raw payload | ✅ |
| AUD | `CONNECTOR_RUN` audit row (jsonb after_state) for the probe batch | ✅ |
| CR-4 | `trueup.required` outbox row for the late-arriving January row (LOCKED period) | ✅ |
| ING-6 | watermark persisted in `connector_state` (`TG2-LIVE-3`) | ✅ |
| AUD | **`eim.sync.completed` round-trip**: outbox → Kafka → core-api auditor (3 new topic channels) → `event_audit_log` | ✅ |
| HIE-2 | hierarchy edits audited (CONFIG_CHANGE ×2: create + move) | ✅ |
| HIE-x | `reconcile_graph()` = 0 drift (incl. probe payee + SCD-2 edges) | ✅ |
| AUD | seals chain verifies | ✅ |

## 2. What was built (per todo.md items)

- **Connector runtime** (`eim-connectors`): `ingestion_batch` run history (RUNNING/COMPLETED, total/accepted/quarantined, watermark_after, correlation_id), `connector_state` watermark upserts, `ingestion_record` per-row quarantine with errors + raw payload JSONB (`V2__ingestion.sql`, own flyway history `flyway_connectors` per ADR-010).
- **Four connectors**: CSV upload (quote-aware parser, default mapping), **Salesforce** (OAuth password grant → SOQL → WireMock-proven), **HubSpot** (CRM v3 private-app token, deals, epoch-millis date normalization, WireMock-proven), **JDBC query** (read-only SELECT guard — the Snowflake posture). All flow through one `IngestionService` (credit attachment by payee email, late-arriving detection vs LOCKED periods → `trueup.required` outbox events, CONNECTOR_RUN audit + `sync.completed` outbox per run).
- **Field mapping** (`FieldMappingService`): mapping registry canonical→source with **canonical-name fallback**, ISO-date / decimal ≤4-scale / ISO-4217 / percent coercion, per-field error lists, unmapped fields → `custom_attributes` JSONB (no migration).
- **Effective-dated hierarchy**: `POST /api/v1/org/payee`, `PUT /org/payee/{id}/manager` (SCD-2 close+insert), `GET /org/rollup/{id}?asOf=`, `GET /org/wavefronts` — all audited (`CONFIG_CHANGE` + `hierarchy.changed` outbox), cycle-rejecting (`hierarchy_has_cycle`, tested false-positive-free).
- **SCD-2 schema** (`V8__tg2_hierarchy_ops.sql` + `V9__drop_payee_person_constraints.sql`): payee PK → `(payee_id, effective_start_date)`, version-grain employee-number uniqueness, person-keyed FKs dropped (documented), `payee_person_idx`. Graph trigger rewrite: manager changes **close the old MANAGES_OF edge** (`close_manages_of`) and project `from_date`/`to_date` per version.
- **Rollup engine**: `rollup_scope` (open versions) + `rollup_scope_asof` (distinct active edges at D) as SQL-recursive CTEs; `wavefront_levels` topological layering (0 = leaf); benchmark **< 2 s @ 5,000 payees** (warmed, in-suite).
- **Event auditor expansion** (core-api): incoming channels for `eim.sync.completed`, `eim.trueup.required`, `eim.hierarchy.changed` (+ DLQs) feeding `event_audit_log`.
- **Contracts**: `openapi/connectors.yaml` 0.2.0 with x-audit on all 8 mutating ops; `eim.hierarchy.changed` in events/audit-mappings; types regenerated.
- **Gateway**: APISIX routes `/api/v1/ingestion/*` + `/api/v1/org/*` → `eim-connectors:8081` with the same OIDC edge posture as core-api.

## 3. Honest deviations & findings (build-log record)

1. **Rollup hot path reads the relational projection, not AGE** (design decision): the AGE-native edge dump costs ~0.5 ms/edge in agtype→uuid conversion — 2.5–2.7 s @ 5,000 payees, over the NFR-24 budget. Rollups recurse over the payee version table (the AD-3 source of truth); the AGE graph remains the **validated projection** — `reconcile_graph()` drift=0 is a live gate in every TG-2 run, so the relational tree *is* the graph tree. Record for arch §9.3 review.
2. **SCD-2 re-keying drops person-keyed FKs** (`payee_manager_payee_id_fkey` + 4 child FKs): under versioned rows `payee_id` is not unique, so FK enforcement is impossible at person grain; referential integrity is enforced by the ingestion/hierarchy paths and the graph reconciler. Classic SCD-2 trade-off — flagged for the TG-3 review.
3. **TG-1 `rollup_scope` latent bug found & fixed**: the shipped cypher path converted the quoted property wrapper straight to uuid (invalid input) — no TG-1 gate exercised it; TG-2's tests and probe do.
4. **Scheduled jobs / per-tenant quotas / XLSX** (ING-1/ING-5 subsets): on-demand runs + full history shipped; cron scheduling and quota enforcement ride the TG-4 scheduler work; XLSX parsed server-side later (CSV now). Real-org OAuth (SFDC sandbox / HubSpot portal / Snowflake key-pair) is a manual-playbook step (04-manual-intervention-playbook.md) — WireMock proves the contracts.
5. **ING-8 raw snapshots to the object store**: batch lineage (`ingestion_batch_id`) + full raw payloads retained in `raw_transaction.raw_payload`; MinIO offload deferred to TG-6 backfill tooling where volume demands it.
6. **ING-10 ingestion-run BPMN**: the event-driven queue (`eim.trueup.required` → Kafka) shipped; the Kogito orchestration process lands with the process-engine tollgate (TG-3+).
7. **HIE-1 credit splits**: single-payee attachment with percentage validation shipped; multi-payee overlays ≤/=/> 100 % land with the calc-engine split semantics (TG-3).
8. **HIE-7 backfill tooling**: the CSV batch path is the vehicle; the packaged design-partner backfill ships in TG-6 (parallel-run tollgate).
9. **Flyway dev-stage re-recording**: V8 was edited during the tollgate (additive-only split + relational rollups); the dev flyway row was reset twice so the idempotent file replayed cleanly (`now at version v9`). Committed files are final; immutability lint passes vs base.
10. **Gateway-trust auth on dev** (unchanged from TG-1): OIDC bearer validated at the edge; identity claims ride headers to the connectors service.

## 4. Exit criteria (TG-2)

| Criterion | Status |
|---|---|
| Two consecutive clean automated syncs (SFDC + CSV) into the canonical model with quarantine reports | ✅ WireMock-SFDC + CSV suites green across repeated runs; live CSV probe quarantines 1/3 with reason; run history + counts persisted |
| As-of hierarchy queries verified against fixture orgs | ✅ unit (May 14/15 split X→Y) + live probe (Sarah/Alex as-of dates) |
| Every sync batch and hierarchy edit carries audit + event records | ✅ CONNECTOR_RUN/CONFIG_CHANGE in `audit_log`; `sync.completed`/`trueup.required`/`hierarchy.changed` round-trip into `event_audit_log` |
| Reconciliation shows zero gaps | ✅ `reconcile_graph()` 0 drift; seals chain verifies |

## 5. Reproduce

```bash
scripts/dev-up.sh                 # kind + helm platform (rev 20+)
bash scripts/verify-tg2.sh        # 23 gates: unit, static, live cluster
```
