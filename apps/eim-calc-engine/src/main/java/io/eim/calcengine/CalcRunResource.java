package io.eim.calcengine;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/** Skeleton run-request endpoint; wavefront execution lands in TG-3 (FR-5xx). */
@Path("/api/v1/calc/runs")
public class CalcRunResource {

    public record CalcRunRequest(String tenantId, String payoutPeriodId, String planVersionHash) {
    }

    public record CalcRunAccepted(boolean accepted, String runId) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestRun(CalcRunRequest request) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.payoutPeriodId())
                || isBlank(request.planVersionHash())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new CalcRunAccepted(false, "tenantId, payoutPeriodId and planVersionHash are required"))
                    .build();
        }
        return Response.status(Response.Status.ACCEPTED)
                .entity(new CalcRunAccepted(true, UUID.randomUUID().toString()))
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
