package io.eim.process.flow;

import io.agroal.api.AgroalDataSource;
import io.eim.process.audit.ProcessAudit;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * True-up queue + true-up-review process (TG4-GOV-3, FR-604): shadow-replay
 * diffs enter the queue; Finance reviews a diff report, approves or rejects
 * with a note; approval posts the PRIOR_PERIOD_TRUEUP entry into the open
 * period with origin linkage. Retro CRM changes are routed here by the
 * ingestion late-arrival signal (regression-tested in the unit suite).
 */
@Path("/api/v1/process/trueup")
@Produces(MediaType.APPLICATION_JSON)
public class TrueupReviewResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    ProcessAudit audit;

    @GET
    @PermitAll
    public List<Map<String, Object>> queue(@Context ContainerRequestContext request)
            throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.trueup_id::text, t.tenant_id::text, t.origin_period_id::text, "
                             + "t.open_period_id::text, t.payee_id::text, t.diff_amount::text, "
                             + "t.status, coalesce(t.report::text, '{}'), t.created_at::text "
                             + "FROM trueup_review t WHERE t.status IN ('PENDING','APPROVED') "
                             + "ORDER BY t.created_at");
             ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trueupId", rs.getString(1));
                row.put("tenantId", rs.getString(2));
                row.put("originPeriodId", rs.getString(3));
                row.put("openPeriodId", rs.getString(4));
                row.put("payeeId", rs.getString(5));
                row.put("diffAmount", rs.getString(6));
                row.put("status", rs.getString(7));
                row.put("report", rs.getString(8));
                row.put("createdAt", rs.getString(9));
                out.add(row);
            }
            return out;
        }
    }

    @POST
    @Path("/{trueupId}/decision")
    @jakarta.annotation.security.PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Response decide(@PathParam("trueupId") String trueupId, JsonObject body,
                           @Context ContainerRequestContext request) throws SQLException {
        String deciderRole = request.getHeaderString("X-Roles");
        if (deciderRole == null || !deciderRole.contains("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required (SoD)")).build();
        }
        String decision = required(body, "decision"); // APPROVE | REJECT
        String note = required(body, "note");
        String actor = actorOf(request);
        try (Connection c = ds.getConnection()) {
            String tenantId;
            String openPeriodId;
            String payeeId;
            String amount;
            String originPeriodId;
            String status;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tenant_id::text, open_period_id::text, payee_id::text, "
                            + "diff_amount::text, origin_period_id::text, status "
                            + "FROM trueup_review WHERE trueup_id = CAST(? AS uuid)")) {
                ps.setString(1, trueupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown true-up")).build();
                    }
                    tenantId = rs.getString(1);
                    openPeriodId = rs.getString(2);
                    payeeId = rs.getString(3);
                    amount = rs.getString(4);
                    originPeriodId = rs.getString(5);
                    status = rs.getString(6);
                }
            }
            if (!"PENDING".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "already decided: " + status)).build();
            }

            if ("APPROVE".equals(decision)) {
                // post the true-up entry into the OPEN period (FR-604.3)
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT eim_ops.ensure_period_partition(CAST(? AS uuid), "
                                + "(SELECT period_key FROM payout_period "
                                + "WHERE payout_period_id = CAST(? AS uuid)))")) {
                    ps.setString(1, openPeriodId);
                    ps.setString(2, openPeriodId);
                    ps.executeQuery();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                                + "payee_id, component_name, payout_amount, entry_type, "
                                + "calculation_trace, is_locked) "
                                + "VALUES (gen_random_uuid(),CAST(? AS uuid),CAST(? AS uuid),"
                                + "CAST(? AS uuid),'shadow_replay',?,'PRIOR_PERIOD_TRUEUP',"
                                + "CAST(? AS jsonb),false)")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, openPeriodId);
                    ps.setString(3, payeeId);
                    ps.setBigDecimal(4, new java.math.BigDecimal(amount));
                    ps.setString(5, "{\"origin_period_id\":\"" + originPeriodId
                            + "\",\"trueup_id\":\"" + trueupId + "\",\"approved_by\":\"" + actor + "\"}");
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE trueup_review SET status = 'POSTED', decided_by = ?, "
                                + "decision_note = ?, decided_at = now() "
                                + "WHERE trueup_id = CAST(? AS uuid)")) {
                    ps.setString(1, actor);
                    ps.setString(2, note);
                    ps.setString(3, trueupId);
                    ps.executeUpdate();
                }
            } else if ("REJECT".equals(decision)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE trueup_review SET status = 'REJECTED', decided_by = ?, "
                                + "decision_note = ?, decided_at = now() "
                                + "WHERE trueup_id = CAST(? AS uuid)")) {
                    ps.setString(1, actor);
                    ps.setString(2, note);
                    ps.setString(3, trueupId);
                    ps.executeUpdate();
                }
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "decision must be APPROVE or REJECT")).build();
            }
            audit.processEvent(tenantId, "trueup_review",
                    "TRUEUP_" + ("APPROVE".equals(decision) ? "POSTED" : "REJECTED"),
                    "{\"trueupId\":\"" + trueupId + "\",\"amount\":\"" + amount + "\"}", actor);
        }
        return Response.accepted(Map.of("trueupId", trueupId,
                "status", "APPROVE".equals(decision) ? "POSTED" : "REJECTED")).build();
    }

    private static String required(JsonObject body, String key) {
        String v = body == null ? null : body.getString(key, null);
        if (v == null || v.isBlank()) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", key + " is required")).build());
        }
        return v;
    }

    private static String actorOf(ContainerRequestContext request) {
        String actor = request.getHeaderString("X-Actor");
        return actor == null || actor.isBlank() ? "finance" : actor;
    }
}
