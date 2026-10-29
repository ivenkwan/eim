package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispute submission + lifecycle API (TG4-SEL-4/5, FR-704/705): payees
 * submit disputes in the four structured categories with an optional
 * ledger/transaction reference; the backward-chaining triage pre-tag
 * derives the node category from the symptom via the plan-compiler query;
 * admins resolve with mandatory comments, and approval can trigger a
 * one-click manual adjustment linked to the ticket.
 */
@Path("/api/v1/disputes")
@Produces(MediaType.APPLICATION_JSON)
public class DisputeResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public record DisputeSubmission(String category, String notes, String ledgerId,
                                    String transactionId, String symptom) {
    }

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response submit(DisputeSubmission body,
                           @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (body == null || body.category() == null || body.category().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "category is required")).build();
        }
        List<String> allowed = List.of("SOURCE_DATA", "CREDIT_SPLIT", "WINDOW_STATE",
                "PLAN_LOGIC", "OTHER");
        if (!allowed.contains(body.category())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "category must be one of " + allowed)).build();
        }
        // backward-chaining triage: the symptom pre-tag overrides the
        // caller's guess (plan-compiler query semantics, FR-704.1)
        String preTag = triage(body.symptom(), body.category());

        UUID disputeId = UUID.randomUUID();
        String payeeId;
        try (Connection c = ds.getConnection()) {
            MeResource.setSessionScope(c, ctx);
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT payee_id::text FROM payee "
                             + "WHERE payee_id = current_setting('app.current_payee_id', true)::uuid "
                             + "AND effective_end_date IS NULL ORDER BY effective_start_date DESC "
                             + "LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "no payee profile for this identity")).build();
            }
            payeeId = rs.getString(1);
            }
        }

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO dispute_ticket (dispute_id, tenant_id, payee_id, transaction_id, "
                             + "period_key, category, notes, status) "
                             + "VALUES (?,?,?,?,?,?,?,'SUBMITTED')")) {
            ps.setObject(1, disputeId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setObject(3, UUID.fromString(payeeId));
            if (body.transactionId() == null || body.transactionId().isBlank()) {
                ps.setNull(4, java.sql.Types.OTHER);
            } else {
                ps.setObject(4, UUID.fromString(body.transactionId()));
            }
            ps.setString(5, null);
            ps.setString(6, preTag);
            ps.setString(7, body.notes());
            ps.executeUpdate();
        }

        audit.record(ctx, "DISPUTE_RESOLUTION", "dispute_ticket", disputeId.toString(),
                null,
                "{\"category\":\"" + preTag + "\",\"symptom\":"
                        + (body.symptom() == null ? "null" : "\"" + body.symptom() + "\"")
                        + ",\"ledger_id\":" + (body.ledgerId() == null ? "null"
                        : "\"" + body.ledgerId() + "\"") + "}",
                "DISPUTE_SUBMIT", null, "dispute.created", disputeId.toString(),
                "{\"dispute\":\"" + disputeId + "\",\"category\":\"" + preTag + "\"}");
        return Response.accepted(Map.of("disputeId", disputeId.toString(),
                "preTaggedCategory", preTag)).build();
    }

    @GET
    @jakarta.annotation.security.PermitAll
    public Response queue(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT d.dispute_id::text, d.payee_id::text, d.transaction_id::text, "
                             + "d.category, coalesce(d.notes, ''), d.status, d.created_at::text "
                             + "FROM dispute_ticket d WHERE d.status IN ('SUBMITTED','UNDER_REVIEW') "
                             + "ORDER BY d.created_at");
             ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("disputeId", rs.getString(1));
                row.put("payeeId", rs.getString(2));
                row.put("transactionId", rs.getString(3));
                row.put("category", rs.getString(4));
                row.put("notes", rs.getString(5));
                row.put("status", rs.getString(6));
                row.put("createdAt", rs.getString(7));
                out.add(row);
            }
            return Response.ok(out).build();
        }
    }

    @POST
    @Path("/{disputeId}/resolve")
    @jakarta.annotation.security.PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response resolve(@PathParam("disputeId") String disputeId, JsonObject body,
                            @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String resolution = required(body, "resolution"); // APPROVE | REJECT
        String comment = required(body, "comment");
        String newStatus = "APPROVE".equals(resolution) ? "APPROVED" : "REJECTED";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE dispute_ticket SET status = ?, resolution_note = ?, updated_at = now() "
                             + "WHERE dispute_id = CAST(? AS uuid) AND status IN "
                             + "('SUBMITTED','UNDER_REVIEW')")) {
            ps.setString(1, newStatus);
            ps.setString(2, comment);
            ps.setString(3, disputeId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "dispute not open")).build();
            }
        }
        audit.record(ctx, "DISPUTE_RESOLUTION", "dispute_ticket", disputeId,
                "{\"status\":\"SUBMITTED\"}",
                "{\"status\":\"" + newStatus + "\"}",
                "DISPUTE_" + resolution, comment,
                "dispute.resolved", disputeId,
                "{\"dispute\":\"" + disputeId + "\",\"status\":\"" + newStatus + "\"}");
        return Response.accepted(Map.of("disputeId", disputeId, "status", newStatus)).build();
    }

    /** Backward-chaining triage table (mirrors the DRL query semantics). */
    static String triage(String symptom, String fallback) {
        if (symptom == null) {
            return fallback == null ? "PLAN_LOGIC" : fallback;
        }
        return switch (symptom) {
            case "late_arrival", "missing_source_row", "currency_mismatch" -> "SOURCE_DATA";
            case "split_sum_off" -> "CREDIT_SPLIT";
            case "window_boundary", "reset_cadence" -> "WINDOW_STATE";
            default -> "PLAN_LOGIC";
        };
    }

    private static String required(JsonObject body, String key) {
        String v = body == null ? null : body.getString(key, null);
        if (v == null || v.isBlank()) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", key + " is required")).build());
        }
        return v;
    }

    private TenantContext ctx(ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "tenant context missing")).build());
        }
        return ctx;
    }
}
