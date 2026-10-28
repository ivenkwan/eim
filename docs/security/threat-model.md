# TG0-OPS-3 — Threat Model & Security Checklist (initial)

Scope: TG-0 skeletons. Living document; reviewed per release and before every
external pen test (NFR-54). STRIDE applied to the container diagram.

## Assets

Commission ledger (financial record of truth), audit streams, source-system
credentials, customer identity federation, plan artifacts.

## Threats & mitigations

| # | Threat (STRIDE) | Vector | Mitigation (status) |
|---|---|---|---|
| T1 | BOLA on payee ledger (Spoofing/Elevation) | Payee A requests payee B entries | PG RLS keyed on session identity (planned TG-1); gateway JWT validation (chart, TG-1); per-endpoint BOLA tests (CI, TG-5) |
| T2 | Gateway bypass | Direct service access in-cluster | NetworkPolicy default-deny (planned TG-1); mTLS mesh (TG-1); "no public path" pen-test case (TG-5) |
| T3 | Credential leakage | Secrets in Git/images | No default credentials — charts REFUSE to render without them (verified in substitution matrix); `.env*` gitignored; external-secrets planned TG-1 |
| T4 | Unaudited mutation (Repudiation) | Dev writes direct to DB or skips audit | DB privileges deny; audit-coverage CI gate (live); outbox architecture (TG-1) |
| T5 | Spec/client drift → type confusion | Hand-edited generated types | Generated files committed; freshness gate fails CI (live) |
| T6 | Float injection into money paths | Convenience `number` usage | ESLint prohibition + negative-control fixture (live) |
| T7 | Supply chain | Malicious dep/base image | SBOM per build (images.yml), CVE gate blocking criticals, digest-pinned promotions (GitOps) |
| T8 | AGE graph access bypassing RLS | Graph catalogs lack RLS | Service-layer-only graph access (ADR-003); pen-test scenario R-7 (TG-5) |
| T9 | Keycloak dev-mode exposure | start-dev in prod posture | Dev-only value; prod overlay must pin production mode (checklist item for TG-1 review) |
| T10 | Kind dev cluster reachable from host network | Local lab exposure | kind clusters are local-only; dev credentials randomly generated per boot, never committed |

## Security requirements checklist (tracked to TG-1/TG-5)

- [ ] TLS 1.3 everywhere incl. service mesh (NFR-50, TG-1)
- [ ] AES-256 at rest verification for PG/MinIO/backups (TG-1)
- [ ] RLS policy tests executed as distinct roles in CI (TG-1)
- [ ] External-secrets operator wired; per-tenant connector keys (TG-1)
- [ ] Workload NetworkPolicies (default-deny ingress to apps except gateway) (TG-1)
- [ ] Pen test: OWASP API Top 10 + gateway bypass + AGE path (TG-5)
- [ ] SBOM + CVE gate live in CI (done — `.github/workflows/images.yml`)
- [ ] No default credentials in charts (done — enforced by `required`, verified by matrix)
