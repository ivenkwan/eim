package io.eim.plancompiler.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

import java.util.Set;
import java.util.UUID;

/**
 * Correlation-ID issuance (AUD-7) + tenant context. Header mode trusts the
 * gateway-validated identity (dev posture); oidc mode reads token claims.
 */
@Provider
public class TenantContextFilter implements ContainerRequestFilter {

    public static final String CTX_PROPERTY = "eim.tenantContext";

    @ConfigProperty(name = "eim.auth.mode", defaultValue = "header")
    String authMode;

    @Override
    public void filter(ContainerRequestContext request) {
        String correlationId = request.getHeaderString("X-Request-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlation_id", correlationId);
        request.getHeaders().putSingle("X-Request-Id", correlationId);

        TenantContext ctx = new TenantContext(
                request.getHeaderString("X-Tenant-Id"),
                request.getHeaderString("X-Actor"),
                Set.of(orEmpty(request.getHeaderString("X-Roles")).split(",")),
                correlationId);
        request.setProperty(CTX_PROPERTY, ctx);
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
