package io.eim.coreapi.audit;

import java.util.Optional;
import java.util.Set;

/**
 * Request-scoped identity derived from validated JWT claims (or dev headers
 * when eim.auth.mode=header). Feeds RLS session variables and audit records
 * (FR-103, NFR-52).
 */
public class TenantContext {

    private String tenantId = "";
    private String payeeId = "";
    private String actor = "anonymous";
    private String actorType = "USER";
    private Set<String> roles = Set.of();
    private String correlationId = "";

    public static TenantContext of(String tenantId, String payeeId, String actor,
                                   Set<String> roles, String correlationId) {
        TenantContext ctx = new TenantContext();
        ctx.tenantId = tenantId == null ? "" : tenantId;
        ctx.payeeId = payeeId == null ? "" : payeeId;
        ctx.actor = actor == null || actor.isBlank() ? "anonymous" : actor;
        ctx.roles = roles == null ? Set.of() : Set.copyOf(roles);
        ctx.correlationId = correlationId == null ? "" : correlationId;
        return ctx;
    }

    public String tenantId() {
        return tenantId;
    }

    /** Empty string means admin scope (sees whole tenant, FR-103.2). */
    public String payeeId() {
        return payeeId;
    }

    public String actor() {
        return actor;
    }

    public String actorType() {
        return actorType;
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
