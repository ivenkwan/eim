# TG-0 Build & Verification Log

| | |
|---|---|
| **Date** | 2026-10-28 (completed 2026-10-29 local) |
| **Harness** | `bash scripts/verify-tg0.sh` (same gates as `.github/workflows/ci.yml`) |
| **Result** | **16 / 17 harness gates PASS. 1 gate (live cluster boot smoke) BLOCKED by a host-level resource limit — not a code defect. Details in §3.** |
| **Companion** | [todo.md](../../todo.md) (TG-0 section) · [ci.yml](../../.github/workflows/ci.yml) |

---

## 1. Gate results

| Item | Gate | Result | Evidence |
|---|---|---|---|
| TG0-INF-1 | Monorepo layout: `packages/*` (pnpm + Turborepo), `apps/*`, `platform/*`, `scripts/*` | ✅ PASS | `pnpm turbo lint typecheck test` — 15/15 tasks green |
| TG0-INF-2 | 5 Quarkus skeletons (REST + SmallRye Health + OTel + Flyway/JDBC-PostgreSQL) | ✅ PASS | `mvn -f apps/pom.xml verify` → **BUILD SUCCESS**, 9/9 tests (H2/pg-mode + ephemeral test ports) |
| TG0-INF-3 | kind cluster boot of full platform via `scripts/dev-up.sh` | ⛔ **BLOCKED (host)** | Script shellcheck-clean + `--dry-run` verified; 6 container images **built locally**; live boot blocked by host `fs.inotify` exhaustion — §3 |
| TG0-INF-4 | OpenAPI 3.1 specs ×5 + spectral lint | ✅ PASS | 0 errors, 24 hygiene warnings; events + audit catalogs in place |
| TG0-INF-5 | Codegen pipeline: types (openapi-typescript) + zod; freshness gate | ✅ PASS | `pnpm codegen:freshness` — 7 generated artifacts match specs |
| TG0-INF-6 | Helm umbrella (11 subcharts) + substitution matrix | ✅ PASS | `helm lint` ×4 postures green; matrix PASS incl. embedded/external swap + **required-password refusal** (NFR-53) |
| TG0-INF-7 | Image pipeline (Dockerfiles runtime+build ×6, SBOM + CVE workflow) | ✅ PASS | `actionlint` green; 6 images built locally (`eim-*:0.1.0-dev`); syft/grype execute in CI (`.github/workflows/images.yml`) |
| TG0-INF-8 | Argo CD app-of-apps + dev/staging/prod overlays | ✅ PASS | `kustomize build` ×3 green; secrets never in Git (values placeholders + `required` guards) |
| TG0-TST-1 | Strict TS gates (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`) + typed ESLint | ✅ PASS | `tsc --noEmit` + `eslint` green across all packages |
| TG0-TST-2 | Coverage floors (≥85% calc-class, ≥70% rest) | ✅ PASS | shared-domain **98.5% lines** (>85); ui-kit 100%; others enforce ≥70; **42/42 frontend tests** |
| TG0-TST-3 | Float prohibition + negative control | ✅ PASS | Fixture lints to 3 errors/exit 1 under `--no-ignore` (rule proven to fire, NFR-01) |
| TG0-TST-4 | Golden harness + fixtures | ✅ PASS | 2/2 fixtures incl. **FR-502.1 canonical** ($90k+$70k → 3 slices → $10,500.00 HALF_EVEN) |
| TG0-TST-5 | Consumer contract tests | ✅ PASS | schemas 7/7 (strict zod), api-client 4/4 (boundary validation incl. reject cases) |
| TG0-TST-6 | Audit-coverage gate + self-test (AD-9/NFR-64) | ✅ PASS | Checker validates all 5 specs' mutating ops (`x-audit`) + 12 event topics; **self-test proves the checker detects violations** |
| TG0-OPS-1 | ADRs ratified | ✅ PASS | `docs/adr/ADR-001…009` + template |
| TG0-OPS-2 | Definition of Done | ✅ PASS | `CONTRIBUTING.md` — DoD embeds the audit-mapping requirement |
| TG0-OPS-3 | Threat model + security checklist | ✅ PASS | `docs/security/threat-model.md` — 10 threats, checklist tracked to TG-1/TG-5 |
| TG0-OPS-4 | Onboarding docs | ✅ PASS | `docs/onboarding.md` + per-service READMEs ×5 |

### Test totals

- Frontend: **42/42** (shared-domain 25, schemas 7, api-client 4, ui-kit 4, web-app 2)
- Backend: **9/9** across five Quarkus services
- Golden fixtures: **2/2**

## 2. Exit-criteria assessment (TG-0)

| Criterion | Status |
|---|---|
| CI pipeline enforces types/tests/coverage/audit-mapping/chart-lint on a hello-world change | ✅ Met — all gates ran green locally on the hello endpoints/pages; the same gates are encoded in `ci.yml` (actionlint-validated) |
| `dev-up.sh` boots the full platform from one umbrella chart | ⛔ Pending host fix — script/chart/images ready; live boot blocked (§3). Chart-verified via lint + template across 4 postures; `--dry-run` flow verified |
| ADRs 1–9 merged | ✅ Met — committed with this change |

## 3. Blocker: host inotify exhaustion (TG0-INF-3)

**Observed** (three attempts: kind v1.31.2 ×2, v1.37.0 ×1): kubelet inside the
kind node crash-loops — `journalctl -u kubelet` inside the retained node:

```
E ... manager.go:340] Registration of the raw container factory failed: inotify_init: too many open files
E ... kubelet.go:1833] "Failed to start cAdvisor" err="inotify_init: too many open files"
systemd[1]: kubelet.service: Main process exited, code=exited, status=1/FAILURE
```

cAdvisor cannot initialize → kubelet never runs the apiserver static pod →
kubeadm `wait-control-plane` times out (`connect: connection refused` on :6443).

**Diagnosis**: shared host (43+ days uptime, multiple users' long-running
containers incl. another team's kind cluster). `fs.inotify.max_user_instances`
is at the **default 128**; the per-uid budget for container processes is
exhausted. My own processes hold only 9 instances. No passwordless sudo is
available to raise the sysctl. A pre-existing cluster from another user
(started when instances were free) still runs — confirming capacity, not
compatibility, is the issue.

**Remediation (host admin)** — either:

```bash
sudo sysctl -w fs.inotify.max_user_instances=512   # and persist in /etc/sysctl.d/
# or free instances by stopping stale containers
```

Then: `bash scripts/dev-up.sh` boots the platform and
`bash scripts/verify-tg0.sh` re-runs the full harness including the cluster
smoke tests (AGE extension + graph, Kafka broker, APISIX→core-api route,
Flyway baseline, MinIO health).

**What IS verified without the cluster**: dev-up.sh (shellcheck + dry-run),
build-apps.sh (6 images built: eim-web + 5 services), umbrella chart rendering
in 4 postures, required-secret refusal, deployment YAML structure.

## 4. Reproduce

```bash
bash scripts/bootstrap-tools.sh   # JDK/Maven/shellcheck/actionlint/kustomize if missing
pnpm install && pnpm codegen
bash scripts/verify-tg0.sh        # full harness (16 green without cluster)
bash scripts/dev-up.sh            # full platform (needs host inotify capacity)
```

## 5. Notable engineering decisions during the build

- openapi-typescript v7 returns an AST — wrapped with `astToString` (scripts/lib/codegen.mjs).
- Quarkus tests: H2 in PostgreSQL mode + `TIMESTAMP WITH TIME ZONE` (portable spelling) + ephemeral `%test.quarkus.http.test-port=0` (port 8081 on this host is held by another user's process).
- The audit-coverage checker ships with `--self-test`: it proves it can FAIL on synthetic violations before trusting its PASS (NFR-64).
- Generated artifacts are committed: freshness gate makes spec↔client drift visible in PR diffs.
