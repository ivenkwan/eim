# ADR-004: APISIX as the single edge

- **Status:** Accepted (ratifies AD-4, mandate C3)
- **Date:** 2026-10-28

## Decision

APISIX (+ Ingress Controller) is the only north-south entry: OIDC/JWT
validation, tenant rate limits, request-size/schema limits, mTLS termination,
and correlation-ID issuance feeding the audit plane. No service is reachable
except through the gateway (NFR-51).

## Consequences

- Centralized policy; routes declared as code (GitOps-mirrored into the audit
  plane, FR-613.5).
- Dev deployments run APISIX standalone (config-as-code, no etcd).
