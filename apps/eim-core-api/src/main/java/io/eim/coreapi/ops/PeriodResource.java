package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // PermitAll + explicit ctx check (not @RolesAllowed): under gateway
    // header-trust the security identity is anonymous, roles ride the
    // merged TenantContext — the same parity pattern as every other
    // core-api mutation (found by the TG-6 partner drill re-lock step).
    @jakarta.annotation.security.PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response lock(@jakarta.ws.rs.PathParam("periodKey") String periodKey,
                         JsonObject body,
                         @Context ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("finance_approver required to lock a period (FR-603)")).build();
        }
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", periodKey);
        payload.put("status", "LOCKED");
        return Response.ok(payload).build();
    }

    /**
     * MI-18 break-glass reopen (TG6-ONB-8): a locked period may only be
     * reopened under DUAL approval — one finance_approver AND one
     * system_admin, two distinct people, both acting on the same request
     * with a documented impact memo. Execution re-grants app writes on the
     * partition, marks the period OPEN and writes PERIOD_REOPEN + APPROVAL
     * audit rows and the eim.period.reopened outbox event. The playbook then
     * mandates re-snapshot, corrected recalc and a fresh lock.
     */
    @POST
    @Path("/{periodKey}/reopen")
    @jakarta.annotation.security.PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response reopen(@jakarta.ws.rs.PathParam("periodKey") String periodKey,
                           JsonObject body,
                           @Context ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        boolean finance = ctx.hasRole("finance_approver");
        boolean sysadmin = ctx.hasRole("system_admin");
        if (!finance && !sysadmin) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("finance_approver or system_admin required (MI-18)")).build();
        }
        String impactMemo = body == null ? null : body.getString("impactMemo", null);
        if (impactMemo == null || impactMemo.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("impactMemo is mandatory for a break-glass reopen (MI-18)")).build();
        }
        try (Connection c = ds.getConnection()) {
            UUID periodId;
            String status;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payout_period_id, status FROM payout_period WHERE period_key = ?")) {
                ps.setString(1, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(error("period not found: " + periodKey)).build();
                    }
                    periodId = (UUID) rs.getObject(1);
                    status = rs.getString(2);
                }
            }
            if (!"LOCKED".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(error("period is not LOCKED (status " + status
                                + ") — reopen only applies to locked periods")).build();
            }

            // find or create the pending dual-approval request
            UUID requestId = null;
            String financeBy = null;
            String sysadminBy = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT request_id, finance_approved_by, sysadmin_approved_by "
                            + "FROM period_reopen_request WHERE payout_period_id = ? "
                            + "AND status = 'PENDING' FOR UPDATE")) {
                ps.setObject(1, periodId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        requestId = (UUID) rs.getObject(1);
                        financeBy = rs.getString(2);
                        sysadminBy = rs.getString(3);
                    }
                }
            }
            if (requestId == null) {
                requestId = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO period_reopen_request (request_id, payout_period_id, "
                                + "impact_memo, requested_by) VALUES (?,?,?,?)")) {
                    ps.setObject(1, requestId);
                    ps.setObject(2, periodId);
                    ps.setString(3, impactMemo);
                    ps.setString(4, ctx.actor());
                    ps.executeUpdate();
                }
            }

            // record this caller's side of the dual approval
            String role = finance ? "finance_approver" : "system_admin";
            String other = finance ? sysadminBy : financeBy;
            if (other != null && other.equals(ctx.actor())) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(error("dual control requires two distinct approvers (MI-18): "
                                + ctx.actor() + " already signed as the other role")).build();
            }
            boolean alreadySigned = finance ? financeBy != null : sysadminBy != null;
            if (!alreadySigned) {
                try (PreparedStatement ps = c.prepareStatement(finance
                        ? "UPDATE period_reopen_request SET finance_approved_by = ?, "
                                + "finance_approved_at = now() WHERE request_id = ?"
                        : "UPDATE period_reopen_request SET sysadmin_approved_by = ?, "
                                + "sysadmin_approved_at = now() WHERE request_id = ?")) {
                    ps.setString(1, ctx.actor());
                    ps.setObject(2, requestId);
                    ps.executeUpdate();
                }
                audit.record(ctx, "APPROVAL", "period_reopen_request", requestId.toString(),
                        null, "{\"role\":\"" + role + "\",\"actor\":\"" + ctx.actor() + "\"}",
                        "MI18_REOPEN_APPROVAL", impactMemo,
                        "eim.period.reopened", periodId.toString(),
                        "{\"request\":\"" + requestId + "\",\"approvedByRole\":\"" + role + "\"}");
                if (finance) {
                    financeBy = ctx.actor();
                } else {
                    sysadminBy = ctx.actor();
                }
            }

            // execute only when both signatures are present
            if (financeBy != null && sysadminBy != null) {
                String partition;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT eim_ops.reopen_period_partition(?)")) {
                    ps.setObject(1, periodId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        partition = rs.getString(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE period_reopen_request SET status = 'EXECUTED', "
                                + "executed_at = now() WHERE request_id = ?")) {
                    ps.setObject(1, requestId);
                    ps.executeUpdate();
                }
                audit.record(ctx, "PERIOD_REOPEN", "payout_period", periodId.toString(),
                        "{\"status\":\"LOCKED\"}",
                        "{\"status\":\"OPEN\",\"partition\":\"" + partition + "\","
                                + "\"finance\":\"" + financeBy + "\",\"sysadmin\":\"" + sysadminBy
                                + "\",\"memo\":\"" + impactMemo.replace("\"", "'") + "\"}",
                        "MI18_BREAK_GLASS", impactMemo,
                        "eim.period.reopened", periodId.toString(),
                        "{\"period\":\"" + periodKey + "\",\"status\":\"OPEN\","
                                + "\"financeApprover\":\"" + financeBy + "\","
                                + "\"systemAdmin\":\"" + sysadminBy + "\"}");
                return Response.ok(Map.of(
                        "period", periodKey,
                        "status", "OPEN",
                        "partition", partition,
                        "financeApprover", financeBy,
                        "systemAdmin", sysadminBy,
                        "followUps", List.of("re-snapshot source data",
                                "corrected recalculation", "mandatory re-lock")))
                        .build();
            }

            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("period", periodKey);
            pending.put("reopenStatus", "PENDING_DUAL_APPROVAL");
            pending.put("financeApprover", financeBy == null ? "" : financeBy);
            pending.put("systemAdmin", sysadminBy == null ? "" : sysadminBy);
            pending.put("waitingFor", finance ? "system_admin" : "finance_approver");
            return Response.ok(pending).build();
        } catch (SQLException e) {
            throw new WebApplicationException("period reopen failed: " + e.getMessage(), 500);
        }
    }

    private String error(String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }
}
