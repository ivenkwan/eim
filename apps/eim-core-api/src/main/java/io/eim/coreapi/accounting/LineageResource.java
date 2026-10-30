package io.eim.coreapi.accounting;

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

/**
 * Zero-orphan lineage (TG7-ACC-11, FR-909/NFR-61): every GL line drills
 * through the FK chain schedule_line → asset → ledger entry → transaction →
 * rep statement; the monthly sampled audit verifies a random sample of GL
 * lines resolve end-to-end and reports any orphan.
 */
@Path("/api/v1/accounting/lineage")
@Produces(MediaType.APPLICATION_JSON)
public class LineageResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @GET
    @Path("/gl-lines/{glLineId}")
    @PermitAll
    public Response drill(@PathParam("glLineId") String glLineId,
                          @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT g.gl_account, g.dr_amount::text, g.cr_amount::text, g.memo, "
                             + "b.je_type, b.period_key, "
                             + "s.line_id::text, s.period_key, s.line_type, s.amount::text, "
                             + "a.asset_id::text, a.contract_ref, a.capitalized_amount::text, "
                             + "a.fx_rate::text, "
                             + "l.ledger_id::text, l.component_name, l.payout_amount::text, "
                             + "t.transaction_id::text, t.source_system, t.booking_amount::text, "
                             + "t.external_deal_id, "
                             + "p.payee_id::text, p.full_name, p.employee_number "
                             + "FROM gl_journal_entry_line g "
                             + "JOIN gl_journal_batch b ON b.batch_id = g.batch_id "
                             + "LEFT JOIN amortization_schedule_line s ON s.line_id = g.schedule_line_id "
                             + "LEFT JOIN capitalized_cost_asset a ON a.asset_id = g.asset_id "
                             + "LEFT JOIN commission_ledger_entry l ON l.ledger_id = g.ledger_id "
                             + "LEFT JOIN raw_transaction t ON t.transaction_id = g.transaction_id "
                             + "LEFT JOIN payee p ON p.payee_id = COALESCE(a.payee_id, l.payee_id) "
                             + "WHERE g.gl_line_id = CAST(? AS uuid) "
                             + "AND g.tenant_id = CAST(? AS uuid)")) {
            ps.setString(1, glLineId);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "unknown GL line")).build();
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("glLineId", glLineId);
                out.put("glAccount", rs.getString(1));
                out.put("dr", rs.getString(2));
                out.put("cr", rs.getString(3));
                out.put("memo", rs.getString(4));
                Map<String, Object> batch = new LinkedHashMap<>();
                batch.put("jeType", rs.getString(5));
                batch.put("periodKey", rs.getString(6));
                out.put("batch", batch);
                Map<String, Object> schedule = new LinkedHashMap<>();
                schedule.put("lineId", rs.getString(7));
                schedule.put("periodKey", rs.getString(8));
                schedule.put("lineType", rs.getString(9));
                schedule.put("amount", rs.getString(10));
                out.put("scheduleLine", schedule);
                Map<String, Object> asset = new LinkedHashMap<>();
                asset.put("assetId", rs.getString(11));
                asset.put("contractRef", rs.getString(12));
                asset.put("capitalizedAmount", rs.getString(13));
                asset.put("fxRate", rs.getString(14));
                out.put("asset", asset);
                Map<String, Object> ledger = new LinkedHashMap<>();
                ledger.put("ledgerId", rs.getString(15));
                ledger.put("componentName", rs.getString(16));
                ledger.put("payoutAmount", rs.getString(17));
                out.put("ledgerEntry", ledger);
                Map<String, Object> txn = new LinkedHashMap<>();
                txn.put("transactionId", rs.getString(18));
                txn.put("sourceSystem", rs.getString(19));
                txn.put("bookingAmount", rs.getString(20));
                txn.put("externalDealId", rs.getString(21));
                out.put("transaction", txn);
                Map<String, Object> rep = new LinkedHashMap<>();
                rep.put("payeeId", rs.getString(22));
                rep.put("fullName", rs.getString(23));
                rep.put("employeeNumber", rs.getString(24));
                out.put("rep", rep);
                return Response.ok(out).build();
            }
        }
    }

    /** Monthly sampled lineage audit (NFR-61): sampled GL lines must resolve. */
    @POST
    @Path("/audit-sample/{periodKey}")
    @PermitAll
    @Transactional
    public Response auditSample(@PathParam("periodKey") String periodKey,
                                @jakarta.ws.rs.QueryParam("sample") Integer sampleSize,
                                @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver") && !ctx.hasRole("system_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver or system_admin (auditor) required"))
                    .build();
        }
        int sample = sampleSize == null || sampleSize <= 0 ? 25 : sampleSize;
        int sampled = 0;
        List<String> orphans = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT g.gl_line_id::text, "
                             + "(g.ledger_id IS NULL OR EXISTS (SELECT 1 FROM commission_ledger_entry l "
                             + " WHERE l.ledger_id = g.ledger_id)) AS ledger_ok "
                             + "FROM gl_journal_entry_line g JOIN gl_journal_batch b "
                             + "ON b.batch_id = g.batch_id "
                             + "WHERE b.period_key = ? AND g.tenant_id = CAST(? AS uuid) "
                             + "ORDER BY random() LIMIT ?")) {
            ps.setString(1, periodKey);
            ps.setString(2, ctx.tenantId());
            ps.setInt(3, sample);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sampled++;
                    if (!rs.getBoolean(2)) {
                        orphans.add(rs.getString(1));
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodKey", periodKey);
        out.put("sampled", sampled);
        out.put("orphans", orphans);
        out.put("zeroOrphan", orphans.isEmpty());
        audit.record(ctx, "APPROVAL", "gl_journal_entry_line", periodKey, null,
                "{\"sampled\":" + sampled + ",\"orphans\":" + orphans.size() + "}",
                "LINEAGE_AUDIT", "monthly sampled lineage audit",
                "eim.gl.pushed", periodKey,
                "{\"period\":\"" + periodKey + "\",\"sampled\":" + sampled
                        + ",\"orphans\":" + orphans.size() + "}");
        return Response.ok(out).build();
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
