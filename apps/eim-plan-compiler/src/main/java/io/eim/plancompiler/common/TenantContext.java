package io.eim.plancompiler.common;

import java.util.Optional;
import java.util.Set;

/**
 * Request-scoped identity for the plan-compiler service (same contract as
 * core-api/connectors): gateway-validated identity claims ride headers in
 * the dev posture, OIDC claims in staging/prod.
 */
public class TenantContext {

    private final String tenantId;
    private final String actor;
    private final Set<String> roles;
    private final String correlationId;

    public TenantContext(String tenantId, String actor, Set<String> roles, String correlationId) {
        this.tenantId = tenantId == null ? "" : tenantId;
        this.actor = actor == null || actor.isBlank() ? "anonymous" : actor;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
        this.correlationId = correlationId == null ? "" : correlationId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String actor() {
        return actor;
    }

    public Set<String> roles() {
        return roles;
    }

    public String correlationId() {
        return correlationId;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
