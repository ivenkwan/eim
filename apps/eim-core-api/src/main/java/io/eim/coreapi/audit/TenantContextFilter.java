package io.eim.coreapi.audit;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

import java.util.Set;

/**
 * Builds the TenantContext for every request (AUD-7 correlation included) and
 * exposes the correlation ID for logs and audit records (NFR-90).
 *
 * oidc mode: claims from the validated bearer token (Keycloak realm roles,
 * tenant_id / payee_id user attributes). header mode: dev headers, for the
 * pre-SSO bring-up and load tests — disabled by default in cluster values.
 */
@Provider
public class TenantContextFilter implements ContainerRequestFilter {

    public static final String CTX_PROPERTY = "eim.tenantContext";

    @Inject
    SecurityIdentity identity;

    @Inject
    org.eclipse.microprofile.config.Config config;

    @ConfigProperty(name = "eim.auth.mode", defaultValue = "oidc")
    String authMode;

    @Override
    public void filter(ContainerRequestContext request) {
        String correlationId = request.getHeaderString("X-Request-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = java.util.UUID.randomUUID().toString();
        }
        MDC.put("correlation_id", correlationId);
        request.getHeaders().putSingle("X-Request-Id", correlationId);

        TenantContext ctx;
        if ("header".equals(authMode)) {
            ctx = TenantContext.of(
                    request.getHeaderString("X-Tenant-Id"),
                    request.getHeaderString("X-Payee-Id"),
                    request.getHeaderString("X-Actor"),
                    Set.of(orEmpty(request.getHeaderString("X-Roles")).split(",")),
                    correlationId);
        } else {
            String tenant = claim(identity, "tenant_id");
            String payee = claim(identity, "payee_id");
            String actor = identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName();
            ctx = TenantContext.of(tenant, payee, actor, realmRoles(), correlationId);
        }
        request.setProperty(CTX_PROPERTY, ctx);
    }

    private Set<String> realmRoles() {
        return identity.getRoles().stream()
                .map(r -> r.toLowerCase().replace(' ', '_'))
                .collect(java.util.stream.Collectors.toSet());
    }

    private String claim(SecurityIdentity id, String name) {
        if (id.isAnonymous()) {
            return "";
        }
        Object raw = id.getAttribute(name);
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        if (raw != null) {
            return String.valueOf(raw);
        }
        return "";
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
