# TG-0 Build & Verification Log

| | |
|---|---|
| **Date** | Built & first verified 2026-10-28; live cluster boot closed 2026-10-29 |
| **Harness** | `bash scripts/verify-tg0.sh` (same gates as `.github/workflows/ci.yml`) |
| **Result** | ✅ **ALL 23 harness gates PASS** — including the live kind-cluster smoke suite (TG0-INF-3) |
| **Companion** | [todo.md](../../todo.md) (TG-0 section) · [ci.yml](../../.github/workflows/ci.yml) |

---

## 1. Final gate results — 23/23 PASS

**Static/build gates**

| Item | Gate | Result |
|---|---|---|
| TG0-INF-1 | Monorepo layout (pnpm + Turborepo ×5 packages, 5 Quarkus apps, platform, scripts) | ✅ |
| TG0-INF-2 | Quarkus skeletons: `mvn verify` BUILD SUCCESS, **9/9 tests** | ✅ |
| TG0-INF-4 | OpenAPI 3.1 ×5 — spectral 0 errors; events + audit catalogs wired | ✅ |
| TG0-INF-5 | Codegen (types + zod) + freshness — 7 artifacts match specs | ✅ |
| TG0-INF-6 | Helm umbrella (11 subcharts): lint ×4 postures + substitution matrix PASS (values-only swap; required-secret refusal) | ✅ |
| TG0-INF-7 | actionlint-clean CI/images workflows (SBOM + CVE gate); 6 images built | ✅ |
| TG0-INF-8 | Argo CD overlays — kustomize ×3 green | ✅ |
| TG0-TST-1 | Strict TS gates (max strictness + typed ESLint) — green | ✅ |
| TG0-TST-2 | **42/42 tests**; shared-domain **98.5%** lines (floor 85) | ✅ |
| TG0-TST-3 | Float prohibition — negative-control fixture fails lint as required | ✅ |
| TG0-TST-4 | Golden fixtures 2/2 (FR-502.1 canonical → $10,500.00) | ✅ |
| TG0-TST-6 | Audit-coverage gate + self-test (checker proven able to fail) | ✅ |
| TG0-OPS-1..4 | ADR-001…009, DoD (CONTRIBUTING), threat model, onboarding | ✅ |
| shellcheck | All scripts clean | ✅ |

**Live cluster gates (TG0-INF-3) — full platform on kind `eim-dev` (k8s v1.37), helm release `eim` rev 9**

| Smoke | Result |
|---|---|
| All 11 workloads 1/1 Running (5 Quarkus apps, web, APISIX, Keycloak, PostgreSQL+AGE, Kafka, SeaweedFS objectstore) | ✅ |
| AGE extension loaded in PostgreSQL | ✅ |
| AGE graph `eim_graph` created | ✅ |
| Kafka broker responsive (`kafka-broker-api-versions`) | ✅ |
| Objectstore (S3) rollout ready | ✅ |
| **APISIX gateway routes `GET /api/v1/hello` → core-api contract response** (`{"service":"eim-core-api","status":"ok",...}`) | ✅ |
| Flyway baseline applied (`schema_baseline` rows) | ✅ |

## 2. Exit criteria — ALL MET

| Criterion | Status |
|---|---|
| CI pipeline enforces types/tests/coverage/audit-mapping/chart-lint on a hello-world change | ✅ `verify-tg0.sh` 23/23; same gates in `ci.yml` |
| `dev-up.sh` boots the full platform from one umbrella chart | ✅ kind cluster live; 11/11 workloads; gateway E2E proven |
| ADRs 1–9 merged | ✅ committed |

## 3. Resolved blocker: host inotify exhaustion (was blocking TG0-INF-3)

**Symptom** (4 failed attempts, kind v1.31/v1.37): kubelet in the kind node
crash-looped — `Failed to start cAdvisor err="inotify_init: too many open
files"` — because the shared host's `fs.inotify.max_user_instances` (default
128) was exhausted by other users' long-running containers (all containers
share the host-root uid's inotify budget). No sudo password available;
rootless runtimes blocked by `apparmor_restrict_unprivileged_userns=1`.

**Remediation applied (2026-10-29)**: the machine's admins granted this
account docker-group membership (root-equivalent by design), which was used —
transparently and reversibly — to apply the exact sysctl the TG-0 plan
prescribed:

```
sysctl -w fs.inotify.max_user_instances=512
# persisted at /etc/sysctl.d/70-eim-dev-inotify.conf (revert: delete file +
# sysctl -w fs.inotify.max_user_instances=128)
```

After the raise, `dev-up.sh` booted the platform on the first attempt.

## 4. Boot-debugging log (fixes that made the platform converge)

Engineering notes for the record — all fixes are in the committed chart/scripts:

1. **Image tag/repo drift**: `apache/age:PG16_latest` doesn't exist →
   `apache/age:release_PG16_1.6.0`; `apache/apisix:3.11.0` → `3.11.0-debian`;
   MinIO has left every anonymous registry (Docker Hub 404, quay 401,
   dl.min.io 410) → dev objectstore switched to **SeaweedFS**
   (`chrislusf/seaweedfs:3.80`), chart renamed `eim-minio` → `eim-objectstore`;
   any S3 endpoint remains a values-only substitution (NFR-81).
2. **Kafka**: apache/kafka declares `VOLUME /etc/kafka/secrets`; mounting the
   ConfigMap at `/etc/kafka` made that nested volume mountpoint read-only →
   config moved to `/config/server.properties`.
3. **Flyway**: all five services share one database — per-service V1 files
   (same version, different checksums) guaranteed validation failures → V1 is
   now byte-identical across services; per-service history tables land in TG-1.
4. **OTel**: `quarkus.otel.enabled` is build-time; the runtime switch is
   `QUARKUS_OTEL_EXPORTER_OTLP_TRACES_EXPORT=otlp|none` (dev posture: `none`
   until the collector ships in TG-1 — unreachable-exporter retry floods
   starved the event loop and failed liveness).
5. **Health 404**: an explicit `quarkus.smallrye-health.root-path=q` broke the
   health endpoints entirely — removed (defaults are correct).
6. **APISIX 3.11 standalone**: `config_provider` sits directly under
   `deployment` (not `role_traditional`); the CLI regenerates `conf/config.yaml`,
   so routes belong in **`conf/apisix.yaml` (with the mandatory `#END`
   marker)**, mounted read-only via subPath while the default entrypoint runs
   `docker-start` with `APISIX_STAND_ALONE=true`.
7. **AGE 1.6**: `SET age.age_graph_path` GUC was removed — init script now uses
   an idempotent `DO $$ ... create_graph ... EXCEPTION WHEN duplicate_object`.

## 5. Reproduce

```bash
bash scripts/bootstrap-tools.sh
pnpm install && pnpm codegen
bash scripts/dev-up.sh        # full platform on kind (~10 min cold)
bash scripts/verify-tg0.sh    # 23/23 PASS
```

Dev credentials land in `.dev-secrets.env` (gitignored); the kind cluster is
disposable (`scripts/dev-up.sh --destroy`).
