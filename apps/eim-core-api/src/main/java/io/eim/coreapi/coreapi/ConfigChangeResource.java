package io.eim.coreapi.coreapi;

import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import io.eim.coreapi.config.ConfigRegistryService;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Audited configuration-change endpoint (FR-613 / AD-9). TG-0's stub is now
 * the real registry: configuration + configuration_change + audit_log +
 * outbox commit atomically; governed entities require explicit approval.
 */
@Path("/api/v1/admin/config-change")
public class ConfigChangeResource {

    public record ConfigChangeRequest(String entity, String payload, String reason) {
    }

    public record ConfigChangeAccepted(boolean accepted, String configurationId,
                                       int version, String status) {
    }

    public record ErrorResponse(String error) {
    }

    @Inject
    ConfigRegistryService registry;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(ConfigChangeRequest request,
                           @Context ContainerRequestContext req) {
        if (request == null || blank(request.entity()) || blank(request.reason())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("entity and reason are required"))
                    .build();
        }
        TenantContext ctx = (TenantContext) req.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null) {
            throw new WebApplicationException("tenant context missing", 500);
        }
        ConfigRegistryService.ConfigVersion v =
                registry.submit(ctx, request.entity(),
                        request.payload() == null ? "{}" : request.payload(),
                        request.reason());
        return Response.status(Response.Status.ACCEPTED)
                .entity(new ConfigChangeAccepted(true, v.id().toString(), v.version(), v.status()))
                .build();
    }

    @POST
    @Path("/{configurationId}/approve")
    @Produces(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("configurationId") String configurationId,
                            String body,
                            @Context ContainerRequestContext req) {
        TenantContext ctx = (TenantContext) req.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || !ctx.hasRole("finance_approver") && !ctx.hasRole("system_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("approval requires finance_approver or system_admin"))
                    .build();
        }
        String note = body == null || body.isBlank() ? "approved" : body;
        return registry.approve(ctx, UUID.fromString(configurationId), note)
                .<Response>map(v -> Response.ok(new ConfigChangeAccepted(
                        true, v.id().toString(), v.version(), v.status())).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("unknown configuration")).build());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
