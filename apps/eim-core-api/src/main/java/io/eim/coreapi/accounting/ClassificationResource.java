package io.eim.coreapi.accounting;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On period lock, classify every ledger entry per book against the book's
 * versioned DMN decision table (TG7-ACC-2, FR-901): EXPENSE rows record the
 * reason; CAPITALIZE rows become capitalized-cost assets — fringe applied per
 * policy, FX locked at the historical rate from the source transaction
 * (FR-905) — with their amortization schedule generated under the book's
 * convention (FR-902). The pre-close completeness gate (FR-908) reports the
 * classified share and itemizes exceptions.
 */
@Path("/api/v1/accounting/books/{bookId}/classification")
@Produces(MediaType.APPLICATION_JSON)
public class ClassificationResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @Inject
    DmnClassifier classifier;

    @POST
    @Path("/classify/{periodKey}")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response classify(@PathParam("bookId") String bookId,
                             @PathParam("periodKey") String periodKey,
                             @jakarta.ws.rs.QueryParam("monthsHint") Integer monthsHintParam,
                             @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver") && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver or comp_admin required")).build();
        }
        int monthsHint = monthsHintParam == null || monthsHintParam <= 0 ? 24 : monthsHintParam;
        try (Connection c = ds.getConnection()) {
            Book book = loadBook(c, ctx, bookId);
            UUID periodId;
            String status;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payout_period_id, status FROM payout_period "
                            + "WHERE period_key = ? AND tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, periodKey);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown period " + periodKey)).build();
                    }
                    periodId = (UUID) rs.getObject(1);
                    status = rs.getString(2);
                }
            }
            if (!"LOCKED".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "classification runs on locked periods only "
                                + "(FR-901): " + periodKey + " is " + status)).build();
            }

            List<Object[]> entries = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT l.ledger_id::text, l.payee_id::text, l.transaction_id::text, "
                            + "l.component_name, l.entry_type, l.payout_amount::text, "
                            + "coalesce(p.role_title,'AE'), t.transaction_date, "
                            + "t.currency_code, t.fx_rate_applied::text "
                            + "FROM commission_ledger_entry l "
                            + "JOIN payout_period pp ON pp.payout_period_id = l.payout_period_id "
                            + "LEFT JOIN payee p ON p.payee_id = l.payee_id "
                            + "LEFT JOIN raw_transaction t ON t.transaction_id = l.transaction_id "
                            + "WHERE l.payout_period_id = ? AND pp.tenant_id = CAST(? AS uuid)")) {
                ps.setObject(1, periodId);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                rs.getDate(8), rs.getString(9), rs.getString(10)});
                    }
                }
            }
            if (entries.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "no ledger entries for " + periodKey)).build();
            }

            int capitalized = 0;
            int expensed = 0;
            BigDecimal capTotal = BigDecimal.ZERO;
            for (Object[] e : entries) {
                String ledgerId = (String) e[0];
                DmnClassifier.Decision d = classifier.classify(book.dmn,
                        (String) e[3], (String) e[4], (String) e[6], monthsHint);
                UUID classificationId = UUID.randomUUID();
                int months = d.months() == null ? monthsHint : d.months();
                LocalDate txnDate = e[7] == null ? LocalDate.now() : ((Date) e[7]).toLocalDate();
                LocalDate contractStart = txnDate.plusDays(1); // amortization starts after booking
                LocalDate contractEnd = contractStart.plusMonths(months).minusDays(1);
                BigDecimal payout = new BigDecimal((String) e[5]);
                BigDecimal absPayout = payout.abs();

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ledger_classification (classification_id, tenant_id, book_id, "
                                + "ledger_id, classification, reason_code, fringe_amount, "
                                + "amortization_months, contract_start, contract_end, dmn_rule_id, "
                                + "dmn_version, classified_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,? ,?) "
                                + "ON CONFLICT (book_id, ledger_id) DO UPDATE SET "
                                + "classification = EXCLUDED.classification, "
                                + "reason_code = EXCLUDED.reason_code, "
                                + "fringe_amount = EXCLUDED.fringe_amount, "
                                + "amortization_months = EXCLUDED.amortization_months, "
                                + "contract_start = EXCLUDED.contract_start, "
                                + "contract_end = EXCLUDED.contract_end, "
                                + "dmn_version = EXCLUDED.dmn_version, "
                                + "classified_by = EXCLUDED.classified_by, "
                                + "classified_at = now() RETURNING classification_id::text")) {
                    ps.setObject(1, classificationId);
                    ps.setObject(2, UUID.fromString(ctx.tenantId()));
                    ps.setObject(3, UUID.fromString(bookId));
                    ps.setObject(4, UUID.fromString(ledgerId));
                    ps.setString(5, d.classification());
                    ps.setString(6, d.reasonCode());
                    BigDecimal fringeAmount = "CAPITALIZE".equals(d.classification())
                            && !"FRINGE_RATE".equals(d.reasonCode())
                            ? absPayout.multiply(book.fringeRate).setScale(4, RoundingMode.HALF_EVEN)
                            : BigDecimal.ZERO;
                    ps.setBigDecimal(7, fringeAmount);
                    ps.setInt(8, months);
                    ps.setDate(9, Date.valueOf(contractStart));
                    ps.setDate(10, Date.valueOf(contractEnd));
                    ps.setString(11, d.reasonCode());
                    ps.setInt(12, book.dmnVersion);
                    ps.setString(13, ctx.actor());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        classificationId = UUID.fromString(rs.getString(1));
                    }
                }

                if ("CAPITALIZE".equals(d.classification())) {
                    capitalized++;
                    BigDecimal capitalizedAmount = absPayout
                            .add("FRINGE_RATE".equals(d.reasonCode()) ? BigDecimal.ZERO
                                    : absPayout.multiply(book.fringeRate).setScale(4, RoundingMode.HALF_EVEN))
                            .setScale(4, RoundingMode.HALF_EVEN);
                    capTotal = capTotal.add(capitalizedAmount);
                    createAssetWithSchedule(c, ctx, book, classificationId, ledgerId, e,
                            capitalizedAmount, months, contractStart, contractEnd);
                } else {
                    expensed++;
                }
            }

            audit.record(ctx, "CALCULATION_RUN", "ledger_classification",
                    bookId + ":" + periodKey, null,
                    "{\"entries\":" + entries.size() + ",\"capitalized\":" + capitalized
                            + ",\"expensed\":" + expensed + ",\"capitalized_amount\":\"" + capTotal
                            + "\",\"dmn_version\":" + book.dmnVersion + "}",
                    "ASC606_CLASSIFICATION", "period classified against book decision table",
                    "eim.accounting.classified", bookId + ":" + periodKey,
                    "{\"book\":\"" + book.bookCode + "\",\"period\":\"" + periodKey
                            + "\",\"capitalized\":" + capitalized + ",\"expensed\":" + expensed + "}");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("periodKey", periodKey);
            out.put("bookCode", book.bookCode);
            out.put("entries", entries.size());
            out.put("capitalized", capitalized);
            out.put("expensed", expensed);
            out.put("capitalizedAmount", capTotal.toPlainString());
            out.put("dmnVersion", book.dmnVersion);
            return Response.ok(out).build();
        } catch (SQLException e) {
            throw new WebApplicationException("classification failed: " + e.getMessage(), 500);
        }
    }

    /** Pre-close completeness gate (FR-908): 100% classified or itemized exceptions. */
    @GET
    @Path("/completeness/{periodKey}")
    @PermitAll
    public Response completeness(@PathParam("bookId") String bookId,
                                 @PathParam("periodKey") String periodKey,
                                 @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        try (Connection c = ds.getConnection()) {
            long total;
            long classified;
            List<String> exceptions = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM commission_ledger_entry l "
                            + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                            + "WHERE p.period_key = ? AND p.tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, periodKey);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM ledger_classification lc "
                            + "JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                            + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                            + "WHERE p.period_key = ? AND lc.book_id = CAST(? AS uuid)")) {
                ps.setString(1, periodKey);
                ps.setString(2, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    classified = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT l.ledger_id::text FROM commission_ledger_entry l "
                            + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                            + "WHERE p.period_key = ? AND p.tenant_id = CAST(? AS uuid) "
                            + "AND NOT EXISTS (SELECT 1 FROM ledger_classification lc "
                            + "WHERE lc.ledger_id = l.ledger_id AND lc.book_id = CAST(? AS uuid)) "
                            + "ORDER BY l.ledger_id LIMIT 50")) {
                ps.setString(1, periodKey);
                ps.setString(2, ctx.tenantId());
                ps.setString(3, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        exceptions.add(rs.getString(1));
                    }
                }
            }
            boolean complete = total > 0 && classified == total;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("periodKey", periodKey);
            out.put("total", total);
            out.put("classified", classified);
            out.put("complete", complete);
            out.put("unclassified", exceptions);
            return Response.ok(out).build();
        }
    }

    // ---------------------------------------------------------------- helpers
    record Book(String bookCode, String currency, String convention, BigDecimal fringeRate,
                String dmn, int dmnVersion) {
    }

    private Book loadBook(Connection c, TenantContext ctx, String bookId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT book_code, functional_currency, amortization_convention, fringe_rate, "
                        + "classification_dmn, classification_dmn_version "
                        + "FROM accounting_book_policy WHERE book_id = CAST(? AS uuid) "
                        + "AND tenant_id = CAST(? AS uuid)")) {
            ps.setString(1, bookId);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new WebApplicationException(Response.status(404)
                            .entity(Map.of("error", "unknown book for tenant")).build());
                }
                return new Book(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getInt(6));
            }
        }
    }

    private void createAssetWithSchedule(Connection c, TenantContext ctx, Book book,
                                         UUID classificationId, String ledgerId, Object[] e,
                                         BigDecimal capitalizedAmount, int months,
                                         LocalDate start, LocalDate end) throws SQLException {
        UUID assetId = UUID.randomUUID();
        String contractRef = e[2] != null ? e[2].toString() : ledgerId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO capitalized_cost_asset (asset_id, tenant_id, book_id, "
                        + "classification_id, ledger_id, payee_id, transaction_id, contract_ref, "
                        + "contract_start, contract_end, amortization_months, source_currency, "
                        + "source_amount, fx_rate, capitalized_amount) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, assetId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setObject(3, UUID.fromString(bookIdOf(classificationId, c)));
            ps.setObject(4, classificationId);
            ps.setObject(5, UUID.fromString(ledgerId));
            ps.setObject(6, e[1] == null ? null : UUID.fromString((String) e[1]));
            ps.setObject(7, e[2] == null ? null : UUID.fromString((String) e[2]));
            ps.setString(8, contractRef);
            ps.setDate(9, Date.valueOf(start));
            ps.setDate(10, Date.valueOf(end));
            ps.setInt(11, months);
            ps.setString(12, (String) e[8]);
            ps.setBigDecimal(13, e[5] == null ? null : new BigDecimal((String) e[5]));
            ps.setBigDecimal(14, e[9] == null ? null : new BigDecimal((String) e[9]));
            ps.setBigDecimal(15, capitalizedAmount);
            ps.executeUpdate();
        }
        List<AmortizationEngine.POWeight> weights = List.of();
        if ("PO_WEIGHTED".equals(book.convention())) {
            weights = loadPoWeights(c, ctx, contractRef);
        }
        List<AmortizationEngine.ScheduleLine> lines = AmortizationEngine.schedule(
                capitalizedAmount, start, months,
                AmortizationEngine.Convention.valueOf(book.convention()), weights);
        String scheduleBookId = bookIdOf(classificationId, c);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO amortization_schedule_line (line_id, tenant_id, asset_id, book_id, "
                        + "period_key, period_start, period_end, line_type, amount, st_balance, "
                        + "lt_balance) VALUES (?,?,?,?,?,?,?,?,?,?,0) "
                        + "ON CONFLICT (asset_id, period_key, line_type) DO NOTHING")) {
            for (AmortizationEngine.ScheduleLine l : lines) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, assetId);
                ps.setObject(4, UUID.fromString(scheduleBookId));
                ps.setString(5, l.periodKey());
                ps.setDate(6, Date.valueOf(l.start()));
                ps.setDate(7, Date.valueOf(l.end()));
                ps.setString(8, "SCHEDULED");
                ps.setBigDecimal(9, l.amount());
                ps.setInt(10, 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        recomputeStLt(c, assetId);
    }

    private List<AmortizationEngine.POWeight> loadPoWeights(Connection c, TenantContext ctx,
                                                            String contractRef) throws SQLException {
        List<AmortizationEngine.POWeight> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT period_key, revenue_amount FROM erp_revenue_event "
                        + "WHERE tenant_id = CAST(? AS uuid) AND contract_ref = ? "
                        + "ORDER BY period_key")) {
            ps.setString(1, ctx.tenantId());
            ps.setString(2, contractRef);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AmortizationEngine.POWeight(rs.getString(1),
                            rs.getBigDecimal(2)));
                }
            }
        }
        return out;
    }

    /** Recompute current/non-current balances per line (FR-906.2). */
    void recomputeStLt(Connection c, UUID assetId) throws SQLException {
        List<AmortizationEngine.ScheduleLine> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT period_key, amount FROM amortization_schedule_line "
                        + "WHERE asset_id = ? AND line_type = 'SCHEDULED' AND amount > 0 "
                        + "ORDER BY period_key")) {
            ps.setObject(1, assetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new AmortizationEngine.ScheduleLine(rs.getString(1),
                            null, null, rs.getBigDecimal(2)));
                }
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        String asOf = lines.get(0).periodKey();
        BigDecimal[] split = AmortizationEngine.stLtSplit(lines, asOf);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE amortization_schedule_line SET st_balance = ?, lt_balance = ? "
                        + "WHERE asset_id = ? AND period_key = ? AND line_type = 'SCHEDULED'")) {
            ps.setBigDecimal(1, split[0]);
            ps.setBigDecimal(2, split[1]);
            ps.setObject(3, assetId);
            ps.setString(4, asOf);
            ps.executeUpdate();
        }
    }

    private String bookIdOf(UUID classificationId, Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT book_id::text FROM ledger_classification "
                        + "WHERE classification_id = ?")) {
            ps.setObject(1, classificationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
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
