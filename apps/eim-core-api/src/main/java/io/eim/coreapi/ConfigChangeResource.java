package io.eim.coreapi;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Skeleton for the audited configuration registry (FR-613 / AD-9).
 * The full transactional-outbox wiring lands in TG-1; the endpoint and its
 * audit mapping (openapi/core-api.yaml x-audit) are the TG-0 contract.
 */
@Path("/api/v1/admin/config-change")
public class ConfigChangeResource {

    public record ConfigChangeRequest(String entity, String payload, String reason) {
    }

    public record ConfigChangeAccepted(boolean accepted, String requestId) {
    }

    public record ErrorResponse(String error) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(ConfigChangeRequest request) {
        if (request == null || isBlank(request.entity()) || isBlank(request.reason())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("entity and reason are required"))
                    .build();
        }
        return Response.status(Response.Status.ACCEPTED)
                .entity(new ConfigChangeAccepted(true, UUID.randomUUID().toString()))
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
