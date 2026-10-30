# Contributing & Definition of Done

Ways of working for the EIM platform. The DoD exists so that every merge is
born compliant — nobody has to "add audit later".

## Definition of Done (per change)

A change is done when ALL of:

1. **Tests** — unit + (if numeric) golden fixtures pass; coverage floors hold
   (≥85% lines on calculation/ledger/amortization modules, ≥70% elsewhere — NFR-102).
2. **Contract updated** — OpenAPI spec changed together with the code;
   `pnpm codegen` re-run and the generated diff reviewed; `pnpm codegen:freshness` green.
3. **Audit mapping declared** — every new endpoint/event type carries
   `x-audit` (mutating ops) or a mapping in `docs/audit-mappings.yaml` (events);
   `pnpm audit:mappings --self-test` green. No mapping, no merge (AD-9/NFR-64).
4. **Types strict** — `pnpm turbo lint typecheck` green (maximal strictness, NFR-103);
   no floats in calculation paths (`fixtures/float-violation.ts` negative control).
5. **Charts updated** — if deployment changes, helm lint + substitution matrix green (NFR-81).
6. **Runbook entry** — any new alert links a runbook (NFR-93).
7. **ADR** — decisions that change architecture get an ADR (`docs/adr/`).

## Local workflow

```bash
pnpm install
pnpm codegen                 # regenerate types + zod from openapi/
pnpm turbo lint typecheck test
pnpm golden                  # golden calculation fixtures
pnpm audit:mappings --self-test
mvn -f apps/pom.xml verify    # backend: all five Quarkus services
```

Full TG-0 verification: `bash scripts/verify-tg0.sh` (see [build log](docs/build-log/TG-0.md)).

## Conventional commits

`<area>: <summary>` — areas: `frn`, `web`, `calc`, `process`, `platform`, `docs`, `audit`.

## Code review expectations

Reviewers verify the DoD checklist, not just correctness. In particular:
SoD-sensitive paths (period lock, approvals) and any change touching
`Money`/rounding requires a second reviewer from the calculation workstream.
