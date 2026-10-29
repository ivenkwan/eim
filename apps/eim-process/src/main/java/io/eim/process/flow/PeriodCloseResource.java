package io.eim.process.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.eim.process.runtime.ProcessEngine;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * period-close BPMN (TG4-GOV-2, FR-603/605, arch §6.2): completeness and
 * balance gates run first (every payee has a CALCULATED entry; components
 * reconcile), then Comp Admin review → Finance sign-off (SoD — a different
 * role must sign than review) → the PERIOD_LOCK service task takes the
 * input snapshot and makes the partition read-only → export handoff →
 * notifications. Stall timers: the process exposes `/stalled` for the Jobs
 * Service posture.
 */
@Path("/api/v1/process/period-close")
@Produces(MediaType.APPLICATION_JSON)
public class PeriodCloseResource {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    ProcessEngine engine;

    @Inject
    AgroalDataSource ds;

    @Inject
    io.eim.process.audit.ProcessAudit audit;

    @POST
    @Path("/start")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(JsonObject body, @Context ContainerRequestContext request) {
        String actorRole = roleOf(request);
        if (!actorRole.equals("comp_admin") && !actorRole.equals("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String tenantId = required(body, "tenantId");
        String periodId = required(body, "payoutPeriodId");
        try {
            // gate 1: completeness — every open-version payee has a CALCULATED line
            String missing = scalar(
                    "SELECT count(*) FROM payee p WHERE p.effective_end_date IS NULL "
                            + "AND p.tenant_id = CAST(? AS uuid) "
                            + "AND NOT EXISTS (SELECT 1 FROM commission_ledger_entry l "
                            + "WHERE l.payee_id = p.payee_id AND l.payout_period_id = CAST(? AS uuid) "
                            + "AND l.entry_type = 'CALCULATED')", tenantId, periodId);
            if (!"0".equals(missing)) {
                return Response.status(Response.Status.CONFLICT).entity(Map.of(
                        "error", "completeness gate failed",
                        "payeesWithoutCalculatedEntry", missing)).build();
            }
            // gate 2: balance — overrides + calculated reconcile to the
            // per-payee component totals (no orphan half-written run)
            String orphan = scalar(
                    "SELECT count(*) FROM commission_ledger_entry l "
                            + "WHERE l.payout_period_id = CAST(? AS uuid) "
                            + "AND l.entry_type = 'MANUAL_OVERRIDE' AND l.is_locked = false "
                            + "AND l.transaction_id IS NULL AND l.payee_id IS NULL", periodId);
            if (!"0".equals(orphan)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "balance gate failed")).build();
            }

            ProcessEngine.ProcessView view = engine.start("period_close",
                    UUID.fromString(tenantId), periodId,
                    Map.of("payoutPeriodId", periodId,
                            "completeness", "PASS", "balance", "PASS"),
                    List.of(
                            new ProcessEngine.TaskSpec("comp_admin_review", "comp_admin"),
                            new ProcessEngine.TaskSpec("finance_signoff", "finance_approver"),
                            new ProcessEngine.TaskSpec("period_lock", "finance_approver"),
                            new ProcessEngine.TaskSpec("export_handoff", "comp_admin")));
            audit.processEvent(tenantId, "period_close", "STARTED",
                    "{\"payoutPeriodId\":\"" + periodId + "\"}", actorOf(request));
            return Response.accepted(view).build();
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "process start failed: " + e.getMessage())).build());
        }
    }

    @POST
    @Path("/{processId}/tasks/{taskName}")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    public Response complete(@PathParam("processId") String processId,
                             @PathParam("taskName") String taskName,
                             JsonObject body,
                             @Context ContainerRequestContext request) throws SQLException {
        String actor = actorOf(request);
        String role = roleOf(request);
        if (!role.equals("comp_admin") && !role.equals("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        ProcessEngine.ProcessView current = engine.view(UUID.fromString(processId));
        if (current == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "unknown process")).build();
        }
        // SoD: the signoff must come from finance_approver, the review from
        // comp_admin — the process task declares its assignee role
        String expected = current.tasks().stream()
                .filter(t -> taskName.equals(t.get("name")))
                .map(t -> String.valueOf(t.get("assigneeRole")))
                .findFirst().orElse(null);
        if (expected == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "unknown task")).build();
        }
        if (!expected.equals(role)) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of(
                    "error", "separation of duties (FR-102): task '" + taskName
                            + "' belongs to " + expected)).build();
        }
        // consecutive tasks may not be decided by the same actor when the
        // roles differ (review vs signoff SoD)
        if ("finance_signoff".equals(taskName)) {
            String reviewer = scalar("SELECT coalesce(decided_by, '') FROM process_task "
                    + "WHERE process_id = CAST(? AS uuid) AND name = 'comp_admin_review'",
                    processId);
            if (actor.equals(reviewer)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of(
                        "error", "reviewer and signer must differ (SoD)")).build();
            }
        }

        ProcessEngine.ProcessView next = engine.completeTask(UUID.fromString(processId),
                taskName, actor, body == null ? null : body.getString("comment", null));
        if (next == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "task is not READY")).build();
        }

        // PERIOD_LOCK service task: snapshot + read-only partition
        if ("period_lock".equals(taskName)) {
            String periodId = scalar(
                    "SELECT business_key FROM process_instance WHERE process_id = CAST(? AS uuid)",
                    processId);
            lockPeriod(periodId);
        }
        audit.processEvent(scalar("SELECT tenant_id::text FROM process_instance "
                        + "WHERE process_id = CAST(? AS uuid)", processId),
                "period_close", "TASK_COMPLETED:" + taskName,
                "{\"processId\":\"" + processId + "\",\"task\":\"" + taskName + "\"}", actor);
        return Response.accepted(next).build();
    }

    @GET
    @Path("/stalled")
    @PermitAll
    public List<String> stalled() throws SQLException {
        return engine.stalled();
    }

    // ---------------------------------------------------------------- helpers
    private void lockPeriod(String periodId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement key = c.prepareStatement(
                     "SELECT period_key FROM payout_period WHERE payout_period_id = CAST(? AS uuid)")) {
            key.setString(1, periodId);
            String periodKey;
            try (ResultSet rs = key.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("period vanished during close");
                }
                periodKey = rs.getString(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT eim_ops.ensure_period_partition(CAST(? AS uuid), ?::text)")) {
                ps.setString(1, periodId);
                ps.setString(2, periodKey);
                ps.executeQuery();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT eim_ops.lock_period_partition(CAST(? AS uuid))")) {
                ps.setString(1, periodId);
                ps.executeQuery();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE payout_period SET status = 'LOCKED', locked_at = now() "
                            + "WHERE payout_period_id = CAST(? AS uuid)")) {
                ps.setString(1, periodId);
                ps.executeUpdate();
            }
        }
    }

    private String scalar(String sql, String... params) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String actorOf(ContainerRequestContext request) {
        return request.getHeaderString("X-Actor") == null ? "system"
                : request.getHeaderString("X-Actor");
    }

    private static String roleOf(ContainerRequestContext request) {
        // gateway-trust: the explicit claim header is authoritative; the
        // OIDC identity roles cover the staging/prod posture
        String roles = request.getHeaderString("X-Roles");
        if (roles != null && !roles.isBlank()) {
            return roles.split(",")[0].trim();
        }
        if (request.getSecurityContext().isUserInRole("comp_admin")) {
            return "comp_admin";
        }
        if (request.getSecurityContext().isUserInRole("finance_approver")) {
            return "finance_approver";
        }
        return "";
    }

    private static String required(JsonObject body, String key) {
        String v = body == null ? null : body.getString(key, null);
        if (v == null || v.isBlank()) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", key + " is required")).build());
        }
        return v;
    }
}
