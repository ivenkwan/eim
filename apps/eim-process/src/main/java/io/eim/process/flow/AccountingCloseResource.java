package io.eim.process.flow;

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
import java.util.UUID;
import java.util.Map;

/**
 * accounting-close BPMN (TG7-ACC-12, arch §6.2): completeness (100% of the
 * locked period's ledger entries classified per book, FR-908) → JE preview
 * (Comp Admin) → JE approval (Finance, SoD — different actor than preview)
 * → GL push → ST/LT reclassification. Post-lock adjustments (ACC-13) flow
 * through the catch-up/impairment lifecycle endpoints, never by editing
 * posted schedules — the process verifies none are pending before completing.
 */
@Path("/api/v1/process/accounting-close")
@Produces(MediaType.APPLICATION_JSON)
public class AccountingCloseResource {

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
        String bookId = required(body, "bookId");
        String periodKey = required(body, "periodKey");
        try {
            // gate: the period must be LOCKED (accounting closes after pay close)
            String status = scalar(
                    "SELECT status FROM payout_period WHERE period_key = ? "
                            + "AND tenant_id = CAST(? AS uuid)", periodKey, tenantId);
            if (!"LOCKED".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "accounting close requires a LOCKED pay period, got "
                                + status)).build();
            }
            // completeness gate (FR-908): 100% of the period's ledger entries
            // classified as capitalize or explicitly expensed for this book
            String unclassified = scalar(
                    "SELECT count(*) FROM commission_ledger_entry l "
                            + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                            + "WHERE p.period_key = ? AND p.tenant_id = CAST(? AS uuid) "
                            + "AND NOT EXISTS (SELECT 1 FROM ledger_classification lc "
                            + "WHERE lc.ledger_id = l.ledger_id AND lc.book_id = CAST(? AS uuid))",
                    periodKey, tenantId, bookId);
            if (!"0".equals(unclassified)) {
                return Response.status(Response.Status.CONFLICT).entity(Map.of(
                        "error", "completeness gate failed (FR-908): unclassified entries",
                        "unclassified", unclassified)).build();
            }
            // balance gate (FR-907.2): every journal batch for the period balances
            String unbalanced = scalar(
                    "SELECT count(*) FROM gl_journal_batch b WHERE b.book_id = CAST(? AS uuid) "
                            + "AND b.period_key = ? AND b.total_dr <> b.total_cr",
                    bookId, periodKey);
            if (!"0".equals(unbalanced)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "balance gate failed (FR-907.2)",
                                "unbalancedBatches", unbalanced)).build();
            }

            ProcessEngine.ProcessView view = engine.start("accounting_close",
                    UUID.fromString(tenantId), bookId + ":" + periodKey,
                    Map.of("bookId", bookId, "periodKey", periodKey,
                            "completeness", "PASS", "balance", "PASS"),
                    java.util.List.of(
                            new ProcessEngine.TaskSpec("je_preview", "comp_admin"),
                            new ProcessEngine.TaskSpec("je_approval", "finance_approver"),
                            new ProcessEngine.TaskSpec("gl_push", "comp_admin"),
                            new ProcessEngine.TaskSpec("st_lt_reclass", "finance_approver")));
            audit.processEvent(tenantId, "accounting_close", "STARTED",
                    "{\"bookId\":\"" + bookId + "\",\"periodKey\":\"" + periodKey + "\"}",
                    actorOf(request));
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
        return doComplete(processId, taskName, body, request);
    }

    private Response doComplete(String processId, String taskName, JsonObject body,
                                ContainerRequestContext request) throws SQLException {
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
                    "error", "separation of duties: task '" + taskName + "' belongs to " + expected))
                    .build();
        }
        // SoD: the JE approval must come from a different actor than the preview
        if ("je_approval".equals(taskName)) {
            String previewer = scalar("SELECT coalesce(decided_by, '') FROM process_task "
                    + "WHERE process_id = CAST(? AS uuid) AND name = 'je_preview'", processId);
            if (actor.equals(previewer)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of(
                        "error", "previewer and approver must differ (SoD)")).build();
            }
        }
        // gl_push service task: every EXPORTED batch must be SYNCED before the
        // process can move on (FR-907.4)
        if ("gl_push".equals(taskName)) {
            String businessKey = scalar("SELECT business_key FROM process_instance "
                    + "WHERE process_id = CAST(? AS uuid)", processId);
            String[] parts = businessKey.split(":", 2);
            String pending = scalar(
                    "SELECT count(*) FROM gl_journal_batch WHERE book_id = CAST(? AS uuid) "
                            + "AND period_key = ? AND status <> 'SYNCED'",
                    parts[0], parts[1]);
            if (!"0".equals(pending)) {
                return Response.status(Response.Status.CONFLICT).entity(Map.of(
                        "error", "GL push incomplete: batches not SYNCED", "pending", pending))
                        .build();
            }
        }

        ProcessEngine.ProcessView next = engine.completeTask(UUID.fromString(processId),
                taskName, actor, body == null ? null : body.getString("comment", null));
        if (next == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "task is not READY")).build();
        }
        audit.processEvent(scalar("SELECT tenant_id::text FROM process_instance "
                        + "WHERE process_id = CAST(? AS uuid)", processId),
                "accounting_close", "TASK_COMPLETED:" + taskName,
                "{\"processId\":\"" + processId + "\",\"task\":\"" + taskName + "\"}", actor);
        return Response.accepted(next).build();
    }

    @GET
    @Path("/stalled")
    @PermitAll
    public java.util.List<String> stalled() throws SQLException {
        return engine.stalled();
    }

    // ---------------------------------------------------------------- helpers
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

    private static String required(JsonObject body, String key) {
        String v = body == null ? null : body.getString(key, null);
        if (v == null || v.isBlank()) {
            throw new WebApplicationException(Response.status(400)
                    .entity(Map.of("error", key + " is required")).build());
        }
        return v;
    }

    private String roleOf(ContainerRequestContext request) {
        String roles = request.getHeaderString("X-Roles");
        return roles == null ? "" : roles.split(",")[0].trim();
    }

    private String actorOf(ContainerRequestContext request) {
        String actor = request.getHeaderString("X-Actor");
        return actor == null ? "anonymous" : actor;
    }
}
