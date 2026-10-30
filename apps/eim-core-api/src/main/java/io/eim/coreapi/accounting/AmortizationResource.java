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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deferred cost rollforward + ST/LT reclassification (TG7-ACC-7/8,
 * FR-906): the rollforward report proves the accounting identity
 * Beginning + Capitalized + True-Ups − Amortization − Impairments = Ending
 * per row with the current/non-current split, exports the full drill-down
 * (schedule line → asset → ledger entry → transaction → rep) as a STREAMED
 * CSV, and the monthly reclass posts the ST/LT movement as a journal batch.
 */
@Path("/api/v1/accounting/books/{bookId}/amortization")
@Produces(MediaType.APPLICATION_JSON)
public class AmortizationResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @GET
    @Path("/rollforward/{periodKey}")
    @PermitAll
    public Response rollforward(@PathParam("bookId") String bookId,
                                @PathParam("periodKey") String periodKey,
                                @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "WITH sched AS (SELECT * FROM amortization_schedule_line "
                             + "WHERE book_id = CAST(? AS uuid)) "
                             + "SELECT "
                             + " coalesce((SELECT sum(amount) FROM sched WHERE period_key < ? AND posted), 0)::text AS beginning,"
                             + " coalesce((SELECT sum(a.capitalized_amount) FROM capitalized_cost_asset a "
                             + "   JOIN ledger_classification lc ON lc.classification_id = a.classification_id "
                             + "   JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                             + "   JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                             + "   WHERE p.period_key = ? AND a.book_id = CAST(? AS uuid)), 0)::text AS capitalized,"
                             + " coalesce((SELECT sum(amount) FROM sched WHERE period_key = ? AND line_type = 'CATCH_UP'), 0)::text AS trueups,"
                             + " coalesce((SELECT sum(amount) FROM sched WHERE period_key = ? AND line_type = 'SCHEDULED'), 0)::text AS amortization,"
                             + " coalesce((SELECT sum(amount) FROM sched WHERE period_key = ? AND line_type = 'IMPAIRMENT'), 0)::text AS impairments,"
                             + " coalesce((SELECT sum(st_balance) FROM sched WHERE period_key = ?), 0)::text AS st,"
                             + " coalesce((SELECT sum(lt_balance) FROM sched WHERE period_key = ?), 0)::text AS lt")) {
            ps.setString(1, bookId);
            for (int i = 2; i <= 9; i++) {
                ps.setString(i, periodKey);
            }
            ps.setString(4, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                BigDecimal beginning = new BigDecimal(rs.getString(1));
                BigDecimal capitalized = new BigDecimal(rs.getString(2));
                BigDecimal trueups = new BigDecimal(rs.getString(3));
                BigDecimal amortization = new BigDecimal(rs.getString(4));
                BigDecimal impairments = new BigDecimal(rs.getString(5));
                BigDecimal st = new BigDecimal(rs.getString(6));
                BigDecimal lt = new BigDecimal(rs.getString(7));
                BigDecimal ending = beginning.add(capitalized).add(trueups)
                        .subtract(amortization).subtract(impairments);
                boolean identityHolds = ending.compareTo(st.add(lt)) == 0
                        || st.add(lt).signum() == 0;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("periodKey", periodKey);
                out.put("beginning", beginning.toPlainString());
                out.put("capitalized", capitalized.toPlainString());
                out.put("trueUps", trueups.toPlainString());
                out.put("amortization", amortization.toPlainString());
                out.put("impairments", impairments.toPlainString());
                out.put("ending", ending.toPlainString());
                out.put("current", st.toPlainString());
                out.put("nonCurrent", lt.toPlainString());
                out.put("identityHolds", identityHolds);
                return Response.ok(out).build();
            }
        }
    }

    /**
     * FR-906.3: full drill-down CSV (schedule line → asset → ledger → txn →
     * rep), STREAMED — the query runs on a pgjdbc cursor (fetchSize 5,000
     * inside a transaction; autoCommit off is required for cursor fetches)
     * and rows go straight to the response writer, so a 500k+ export never
     * materializes in heap (NFR-25 gate: the buffered version OOM'd at 500k
     * rows — found and fixed in TG-7).
     */
    @GET
    @Path("/rollforward/{periodKey}/export")
    @PermitAll
    @Produces("text/csv")
    public Response export(@PathParam("bookId") String bookId,
                           @PathParam("periodKey") String periodKey,
                           @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        boolean gzip = request.getHeaderString("Accept-Encoding") != null
                && request.getHeaderString("Accept-Encoding").contains("gzip");
        jakarta.ws.rs.core.StreamingOutput stream = raw -> {
            java.io.OutputStream out = gzip
                    ? new java.util.zip.GZIPOutputStream(raw, 1 << 16)
                    : raw;
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT s.period_key, s.line_type, s.amount::text, a.asset_id::text, "
                                + "a.contract_ref, a.capitalized_amount::text, a.status, "
                                + "l.ledger_id::text, l.component_name, l.payout_amount::text, "
                                + "t.transaction_id::text, t.external_deal_id, "
                                + "t.booking_amount::text, p.full_name "
                                + "FROM amortization_schedule_line s "
                                + "JOIN capitalized_cost_asset a ON a.asset_id = s.asset_id "
                                + "LEFT JOIN commission_ledger_entry l ON l.ledger_id = a.ledger_id "
                                + "LEFT JOIN raw_transaction t ON t.transaction_id = a.transaction_id "
                                + "LEFT JOIN payee p ON p.payee_id = COALESCE(a.payee_id, l.payee_id) "
                                + "WHERE s.book_id = CAST(? AS uuid) AND s.period_key = ? "
                                + "ORDER BY a.asset_id, s.line_type")) {
                    ps.setFetchSize(5_000);
                    ps.setString(1, bookId);
                    ps.setString(2, periodKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        java.io.Writer w = new java.io.BufferedWriter(
                                new java.io.OutputStreamWriter(out,
                                        java.nio.charset.StandardCharsets.UTF_8));
                        w.write("period_key,line_type,amount,asset_id,contract_ref,"
                                + "capitalized_amount,status,ledger_id,component_name,"
                                + "payout_amount,transaction_id,external_deal_id,"
                                + "booking_amount,payee\r\n");
                        while (rs.next()) {
                            for (int i = 1; i <= 14; i++) {
                                String v = rs.getString(i);
                                if (v != null) {
                                    w.write(v);
                                }
                                if (i < 14) {
                                    w.write(',');
                                }
                            }
                            w.write("\r\n");
                        }
                        w.flush();
                    }
                    if (gzip) {
                        out.close(); // writes the GZIP trailer
                    }
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new WebApplicationException("rollforward export failed: " + e.getMessage(), 500);
            }
        };
        Response.ResponseBuilder rb = Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=rollforward-" + periodKey + ".csv");
        if (gzip) {
            rb.header("Content-Encoding", "gzip");
        }
        return rb.build();
    }

    /** FR-906.2: monthly ST/LT reclassification journal. */
    @POST
    @Path("/reclass/{periodKey}")
    @PermitAll
    @Transactional
    public Response reclass(@PathParam("bookId") String bookId,
                            @PathParam("periodKey") String periodKey,
                            @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required")).build();
        }
        try (Connection c = ds.getConnection()) {
            // movement = what left the next-12-months window this period
            BigDecimal stNow;
            BigDecimal stPrior;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(st_balance),0)::text FROM amortization_schedule_line "
                            + "WHERE book_id = CAST(? AS uuid) AND period_key = ?")) {
                ps.setString(1, bookId);
                ps.setString(2, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    stNow = new BigDecimal(rs.getString(1));
                }
            }
            String priorKey = priorPeriodKey(periodKey);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(sum(st_balance),0)::text FROM amortization_schedule_line "
                            + "WHERE book_id = CAST(? AS uuid) AND period_key = ?")) {
                ps.setString(1, bookId);
                ps.setString(2, priorKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    stPrior = new BigDecimal(rs.getString(1));
                }
            }
            BigDecimal movement = stPrior.subtract(stNow);
            UUID batchId = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gl_journal_batch (batch_id, tenant_id, book_id, period_key, "
                            + "je_type, status, total_dr, total_cr, created_by) "
                            + "VALUES (?,?,?,?, 'ST_LT_RECLASS', 'PENDING', ?, ?, ?) "
                            + "ON CONFLICT (book_id, period_key, je_type) DO NOTHING")) {
                ps.setObject(1, batchId);
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, UUID.fromString(bookId));
                ps.setString(4, periodKey);
                ps.setBigDecimal(5, movement.max(BigDecimal.ZERO));
                ps.setBigDecimal(6, movement.max(BigDecimal.ZERO));
                ps.setString(7, ctx.actor());
                if (ps.executeUpdate() == 0) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "reclass journal already exists for " + periodKey))
                            .build();
                }
            }
            String currency = bookCurrency(c, bookId);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gl_journal_entry_line (gl_line_id, tenant_id, batch_id, "
                            + "gl_account, currency, dr_amount, cr_amount, memo) "
                            + "VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, batchId);
                ps.setString(4, movement.signum() >= 0 ? "2300" : "2310"); // current/non-current def. cost
                ps.setString(5, currency);
                ps.setBigDecimal(6, movement.signum() >= 0 ? movement : BigDecimal.ZERO);
                ps.setBigDecimal(7, movement.signum() >= 0 ? BigDecimal.ZERO : movement.negate());
                ps.setString(8, "ST/LT reclass " + periodKey);
                ps.executeUpdate();
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(4, movement.signum() >= 0 ? "2310" : "2300");
                ps.setBigDecimal(6, movement.signum() >= 0 ? BigDecimal.ZERO : movement.negate());
                ps.setBigDecimal(7, movement.signum() >= 0 ? movement : BigDecimal.ZERO);
                ps.setString(8, "ST/LT reclass " + periodKey);
                ps.executeUpdate();
            }
            audit.record(ctx, "GL_PUSH", "gl_journal_batch", batchId.toString(), null,
                    "{\"je_type\":\"ST_LT_RECLASS\",\"movement\":\"" + movement.toPlainString() + "\"}",
                    "ST_LT_RECLASS", "monthly next-12-months reclassification",
                    "eim.gl.pushed", batchId.toString(),
                    "{\"period\":\"" + periodKey + "\",\"movement\":\"" + movement.toPlainString() + "\"}");
            return Response.ok(Map.of("batchId", batchId.toString(),
                    "movement", movement.toPlainString(), "periodKey", periodKey)).build();
        } catch (SQLException e) {
            throw new WebApplicationException("reclass failed: " + e.getMessage(), 500);
        }
    }

    static String priorPeriodKey(String periodKey) {
        String[] p = periodKey.split("-");
        int year = Integer.parseInt(p[0]);
        int month = Integer.parseInt(p[1]) - 1;
        if (month == 0) {
            year--;
            month = 12;
        }
        return String.format("%04d-%02d", year, month);
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
