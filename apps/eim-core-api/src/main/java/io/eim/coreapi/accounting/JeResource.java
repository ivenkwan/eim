package io.eim.coreapi.accounting;

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

import java.math.BigDecimal;
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
 * GL journal generation, balance gate and ERP push (TG7-ACC-9, FR-907):
 * four standard templates — payroll accrual, capitalization, periodic
 * amortization, churn impairment (plus ST/LT reclass from the amortization
 * resource) — each mapped to GL accounts with cost center/subsidiary
 * dimensions and full lineage. Batches are created balanced (DR ≡ CR per
 * subsidiary/currency); the period balance gate refuses an unbalanced close;
 * the ERP push tracks PENDING → EXPORTED → SYNCED | FAILED with retry.
 */
@Path("/api/v1/accounting/books/{bookId}/jes")
@Produces(MediaType.APPLICATION_JSON)
public class JeResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @POST
    @Path("/{periodKey}/{jeType}")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response generate(@PathParam("bookId") String bookId,
                             @PathParam("periodKey") String periodKey,
                             @PathParam("jeType") String jeType, JsonObject body,
                             @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required")).build();
        }
        String subsidiary = body == null ? "EIM" : body.getString("subsidiary", "EIM");
        String costCenter = body == null ? "SALES" : body.getString("costCenter", "SALES");
        try (Connection c = ds.getConnection()) {
            String currency = bookCurrency(c, bookId);
            List<Object[]> lines = switch (jeType) {
                case "PAYROLL_ACCRUAL" -> payrollAccrual(c, ctx, bookId, periodKey);
                case "CAPITALIZATION" -> capitalization(c, ctx, bookId, periodKey);
                case "AMORTIZATION" -> amortization(c, ctx, bookId, periodKey);
                case "IMPAIRMENT" -> impairment(c, ctx, bookId, periodKey);
                case "CATCH_UP" -> catchUp(c, ctx, bookId, periodKey);
                default -> null;
            };
            if (lines == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "jeType must be PAYROLL_ACCRUAL | CAPITALIZATION | "
                                + "AMORTIZATION | IMPAIRMENT | CATCH_UP")).build();
            }
            if (lines.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "no source amounts for " + jeType
                                + " in " + periodKey)).build();
            }
            UUID batchId = UUID.randomUUID();
            BigDecimal dr = BigDecimal.ZERO;
            BigDecimal cr = BigDecimal.ZERO;
            for (Object[] l : lines) {
                dr = dr.add((BigDecimal) l[5]);
                cr = cr.add((BigDecimal) l[6]);
            }
            // the balance gate applies at creation: an unbalanced template is a bug
            if (dr.compareTo(cr) != 0) {
                throw new WebApplicationException("unbalanced template " + jeType
                        + ": DR " + dr + " vs CR " + cr + " (FR-907.2)", 500);
            }
            // parent batch first: the lines carry the FK
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gl_journal_batch (batch_id, tenant_id, book_id, period_key, "
                            + "je_type, status, total_dr, total_cr, created_by) "
                            + "VALUES (?,?,?,?,?,'PENDING',?,?,?)")) {
                ps.setObject(1, batchId);
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, UUID.fromString(bookId));
                ps.setString(4, periodKey);
                ps.setString(5, jeType);
                ps.setBigDecimal(6, dr);
                ps.setBigDecimal(7, cr);
                ps.setString(8, ctx.actor());
                ps.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO gl_journal_entry_line (gl_line_id, tenant_id, batch_id, "
                            + "schedule_line_id, asset_id, ledger_id, transaction_id, gl_account, "
                            + "cost_center, subsidiary, currency, dr_amount, cr_amount, memo) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Object[] l : lines) {
                    BigDecimal d = (BigDecimal) l[5];
                    BigDecimal r = (BigDecimal) l[6];
                    ins.setObject(1, UUID.randomUUID());
                    ins.setObject(2, UUID.fromString(ctx.tenantId()));
                    ins.setObject(3, batchId);
                    ins.setObject(4, l[0] == null ? null : UUID.fromString((String) l[0]));
                    ins.setObject(5, l[1] == null ? null : UUID.fromString((String) l[1]));
                    ins.setObject(6, l[2] == null ? null : UUID.fromString((String) l[2]));
                    ins.setObject(7, l[3] == null ? null : UUID.fromString((String) l[3]));
                    ins.setString(8, (String) l[4]);
                    ins.setString(9, costCenter);
                    ins.setString(10, subsidiary);
                    ins.setString(11, currency);
                    ins.setBigDecimal(12, d);
                    ins.setBigDecimal(13, r);
                    ins.setString(14, jeType + " " + periodKey);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            audit.record(ctx, "GL_PUSH", "gl_journal_batch", batchId.toString(), null,
                    "{\"je_type\":\"" + jeType + "\",\"dr\":\"" + dr.toPlainString()
                            + "\",\"cr\":\"" + cr.toPlainString() + "\",\"lines\":" + lines.size() + "}",
                    "JE_GENERATION", "journal batch generated",
                    "eim.gl.pushed", batchId.toString(),
                    "{\"period\":\"" + periodKey + "\",\"jeType\":\"" + jeType + "\"}");
            return Response.ok(Map.of("batchId", batchId.toString(), "jeType", jeType,
                    "lines", lines.size(), "totalDr", dr.toPlainString(),
                    "totalCr", cr.toPlainString(), "status", "PENDING")).build();
        } catch (SQLException e) {
            throw new WebApplicationException("JE generation failed: " + e.getMessage(), 500);
        }
    }

    /** FR-907.2: a period cannot close with an unbalanced journal set. */
    @GET
    @Path("/{periodKey}/balance-check")
    @PermitAll
    public Response balanceCheck(@PathParam("bookId") String bookId,
                                 @PathParam("periodKey") String periodKey,
                                 @Context ContainerRequestContext request) throws SQLException {
        ctx(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean balanced = true;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT je_type, subsidiary, currency, sum(dr_amount)::text, sum(cr_amount)::text "
                             + "FROM gl_journal_entry_line l JOIN gl_journal_batch b "
                             + "ON b.batch_id = l.batch_id "
                             + "WHERE b.book_id = CAST(? AS uuid) AND b.period_key = ? "
                             + "GROUP BY je_type, subsidiary, currency ORDER BY je_type")) {
            ps.setString(1, bookId);
            ps.setString(2, periodKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("jeType", rs.getString(1));
                    r.put("subsidiary", rs.getString(2));
                    r.put("currency", rs.getString(3));
                    BigDecimal dr = new BigDecimal(rs.getString(4));
                    BigDecimal cr = new BigDecimal(rs.getString(5));
                    r.put("dr", dr.toPlainString());
                    r.put("cr", cr.toPlainString());
                    r.put("balanced", dr.compareTo(cr) == 0);
                    balanced &= dr.compareTo(cr) == 0;
                    rows.add(r);
                }
            }
        }
        return Response.ok(Map.of("periodKey", periodKey, "balanced", balanced,
                "groups", rows)).build();
    }

    /** FR-907.4: ERP push with per-batch sync status and retry. */
    @POST
    @Path("/batch/{batchId}/push")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response push(@PathParam("batchId") String batchId, JsonObject body,
                         @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver") && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver or comp_admin required")).build();
        }
        String target = body == null ? "NETSUITE" : body.getString("erpTarget", "NETSUITE");
        String fail = body == null ? null : body.getString("simulateFailure", null);
        String next = "FAILED".equals(fail) ? "FAILED" : "EXPORTED";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE gl_journal_batch SET status = ?, erp_target = ?, "
                             + "pushed_at = now(), retry_count = retry_count "
                             + "+ (CASE WHEN gl_journal_batch.status = 'FAILED' THEN 1 ELSE 0 END), "
                             + "last_error = ?, erp_reference = ? "
                             + "WHERE batch_id = CAST(? AS uuid) AND tenant_id = CAST(? AS uuid) "
                             + "AND status IN ('PENDING','EXPORTED','FAILED') RETURNING status")) {
            ps.setString(1, next);
            ps.setString(2, target);
            ps.setString(3, "FAILED".equals(next) ? "simulated ERP rejection (dev hook)" : null);
            ps.setString(4, "FAILED".equals(next) ? null : target + "-" + UUID.randomUUID());
            ps.setString(5, batchId);
            ps.setString(6, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "batch not found or already synced")).build();
                }
                next = rs.getString(1);
            }
        } catch (SQLException e) {
            throw new WebApplicationException("push failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "GL_PUSH", "gl_journal_batch", batchId, null,
                "{\"status\":\"" + next + "\",\"erp\":\"" + target + "\"}",
                "ERP_PUSH", "journal batch pushed to ERP",
                "eim.gl.pushed", batchId,
                "{\"batch\":\"" + batchId + "\",\"status\":\"" + next + "\",\"erp\":\"" + target + "\"}");
        return Response.ok(Map.of("batchId", batchId, "status", next, "erpTarget", target)).build();
    }

    /** Mark an EXPORTED batch as SYNCED (ERP acknowledgement callback). */
    @POST
    @Path("/batch/{batchId}/ack")
    @PermitAll
    @Transactional
    public Response ack(@PathParam("batchId") String batchId,
                        @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver") && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver or comp_admin required")).build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE gl_journal_batch SET status = 'SYNCED', synced_at = now() "
                             + "WHERE batch_id = CAST(? AS uuid) AND status = 'EXPORTED' "
                             + "RETURNING status")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Response.ok(Map.of("batchId", batchId, "status", rs.getString(1))).build()
                        : Response.status(Response.Status.CONFLICT)
                                .entity(Map.of("error", "batch not in EXPORTED")).build();
            }
        } catch (SQLException e) {
            throw new WebApplicationException("ack failed: " + e.getMessage(), 500);
        }
    }

    @GET
    @Path("/{periodKey}")
    @PermitAll
    public List<Map<String, Object>> list(@PathParam("bookId") String bookId,
                                          @PathParam("periodKey") String periodKey,
                                          @Context ContainerRequestContext request) throws SQLException {
        ctx(request);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT batch_id::text, je_type, status, erp_target, erp_reference, "
                             + "retry_count, total_dr::text, total_cr::text, created_by, "
                             + "coalesce(pushed_at::text,''), coalesce(synced_at::text,'') "
                             + "FROM gl_journal_batch WHERE book_id = CAST(? AS uuid) "
                             + "AND period_key = ? ORDER BY je_type")) {
            ps.setString(1, bookId);
            ps.setString(2, periodKey);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("batchId", rs.getString(1));
                    r.put("jeType", rs.getString(2));
                    r.put("status", rs.getString(3));
                    r.put("erpTarget", rs.getString(4));
                    r.put("erpReference", rs.getString(5));
                    r.put("retryCount", rs.getInt(6));
                    r.put("totalDr", rs.getString(7));
                    r.put("totalCr", rs.getString(8));
                    r.put("createdBy", rs.getString(9));
                    out.add(r);
                }
                return out;
            }
        }
    }

    // ------------------------------------------------------------- templates
    // line tuple: scheduleLineId, assetId, ledgerId, transactionId, account, dr, cr

    private List<Object[]> payrollAccrual(Connection c, TenantContext ctx, String bookId,
                                          String periodKey) throws SQLException {
        // DR commission expense / CR accrued payroll, one line pair per ledger entry
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT l.ledger_id::text, coalesce(l.transaction_id::text,''), l.payout_amount "
                        + "FROM commission_ledger_entry l JOIN payout_period p "
                        + "ON p.payout_period_id = l.payout_period_id "
                        + "WHERE p.period_key = ? AND p.tenant_id = CAST(? AS uuid)")) {
            ps.setString(1, periodKey);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ledger = rs.getString(1);
                    String txn = rs.getString(2);
                    BigDecimal amt = rs.getBigDecimal(3);
                    out.add(new Object[]{null, null, ledger, txn.isEmpty() ? null : txn,
                            "6100", amt, BigDecimal.ZERO});
                    out.add(new Object[]{null, null, ledger, txn.isEmpty() ? null : txn,
                            "2100", BigDecimal.ZERO, amt});
                }
            }
        }
        return out;
    }

    private List<Object[]> capitalization(Connection c, TenantContext ctx, String bookId,
                                          String periodKey) throws SQLException {
        // DR deferred cost asset / CR commission expense (contra), per asset
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT a.asset_id::text, a.ledger_id::text, "
                        + "coalesce(a.transaction_id::text,''), a.capitalized_amount "
                        + "FROM capitalized_cost_asset a JOIN ledger_classification lc "
                        + "ON lc.classification_id = a.classification_id "
                        + "JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                        + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                        + "WHERE p.period_key = ? AND a.book_id = CAST(? AS uuid)")) {
            ps.setString(1, periodKey);
            ps.setString(2, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String asset = rs.getString(1);
                    String ledger = rs.getString(2);
                    String txn = rs.getString(3);
                    BigDecimal amt = rs.getBigDecimal(4);
                    out.add(new Object[]{null, asset, ledger, txn.isEmpty() ? null : txn,
                            "2300", amt, BigDecimal.ZERO});
                    out.add(new Object[]{null, asset, ledger, txn.isEmpty() ? null : txn,
                            "6100", BigDecimal.ZERO, amt});
                }
            }
        }
        return out;
    }

    private List<Object[]> amortization(Connection c, TenantContext ctx, String bookId,
                                        String periodKey) throws SQLException {
        // DR amortization expense / CR accumulated amortization, per schedule line
        return scheduleLines(c, bookId, periodKey, "SCHEDULED", "6200", "2310");
    }

    private List<Object[]> impairment(Connection c, TenantContext ctx, String bookId,
                                      String periodKey) throws SQLException {
        // DR impairment expense / CR accumulated amortization
        return scheduleLines(c, bookId, periodKey, "IMPAIRMENT", "6300", "2310");
    }

    private List<Object[]> catchUp(Connection c, TenantContext ctx, String bookId,
                                   String periodKey) throws SQLException {
        // DR amortization expense (catch-up) / CR accumulated amortization
        return scheduleLines(c, bookId, periodKey, "CATCH_UP", "6200", "2310");
    }

    private List<Object[]> scheduleLines(Connection c, String bookId, String periodKey,
                                         String lineType, String drAccount, String crAccount)
            throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.line_id::text, a.asset_id::text, a.ledger_id::text, "
                        + "coalesce(a.transaction_id::text,''), s.amount "
                        + "FROM amortization_schedule_line s "
                        + "JOIN capitalized_cost_asset a ON a.asset_id = s.asset_id "
                        + "WHERE s.book_id = CAST(? AS uuid) AND s.period_key = ? "
                        + "AND s.line_type = ? AND s.amount <> 0")) {
            ps.setString(1, bookId);
            ps.setString(2, periodKey);
            ps.setString(3, lineType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    String asset = rs.getString(2);
                    String ledger = rs.getString(3);
                    String txn = rs.getString(4);
                    BigDecimal amt = rs.getBigDecimal(5).abs();
                    out.add(new Object[]{line, asset, ledger, txn.isEmpty() ? null : txn,
                            drAccount, amt, BigDecimal.ZERO});
                    out.add(new Object[]{line, asset, ledger, txn.isEmpty() ? null : txn,
                            crAccount, BigDecimal.ZERO, amt});
                }
            }
        }
        return out;
    }

    private String bookCurrency(Connection c, String bookId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT functional_currency FROM accounting_book_policy "
                        + "WHERE book_id = CAST(? AS uuid)")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "USD";
            }
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
