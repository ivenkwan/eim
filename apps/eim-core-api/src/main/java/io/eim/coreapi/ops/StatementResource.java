package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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

/**
 * Seller statements (TG4-SEL-1/2/3, FR-701/702/703): the payee's own
 * RLS-scoped statement overview (total payout, component breakdown with the
 * attainment position, open vs locked period state), the calculation trace
 * drawer payload bound straight to the ledger's JSONB trace, and
 * CSV/printable downloads whose numbers are byte-for-byte the on-screen
 * values. Served read-only even when the engine is down (NFR-43): this
 * resource only reads committed ledger rows.
 */
@Path("/api/v1/me/statement")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.transaction.Transactional
public class StatementResource {

    @Inject
    AgroalDataSource ds;

    @GET
    @PermitAll
    public Map<String, Object> overview(@QueryParam("periodId") String periodId,
                                        @Context ContainerRequestContext request)
            throws SQLException {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        Map<String, Object> out = new LinkedHashMap<>();
        if (ctx == null || ctx.payeeId() == null || ctx.payeeId().isBlank()) {
            out.put("error", "payee identity required");
            return out;
        }
        out.put("actor", ctx == null ? "anonymous" : ctx.actor());
        List<Map<String, Object>> components = new ArrayList<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        String periodKey = null;
        String status = "OPEN";
        String attainmentTo = "0";
        try (Connection c = ds.getConnection()) {
            MeResource.setSessionScope(c, ctx);
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT l.component_name, sum(l.payout_amount), count(*), "
                             + "max(l.calculation_trace->>'window_to'), p.period_key, p.status "
                             + "FROM commission_ledger_entry l "
                             + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                             + "WHERE l.payee_id = current_setting('app.current_payee_id', true)::uuid "
                             + "AND (?::uuid IS NULL OR l.payout_period_id = ?::uuid) "
                             + "GROUP BY l.component_name, p.period_key, p.status "
                             + "ORDER BY l.component_name")) {
            ps.setObject(1, periodId == null || periodId.isBlank()
                    ? null : java.util.UUID.fromString(periodId));
            ps.setObject(2, periodId == null || periodId.isBlank()
                    ? null : java.util.UUID.fromString(periodId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> comp = new LinkedHashMap<>();
                    comp.put("component", rs.getString(1));
                    java.math.BigDecimal amount = rs.getBigDecimal(2);
                    total = total.add(amount);
                    comp.put("amount", amount.toPlainString());
                    comp.put("entries", rs.getInt(3));
                    String windowTo = rs.getString(4);
                    if (windowTo != null) {
                        attainmentTo = windowTo;
                    }
                    periodKey = rs.getString(5);
                    status = rs.getString(6);
                    components.add(comp);
                }
            }
            }
        }
        out.put("period", periodKey);
        out.put("periodStatus", status);
        out.put("totalPayout", total.toPlainString());
        out.put("attainment", attainmentTo);
        out.put("components", components);
        out.put("nextTierHurdle", nextHurdle(attainmentTo));
        return out;
    }

    @GET
    @Path("/trace/{ledgerId}")
    @PermitAll
    public Response trace(@PathParam("ledgerId") String ledgerId,
                          @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.payeeId() == null || ctx.payeeId().isBlank()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "payee identity required")).build();
        }
        try (Connection c = ds.getConnection()) {
            MeResource.setSessionScope(c, ctx);
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT component_name, payout_amount::text, entry_type, "
                             + "calculation_trace::text, transaction_id::text "
                             + "FROM commission_ledger_entry "
                             + "WHERE ledger_id = CAST(? AS uuid) "
                             + "AND payee_id = current_setting('app.current_payee_id', true)::uuid")) {
            ps.setString(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "entry not found for this payee")).build();
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ledgerId", ledgerId);
                out.put("component", rs.getString(1));
                out.put("amount", rs.getString(2));
                out.put("entryType", rs.getString(3));
                out.put("trace", rs.getString(4));
                out.put("transactionId", rs.getString(5));
                return Response.ok(out).build();
            }
            }
        }
    }

    /** CSV download matching the on-screen values exactly (FR-703). */
    @GET
    @Path("/download")
    @PermitAll
    @Produces("text/csv")
    public Response download(@QueryParam("periodId") String periodId,
                             @Context ContainerRequestContext request) throws SQLException {
        Map<String, Object> overview = overview(periodId, request);
        StringBuilder csv = new StringBuilder("component,amount\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comps = (List<Map<String, Object>>) overview.get("components");
        for (Map<String, Object> comp : comps) {
            csv.append('"').append(comp.get("component")).append("\",")
                    .append(comp.get("amount")).append('\n');
        }
        csv.append("\"TOTAL\",").append(overview.get("totalPayout")).append('\n');
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=statement.csv")
                .type("text/csv").build();
    }

    private static String nextHurdle(String attainmentTo) {
        try {
            java.math.BigDecimal a = new java.math.BigDecimal(attainmentTo);
            if (a.compareTo(new java.math.BigDecimal("100000")) < 0) {
                return "100000";
            }
            if (a.compareTo(new java.math.BigDecimal("150000")) < 0) {
                return "150000";
            }
            return null; // top tier reached
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
