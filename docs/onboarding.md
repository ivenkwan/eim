# Engineering Onboarding

Target: first merged change within 2 days (NFR-105).

## Day 1 — environment

```bash
# 1. Toolchains (JDK 21, Maven, shellcheck, actionlint, kustomize)
bash scripts/bootstrap-tools.sh

# 2. Node deps + generated client
pnpm install && pnpm codegen

# 3. Verify the whole TG-0 stack end to end
bash scripts/verify-tg0.sh

# 4. Full platform on a local kind cluster (~15 min first run)
bash scripts/dev-up.sh          # credentials land in .dev-secrets.env (gitignored)
bash scripts/dev-up.sh --destroy
```

Read, in order: [architecture plan](../doc/03-architecture-plan.md) →
[ADR-009](adr/ADR-009-audit-first-mutation.md) (audit-first) →
[CONTRIBUTING](../CONTRIBUTING.md) (DoD).

## Day 2 — first change (hello-world path)

1. Pick a `good-first-change` ticket (usually an endpoint or UI polish).
2. Change the OpenAPI spec FIRST if the contract moves → `pnpm codegen`.
3. Watch CI gates enforce the DoD: strict types, coverage, audit mapping.
4. Add/extend golden fixtures if your change touches numbers (NFR-100).

## Repository map

| Path | What |
|---|---|
| `packages/` | TypeScript monorepo (web-app, ui-kit, api-client, schemas, shared-domain) |
| `apps/` | Quarkus services (core-api, connectors, calc-engine, process, plan-compiler) |
| `openapi/` | OpenAPI 3.1 contracts — the source of generated types |
| `platform/helm/` | Umbrella chart + subcharts (apps + data plane) |
| `platform/argocd/` | GitOps: app-of-apps + per-env overlays |
| `scripts/` | codegen, gates, dev cluster, scaffolds (provenance) |
| `fixtures/golden/` | Golden calculation fixtures |
| `docs/` | ADRs, events/audit catalogs, security, this file |
| `doc/` | Requirements & architecture (specs) |
