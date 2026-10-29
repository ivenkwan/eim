package io.eim.process.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

import java.security.Principal;
import java.util.UUID;

/**
 * Correlation-ID issuance (AUD-7) + dev identity: Quarkus security maps the
 * authenticated principal (OIDC in staging/prod); the dev gateway-trust
 * posture reads the same claims from headers.
 */
@Provider
public class TenantContextFilter implements ContainerRequestFilter {

    public static final String CTX_PROPERTY = "eim.tenantContext";

    @Override
    public void filter(ContainerRequestContext request) {
        String correlationId = request.getHeaderString("X-Request-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlation_id", correlationId);
        request.getHeaders().putSingle("X-Request-Id", correlationId);

        java.security.Principal principal = request.getSecurityContext().getUserPrincipal();
        String actor = request.getHeaderString("X-Actor");
        if ((actor == null || actor.isBlank()) && principal != null) {
            actor = principal.getName();
        }
        String correlation = String.valueOf(MDC.get("correlation_id"));
        request.setProperty(CTX_PROPERTY, new TenantContext(
                request.getHeaderString("X-Tenant-Id"),
                actor == null ? "anonymous" : actor,
                correlation == null ? "" : correlation));
    }
}
