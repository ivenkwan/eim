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
 * Contract lifecycle events without mutating closed periods (TG7-ACC-4,
 * FR-903): full churn zeroes only UNPOSTED future schedule lines and posts
 * the remaining unamortized balance as an impairment in the current open
 * period; partial downgrade follows the book's policy (continue vs.
 * pro-rata impairment); retroactive true-ups create a child amortization
 * stream linked to the original contract start with a cumulative catch-up
 * entry in the period of discovery. Posted schedule lines are immutable —
 * enforced by the database trigger (FR-907.3).
 */
@Path("/api/v1/accounting/assets")
@Produces(MediaType.APPLICATION_JSON)
public class LifecycleResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @POST
    @Path("/{assetId}/churn")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response churn(@PathParam("assetId") String assetId, JsonObject body,
                          @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required")).build();
        }
        String openPeriod = body.getString("openPeriodKey", LocalDate.now().withDayOfMonth(1)
                .toString().substring(0, 7));
        try (Connection c = ds.getConnection()) {
            Asset a = loadAsset(c, ctx, assetId);
            BigDecimal unamortized = remaining(c, assetId);
            int zeroed = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE amortization_schedule_line SET amount = 0 "
                            + "WHERE asset_id = CAST(? AS uuid) AND posted = false "
                            + "AND line_type = 'SCHEDULED' AND period_key > ?")) {
                ps.setString(1, assetId);
                ps.setString(2, openPeriod);
                zeroed = ps.executeUpdate();
            }
            // impairment of the remaining balance lands in the CURRENT open period
            UUID lineId = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO amortization_schedule_line (line_id, tenant_id, asset_id, book_id, "
                            + "period_key, period_start, period_end, line_type, amount, posted, "
                            + "posted_period_key) VALUES (?,?,?,?,?,?,?, 'IMPAIRMENT', ?, false, ?) "
                            + "ON CONFLICT (asset_id, period_key, line_type) DO UPDATE SET "
                            + "amount = EXCLUDED.amount")) {
                ps.setObject(1, lineId);
                ps.setObject(2, UUID.fromString(a.tenantId()));
                ps.setObject(3, UUID.fromString(assetId));
                ps.setObject(4, UUID.fromString(a.bookId()));
                ps.setString(5, openPeriod);
                ps.setDate(6, Date.valueOf(LocalDate.parse(openPeriod + "-01")));
                ps.setDate(7, Date.valueOf(LocalDate.parse(openPeriod + "-01")
                        .withDayOfMonth(LocalDate.parse(openPeriod + "-01").lengthOfMonth())));
                ps.setBigDecimal(8, unamortized);
                ps.setString(9, openPeriod);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE capitalized_cost_asset SET status = 'CHURNED' "
                            + "WHERE asset_id = CAST(? AS uuid)")) {
                ps.setString(1, assetId);
                ps.executeUpdate();
            }
            audit.record(ctx, "MANUAL_ADJUSTMENT", "capitalized_cost_asset", assetId,
                    "{\"status\":\"" + a.status() + "\"}",
                    "{\"status\":\"CHURNED\",\"impairment\":\"" + unamortized.toPlainString()
                            + "\",\"future_lines_zeroed\":" + zeroed + "}",
                    "FR903_CHURN", body.getString("comment", "full churn in clawback window"),
                    "eim.accounting.classified", assetId,
                    "{\"asset\":\"" + assetId + "\",\"impairment\":\"" + unamortized + "\"}");
            return Response.ok(Map.of("assetId", assetId, "status", "CHURNED",
                    "impairment", unamortized.toPlainString(),
                    "futureLinesZeroed", zeroed, "period", openPeriod)).build();
        } catch (SQLException e) {
            throw new WebApplicationException("churn failed: " + e.getMessage(), 500);
        }
    }

    @POST
    @Path("/{assetId}/downgrade")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response downgrade(@PathParam("assetId") String assetId, JsonObject body,
                              @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required")).build();
        }
        BigDecimal pct = decimalOf(body, "contractionPct");
        if (pct == null || pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "contractionPct must be in (0,1]")).build();
        }
        String openPeriod = body.getString("openPeriodKey",
                LocalDate.now().withDayOfMonth(1).toString().substring(0, 7));
        try (Connection c = ds.getConnection()) {
            Asset a = loadAsset(c, ctx, assetId);
            String policy;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT downgrade_policy FROM accounting_book_policy "
                            + "WHERE book_id = CAST(? AS uuid)")) {
                ps.setString(1, a.bookId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    policy = rs.getString(1);
                }
            }
            if ("CONTINUE".equals(policy)) {
                return Response.ok(Map.of("assetId", assetId, "policy", "CONTINUE",
                        "action", "amortization continues over remaining life")).build();
            }
            // PRO_RATE_IMPAIR: partial impairment = contraction % × remaining
            BigDecimal remaining = remaining(c, assetId);
            BigDecimal impairment = remaining.multiply(pct).setScale(4, RoundingMode.HALF_EVEN);
            BigDecimal futureScale = BigDecimal.ONE.subtract(pct);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE amortization_schedule_line SET amount = round(amount * ?, 4) "
                            + "WHERE asset_id = CAST(? AS uuid) AND posted = false "
                            + "AND line_type = 'SCHEDULED' AND period_key > ?")) {
                ps.setBigDecimal(1, futureScale);
                ps.setString(2, assetId);
                ps.setString(3, openPeriod);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO amortization_schedule_line (line_id, tenant_id, asset_id, book_id, "
                            + "period_key, period_start, period_end, line_type, amount) "
                            + "VALUES (?,?,?,?,?,?,?, 'IMPAIRMENT', ?) "
                            + "ON CONFLICT (asset_id, period_key, line_type) DO UPDATE SET "
                            + "amount = EXCLUDED.amount")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(a.tenantId()));
                ps.setObject(3, UUID.fromString(assetId));
                ps.setObject(4, UUID.fromString(a.bookId()));
                ps.setString(5, openPeriod);
                ps.setDate(6, Date.valueOf(LocalDate.parse(openPeriod + "-01")));
                ps.setDate(7, Date.valueOf(LocalDate.parse(openPeriod + "-01")
                        .withDayOfMonth(LocalDate.parse(openPeriod + "-01").lengthOfMonth())));
                ps.setBigDecimal(8, impairment);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE capitalized_cost_asset SET status = 'DOWNGRADED' "
                            + "WHERE asset_id = CAST(? AS uuid)")) {
                ps.setString(1, assetId);
                ps.executeUpdate();
            }
            audit.record(ctx, "MANUAL_ADJUSTMENT", "capitalized_cost_asset", assetId,
                    "{\"status\":\"" + a.status() + "\"}",
                    "{\"status\":\"DOWNGRADED\",\"contraction\":\"" + pct.toPlainString()
                            + "\",\"impairment\":\"" + impairment.toPlainString() + "\"}",
                    "FR903_DOWNGRADE", body.getString("comment", "partial downgrade"),
                    "eim.accounting.classified", assetId,
                    "{\"asset\":\"" + assetId + "\",\"impairment\":\"" + impairment + "\"}");
            return Response.ok(Map.of("assetId", assetId, "status", "DOWNGRADED",
                    "policy", policy, "impairment", impairment.toPlainString())).build();
        } catch (SQLException e) {
            throw new WebApplicationException("downgrade failed: " + e.getMessage(), 500);
        }
    }

    /** FR-903.3: retro catch-up as a CHILD stream from the original start. */
    @POST
    @Path("/{assetId}/retro-trueup")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response retroTrueup(@PathParam("assetId") String assetId, JsonObject body,
                                @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required")).build();
        }
        BigDecimal newAmount = decimalOf(body, "additionalAmount");
        if (newAmount == null || newAmount.signum() <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "additionalAmount must be > 0")).build();
        }
        String discoveryPeriod = body.getString("discoveryPeriodKey",
                LocalDate.now().withDayOfMonth(1).toString().substring(0, 7));
        try (Connection c = ds.getConnection()) {
            Asset a = loadAsset(c, ctx, assetId);
            String convention;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT amortization_convention FROM accounting_book_policy "
                            + "WHERE book_id = CAST(? AS uuid)")) {
                ps.setString(1, a.bookId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    convention = rs.getString(1);
                }
            }
            // child asset: same contract window/term, parent linkage
            UUID childId = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO capitalized_cost_asset (asset_id, tenant_id, book_id, "
                            + "classification_id, ledger_id, payee_id, transaction_id, contract_ref, "
                            + "contract_start, contract_end, amortization_months, capitalized_amount, "
                            + "status, parent_asset_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, "
                            + "'ACTIVE', CAST(? AS uuid))")) {
                ps.setObject(1, childId);
                ps.setObject(2, UUID.fromString(a.tenantId()));
                ps.setObject(3, UUID.fromString(a.bookId()));
                ps.setObject(4, UUID.fromString(a.classificationId()));
                ps.setObject(5, UUID.fromString(a.ledgerId()));
                ps.setObject(6, UUID.fromString(a.payeeId()));
                ps.setObject(7, a.transactionId() == null ? null : UUID.fromString(a.transactionId()));
                ps.setString(8, a.contractRef());
                ps.setDate(9, Date.valueOf(a.contractStart()));
                ps.setDate(10, Date.valueOf(a.contractEnd()));
                ps.setInt(11, a.months());
                ps.setBigDecimal(12, newAmount);
                ps.setString(13, assetId);
                ps.executeUpdate();
            }
            // cumulative catch-up: the child's elapsed amortization since the
            // original start posts in the DISCOVERY period (e.g. April catch-up
            // for Jan–Mar elapsed days); the remainder follows the schedule.
            List<AmortizationEngine.ScheduleLine> lines = AmortizationEngine.schedule(
                    newAmount, LocalDate.parse(a.contractStart()), a.months(),
                    AmortizationEngine.Convention.valueOf(convention), null);
            BigDecimal catchUp = BigDecimal.ZERO;
            List<AmortizationEngine.ScheduleLine> future = new ArrayList<>();
            for (AmortizationEngine.ScheduleLine l : lines) {
                if (l.periodKey().compareTo(discoveryPeriod) < 0) {
                    catchUp = catchUp.add(l.amount());
                } else {
                    future.add(l);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO amortization_schedule_line (line_id, tenant_id, asset_id, book_id, "
                            + "period_key, period_start, period_end, line_type, amount) "
                            + "VALUES (?,?,?,?,?,?,?, ?, ?) ON CONFLICT DO NOTHING")) {
                if (catchUp.signum() > 0) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, UUID.fromString(a.tenantId()));
                    ps.setObject(3, childId);
                    ps.setObject(4, UUID.fromString(a.bookId()));
                    ps.setString(5, discoveryPeriod);
                    ps.setDate(6, Date.valueOf(LocalDate.parse(discoveryPeriod + "-01")));
                    ps.setDate(7, Date.valueOf(LocalDate.parse(discoveryPeriod + "-01")
                            .withDayOfMonth(LocalDate.parse(discoveryPeriod + "-01").lengthOfMonth())));
                    ps.setString(8, "CATCH_UP");
                    ps.setBigDecimal(9, catchUp);
                    ps.executeUpdate();
                }
                for (AmortizationEngine.ScheduleLine l : future) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, UUID.fromString(a.tenantId()));
                    ps.setObject(3, childId);
                    ps.setObject(4, UUID.fromString(a.bookId()));
                    ps.setString(5, l.periodKey());
                    ps.setDate(6, Date.valueOf(l.start()));
                    ps.setDate(7, Date.valueOf(l.end()));
                    ps.setString(8, "SCHEDULED");
                    ps.setBigDecimal(9, l.amount());
                    ps.executeUpdate();
                }
            }
            audit.record(ctx, "TRUEUP_POSTING", "capitalized_cost_asset", childId.toString(),
                    null,
                    "{\"parent\":\"" + assetId + "\",\"additional\":\"" + newAmount.toPlainString()
                            + "\",\"catch_up\":\"" + catchUp.toPlainString() + "\"}",
                    "FR903_RETRO_TRUEUP", body.getString("comment", "retro catch-up child stream"),
                    "eim.accounting.classified", childId.toString(),
                    "{\"parent\":\"" + assetId + "\",\"catchUp\":\"" + catchUp + "\"}");
            return Response.ok(Map.of("childAssetId", childId.toString(),
                    "parentAssetId", assetId, "catchUp", catchUp.toPlainString(),
                    "discoveryPeriod", discoveryPeriod)).build();
        } catch (SQLException e) {
            throw new WebApplicationException("retro true-up failed: " + e.getMessage(), 500);
        }
    }

    @GET
    @Path("/{assetId}")
    @PermitAll
    public Response asset(@PathParam("assetId") String assetId,
                          @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        try (Connection c = ds.getConnection()) {
            Asset a = loadAsset(c, ctx, assetId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assetId", assetId);
            out.put("contractRef", a.contractRef());
            out.put("status", a.status());
            out.put("capitalizedAmount", a.capitalizedAmount().toPlainString());
            out.put("fxRate", a.fxRate() == null ? null : a.fxRate().toPlainString());
            out.put("remainingUnamortized", remaining(c, assetId).toPlainString());
            List<Map<String, Object>> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT period_key, line_type, amount::text, posted FROM amortization_schedule_line "
                            + "WHERE asset_id = CAST(? AS uuid) ORDER BY period_key, line_type")) {
                ps.setString(1, assetId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> l = new LinkedHashMap<>();
                        l.put("periodKey", rs.getString(1));
                        l.put("lineType", rs.getString(2));
                        l.put("amount", rs.getString(3));
                        l.put("posted", rs.getBoolean(4));
                        lines.add(l);
                    }
                }
            }
            out.put("schedule", lines);
            return Response.ok(out).build();
        }
    }

    /** JSON numbers or numeric strings (clients may send either). */
    private static BigDecimal decimalOf(JsonObject body, String key) {
        if (body == null || body.isNull(key) || !body.containsKey(key)) {
            return null;
        }
        var v = body.get(key);
        return v instanceof jakarta.json.JsonNumber n ? n.bigDecimalValue()
                : new BigDecimal(body.getString(key));
    }

    // ---------------------------------------------------------------- helpers
    record Asset(String tenantId, String bookId, String classificationId, String ledgerId,
                 String payeeId, String transactionId, String contractRef, String contractStart,
                 String contractEnd, int months, BigDecimal capitalizedAmount, BigDecimal fxRate,
                 String status) {
    }

    private Asset loadAsset(Connection c, TenantContext ctx, String assetId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT tenant_id::text, book_id::text, classification_id::text, ledger_id::text, "
                        + "payee_id::text, transaction_id::text, contract_ref, "
                        + "contract_start::text, contract_end::text, amortization_months, "
                        + "capitalized_amount, fx_rate, status FROM capitalized_cost_asset "
                        + "WHERE asset_id = CAST(? AS uuid) AND tenant_id = CAST(? AS uuid)")) {
            ps.setString(1, assetId);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new WebApplicationException(Response.status(404)
                            .entity(Map.of("error", "unknown asset for tenant")).build());
                }
                return new Asset(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getInt(10),
                        rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getString(13));
            }
        }
    }

    static BigDecimal remaining(Connection c, String assetId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT coalesce(sum(amount),0) FROM amortization_schedule_line "
                        + "WHERE asset_id = CAST(? AS uuid) AND line_type = 'SCHEDULED' "
                        + "AND amount > 0 AND posted = false")) {
            ps.setString(1, assetId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
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
