package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispute SLA timers + escalations (TG7-GOV-2, FR-705.2): each category has
 * a resolution SLA (hours); `/sla` reports open disputes with their due
 * times and breach flags, `/escalate` escalates a breached dispute to the
 * category's escalation role and audits the transition. The nightly ops
 * sweep calls the same query.
 */
@Path("/api/v1/disputes")
@Produces(MediaType.APPLICATION_JSON)
public class DisputeSlaResource {

    /** SLA hours per category (FR-705.2): plan-logic disputes are urgent. */
    private static final Map<String, Integer> SLA_HOURS = Map.of(
            "SOURCE_DATA", 72,
            "CREDIT_SPLIT", 48,
            "WINDOW_STATE", 96,
            "PLAN_LOGIC", 24,
            "OTHER", 120);

    private static final Map<String, String> ESCALATION_ROLE = Map.of(
            "SOURCE_DATA", "comp_admin",
            "CREDIT_SPLIT", "comp_admin",
            "WINDOW_STATE", "finance_approver",
            "PLAN_LOGIC", "finance_approver",
            "OTHER", "comp_admin");

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public static int slaHoursFor(String category) {
        return SLA_HOURS.getOrDefault(category, SLA_HOURS.get("OTHER"));
    }

    public static String escalationRoleFor(String category) {
        return ESCALATION_ROLE.getOrDefault(category, "comp_admin");
    }

    @GET
    @Path("/sla")
    @PermitAll
    public Response sla(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT dispute_id::text, category, status, created_at, "
                             + "coalesce(escalated_to,'') "
                             + "FROM dispute_ticket WHERE tenant_id = CAST(? AS uuid) "
                             + "AND status IN ('SUBMITTED','IN_REVIEW') ORDER BY created_at")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                Instant now = Instant.now();
                while (rs.next()) {
                    Instant created = rs.getTimestamp(4).toInstant();
                    int hours = slaHoursFor(rs.getString(2));
                    Instant due = created.plus(hours, ChronoUnit.HOURS);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("disputeId", rs.getString(1));
                    row.put("category", rs.getString(2));
                    row.put("status", rs.getString(3));
                    row.put("createdAt", rs.getTimestamp(4).toInstant().toString());
                    row.put("slaHours", hours);
                    row.put("dueAt", due.toString());
                    row.put("breached", now.isAfter(due));
                    row.put("escalatedTo", rs.getString(5));
                    rows.add(row);
                }
            }
        }
        long breached = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("breached"))).count();
        return Response.ok(Map.of("open", rows.size(), "breached", breached, "rows", rows)).build();
    }

    @POST
    @Path("/{disputeId}/escalate")
    @PermitAll
    @Transactional
    public Response escalate(@jakarta.ws.rs.PathParam("disputeId") String disputeId,
                             @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        try (Connection c = ds.getConnection()) {
            String category;
            String status;
            Instant created;
            String alreadyEscalated;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT category, status, created_at, coalesce(escalated_to,'') "
                            + "FROM dispute_ticket WHERE dispute_id = CAST(? AS uuid) "
                            + "AND tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, disputeId);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown dispute")).build();
                    }
                    category = rs.getString(1);
                    status = rs.getString(2);
                    created = rs.getTimestamp(3).toInstant();
                    alreadyEscalated = rs.getString(4);
                }
            }
            if (!"SUBMITTED".equals(status) && !"IN_REVIEW".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "dispute is " + status + " — nothing to escalate"))
                        .build();
            }
            Instant due = created.plus(slaHoursFor(category), ChronoUnit.HOURS);
            boolean breached = Instant.now().isAfter(due);
            String role = escalationRoleFor(category);
            if (!alreadyEscalated.isBlank()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "already escalated to " + alreadyEscalated)).build();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE dispute_ticket SET escalated_to = ?, status = 'IN_REVIEW' "
                            + "WHERE dispute_id = CAST(? AS uuid)")) {
                ps.setString(1, role);
                ps.setString(2, disputeId);
                ps.executeUpdate();
            }
            audit.record(ctx, "DISPUTE_RESOLUTION", "dispute_ticket", disputeId,
                    "{\"status\":\"" + status + "\"}",
                    "{\"status\":\"IN_REVIEW\",\"escalated_to\":\"" + role + "\"}",
                    breached ? "SLA_ESCALATION_BREACH" : "SLA_ESCALATION",
                    "SLA due " + due + (breached ? " (breached)" : ""),
                    "eim.dispute.updated", disputeId,
                    "{\"dispute\":\"" + disputeId + "\",\"escalatedTo\":\"" + role
                            + "\",\"breached\":" + breached + "}");
            return Response.ok(Map.of("disputeId", disputeId, "escalatedTo", role,
                    "breached", breached)).build();
        } catch (SQLException e) {
            throw new WebApplicationException("escalation failed: " + e.getMessage(), 500);
        }
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
