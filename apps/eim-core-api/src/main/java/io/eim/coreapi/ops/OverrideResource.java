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
 * Manual adjustment overrides (TG4-GOV-1, FR-602/601.1): a separate
 * MANUAL_OVERRIDE ledger entry preserves the original, the reason code and
 * comment are mandatory, and an optional dispute links the ticket. Only
 * comp_admin may adjust; locked periods refuse new overrides.
 */
@Path("/api/v1/admin/overrides")
@Produces(MediaType.APPLICATION_JSON)
public class OverrideResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @POST
    @jakarta.annotation.security.PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin required")).build();
        }
        String periodId = required(body, "payoutPeriodId");
        String payeeId = required(body, "payeeId");
        String componentName = required(body, "componentName");
        String adjusted = required(body, "adjustedAmount");
        String reasonCode = required(body, "reasonCode");
        if (body.getString("reasonCode", "").isBlank()
                || body.getString("reasonComment", "").isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "reasonCode and reasonComment are mandatory (FR-602)"))
                    .build();
        }
        UUID overrideId = UUID.randomUUID();
        String original = "0";
        try (Connection c = ds.getConnection()) {
            // gate: period must not be locked yet
            String status;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status FROM payout_period WHERE payout_period_id = CAST(? AS uuid)")) {
                ps.setString(1, periodId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown payout period")).build();
                    }
                    status = rs.getString(1);
                }
            }
            if ("LOCKED".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "period is LOCKED — break-glass reopen required"))
                        .build();
            }

            // the original = current CALCULATED total for the component
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(payout_amount), 0)::text FROM commission_ledger_entry "
                            + "WHERE payout_period_id = CAST(? AS uuid) "
                            + "AND payee_id = CAST(? AS uuid) AND component_name = ? "
                            + "AND entry_type = 'CALCULATED'")) {
                ps.setString(1, periodId);
                ps.setString(2, payeeId);
                ps.setString(3, componentName);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    original = rs.getString(1);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO manual_override (override_id, tenant_id, payout_period_id, payee_id, "
                            + "component_name, original_amount, adjusted_amount, reason_code, "
                            + "reason_comment, dispute_id, created_by) "
                            + "VALUES (?,CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),?,?,?,?,?,CAST(? AS uuid),?)")) {
                ps.setObject(1, overrideId);
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, UUID.fromString(periodId));
                ps.setObject(4, UUID.fromString(payeeId));
                ps.setString(5, componentName);
                ps.setBigDecimal(6, new java.math.BigDecimal(original));
                ps.setBigDecimal(7, new java.math.BigDecimal(adjusted));
                ps.setString(8, reasonCode);
                ps.setString(9, body.getString("reasonComment", null));
                if (!body.containsKey("disputeId") || body.isNull("disputeId")) {
                    ps.setNull(10, java.sql.Types.OTHER);
                } else {
                    ps.setObject(10, UUID.fromString(body.getString("disputeId")));
                }
                ps.setString(11, ctx.actor());
                ps.executeUpdate();
            }

            // the override lands as its own typed ledger entry; the
            // CALCULATED original stays untouched (FR-602)
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT eim_ops.ensure_period_partition(CAST(? AS uuid), "
                            + "(SELECT period_key FROM payout_period WHERE payout_period_id = CAST(? AS uuid)))")) {
                ps.setString(1, periodId);
                ps.setString(2, periodId);
                ps.executeQuery();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                            + "payee_id, component_name, payout_amount, entry_type, calculation_trace, is_locked) "
                            + "VALUES (?,?,?,?,?,?,'MANUAL_OVERRIDE',CAST(? AS jsonb),false)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, UUID.fromString(periodId));
                ps.setObject(4, UUID.fromString(payeeId));
                ps.setString(5, componentName);
                java.math.BigDecimal delta = new java.math.BigDecimal(adjusted)
                        .subtract(new java.math.BigDecimal(original));
                ps.setBigDecimal(6, delta);
                ps.setString(7, "{\"override_id\":\"" + overrideId + "\",\"original\":\"" + original
                        + "\",\"adjusted\":\"" + adjusted + "\",\"reason_code\":\"" + reasonCode
                        + "\",\"dispute_id\":" + (body.containsKey("disputeId") && !body.isNull("disputeId")
                        ? "\"" + body.getString("disputeId") + "\"" : "null") + "}");
                ps.executeUpdate();
            }

            audit.record(ctx, "MANUAL_ADJUSTMENT", "manual_override", overrideId.toString(),
                    "{\"amount\":\"" + original + "\"}",
                    "{\"amount\":\"" + adjusted + "\",\"reason\":\"" + reasonCode + "\"}",
                    reasonCode, body.getString("reasonComment", null),
                    "adjustment.applied", overrideId.toString(),
                    "{\"component\":\"" + componentName + "\",\"original\":\"" + original
                            + "\",\"adjusted\":\"" + adjusted + "\"}");
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "override failed: " + e.getMessage())).build());
        }
        return Response.accepted(Map.of("overrideId", overrideId.toString(),
                "originalAmount", original, "adjustedAmount", adjusted)).build();
    }

    @GET
    @PermitAll
    public List<Map<String, Object>> list(@Context ContainerRequestContext request) throws SQLException {
        ctx(request);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT o.override_id::text, o.payout_period_id::text, o.payee_id::text, "
                             + "o.component_name, o.original_amount::text, o.adjusted_amount::text, "
                             + "o.reason_code, o.reason_comment, o.dispute_id::text, o.created_by, "
                             + "o.created_at::text FROM manual_override o "
                             + "JOIN payout_period p ON p.payout_period_id = o.payout_period_id "
                             + "WHERE p.tenant_id = CAST(? AS uuid) ORDER BY o.created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("overrideId", rs.getString(1));
                row.put("payoutPeriodId", rs.getString(2));
                row.put("payeeId", rs.getString(3));
                row.put("componentName", rs.getString(4));
                row.put("originalAmount", rs.getString(5));
                row.put("adjustedAmount", rs.getString(6));
                row.put("reasonCode", rs.getString(7));
                row.put("reasonComment", rs.getString(8));
                row.put("disputeId", rs.getString(9));
                row.put("createdBy", rs.getString(10));
                row.put("createdAt", rs.getString(11));
                out.add(row);
            }
            return out;
        }
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
