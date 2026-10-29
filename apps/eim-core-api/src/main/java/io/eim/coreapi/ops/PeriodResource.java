package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Period lifecycle (TG1-SEC-3 SoD): only the finance_approver role may lock a
 * payout period (FR-102/603). The lock creates the dedicated partition if
 * needed, revokes app writes on it (NFR-44), and writes the audited trail.
 */
@Path("/api/v1/admin/periods")
@Produces(MediaType.APPLICATION_JSON)
public class PeriodResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @POST
    @Path("/{periodKey}/lock")
    @RolesAllowed("finance_approver")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response lock(@jakarta.ws.rs.PathParam("periodKey") String periodKey,
                         JsonObject body,
                         @Context ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        String reason = body == null ? null : body.getString("reason", null);

        UUID periodId;
        try (Connection c = ds.getConnection()) {
            UUID found;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payout_period_id FROM payout_period WHERE period_key = ?")) {
                ps.setString(1, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(error("period not found: " + periodKey)).build();
                    }
                    found = (UUID) rs.getObject(1);
                }
            }
            periodId = found;
            String partition;
            try (PreparedStatement ps = c.prepareStatement("SELECT eim_ops.ensure_period_partition(?, ?)")) {
                ps.setObject(1, periodId);
                ps.setString(2, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    partition = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT eim_ops.lock_period_partition(?)")) {
                ps.setObject(1, periodId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            audit.record(ctx, "PERIOD_LOCK", "payout_period", periodId.toString(),
                    "{\"status\":\"OPEN\"}", "{\"status\":\"LOCKED\",\"partition\":\"" + partition + "\"}",
                    "PERIOD_CLOSE", reason, "period.locked", periodId.toString(),
                    "{\"period\":\"" + periodKey + "\",\"status\":\"LOCKED\"}");
        } catch (SQLException e) {
            throw new WebApplicationException("period lock failed: " + e.getMessage(), 500);
        }
        JsonObject payload = Json.createObjectBuilder()
                .add("period", periodKey)
                .add("status", "LOCKED")
                .build();
        return Response.ok(payload).build();
    }

    private String error(String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }
}
