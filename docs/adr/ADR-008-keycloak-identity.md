# ADR-008: Keycloak as the embedded identity broker

- **Status:** Accepted (ratifies AD-8)
- **Date:** 2026-10-28

## Decision

Keycloak federates customer IdPs (OIDC/SAML) and issues platform JWTs with
tenant/role/payee claims; APISIX validates them at the edge. Break-glass
accounts are MFA-only and time-boxed (FR-101, playbook MI-19).

## Consequences

- Cloud-agnostic identity; SCIM provisioning; no vendor lock-in.
- Keycloak upgrades become a platform concern (tracked in TG-1 security work).
