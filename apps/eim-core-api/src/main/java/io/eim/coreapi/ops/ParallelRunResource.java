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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * Parallel-run harness (TG6-ONB-2..6): shadow statements from the legacy
 * system are uploaded per period, a cent-for-cent diff report is generated
 * against the EIM ledger, discrepancies flow through a triage workflow, and
 * a partner sign-off is only recorded at a 100.0% match (ONB-4) with the
 * evidence hash of the exact diff set. Close-cycle telemetry derives the
 * reconciliation+approval duration per locked period (ONB-6).
 */
@Path("/api/v1/parallel-run")
@Produces(MediaType.APPLICATION_JSON)
public class ParallelRunResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    // ------------------------------------------------------- legacy upload (ONB-2)
    @POST
    @Path("/partners/{partnerId}/periods/{periodKey}/legacy-statement")
    @PermitAll
    @Consumes("text/csv")
    @Transactional
    public Response uploadLegacy(@PathParam("partnerId") String partnerId,
                                 @PathParam("periodKey") String periodKey,
                                 String csv,
                                 @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        requirePartner(ctx, partnerId);
        if (csv == null || csv.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "empty statement")).build();
        }
        int rows = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO legacy_statement (legacy_id, partner_id, period_key, payee_id, "
                             + "legacy_payout, uploaded_by) VALUES (?,CAST(? AS uuid),?,CAST(? AS uuid),?,?) "
                             + "ON CONFLICT (partner_id, period_key, payee_id) "
                             + "DO UPDATE SET legacy_payout = EXCLUDED.legacy_payout, "
                             + "uploaded_by = EXCLUDED.uploaded_by, uploaded_at = now()")) {
            for (String line : csv.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("payee_id") || t.startsWith("#")) {
                    continue; // header or comment
                }
                String[] cols = t.split("[,;]");
                if (cols.length < 2) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "bad line (need payee_id,legacy_payout): " + t)).build();
                }
                try {
                    UUID.fromString(cols[0].trim());
                    new BigDecimal(cols[1].trim());
                } catch (IllegalArgumentException e) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "bad line (invalid uuid or amount): " + t)).build();
                }
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, partnerId);
                ps.setString(3, periodKey);
                ps.setObject(4, UUID.fromString(cols[0].trim()));
                ps.setBigDecimal(5, new BigDecimal(cols[1].trim()));
                ps.setString(6, ctx.actor());
                ps.addBatch();
                rows++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new WebApplicationException("legacy statement load failed: " + e.getMessage(), 500);
        }
        if (rows == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "no data rows")).build();
        }
        audit.record(ctx, "CONNECTOR_RUN", "legacy_statement", partnerId + ":" + periodKey,
                null, "{\"rows\":" + rows + "}",
                "PARALLEL_UPLOAD", "legacy system statement uploaded for parallel run",
                "eim.sync.completed", partnerId + ":" + periodKey,
                "{\"partner\":\"" + partnerId + "\",\"period\":\"" + periodKey
                        + "\",\"legacyRows\":" + rows + "}");
        return Response.ok(Map.of("rows", rows, "periodKey", periodKey)).build();
    }

    // ------------------------------------------------------- diff generation (ONB-3)
    @POST
    @Path("/partners/{partnerId}/periods/{periodKey}/diff")
    @PermitAll
    @Transactional
    public Response generateDiff(@PathParam("partnerId") String partnerId,
                                 @PathParam("periodKey") String periodKey,
                                 @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        requirePartner(ctx, partnerId);
        List<Object[]> joined = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM parallel_diff WHERE partner_id = CAST(? AS uuid) AND period_key = ?")) {
                ps.setString(1, partnerId);
                ps.setString(2, periodKey);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "WITH eim AS (SELECT l.payee_id, sum(l.payout_amount) AS total "
                            + "FROM commission_ledger_entry l "
                            + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                            + "WHERE p.tenant_id = CAST(? AS uuid) AND p.period_key = ? "
                            + "GROUP BY l.payee_id), "
                            + "leg AS (SELECT payee_id, legacy_payout FROM legacy_statement "
                            + "WHERE partner_id = CAST(? AS uuid) AND period_key = ?) "
                            + "SELECT coalesce(e.payee_id, g.payee_id)::text, "
                            + "coalesce(e.total, 0)::text, coalesce(g.legacy_payout, 0)::text "
                            + "FROM eim e FULL OUTER JOIN leg g ON g.payee_id = e.payee_id")) {
                ps.setString(1, ctx.tenantId());
                ps.setString(2, periodKey);
                ps.setString(3, partnerId);
                ps.setString(4, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        joined.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO parallel_diff (diff_id, partner_id, period_key, payee_id, "
                            + "eim_payout, legacy_payout, status) "
                            + "VALUES (?,CAST(? AS uuid),?,CAST(? AS uuid),?,?,?)")) {
                int matched = 0;
                for (Object[] row : joined) {
                    BigDecimal eim = new BigDecimal((String) row[1]);
                    BigDecimal legacy = new BigDecimal((String) row[2]);
                    boolean match = eim.subtract(legacy).movePointRight(2).compareTo(BigDecimal.ZERO) == 0;
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, partnerId);
                    ps.setString(3, periodKey);
                    ps.setObject(4, UUID.fromString((String) row[0]));
                    ps.setBigDecimal(5, eim);
                    ps.setBigDecimal(6, legacy);
                    ps.setString(7, match ? "MATCH" : "DISCREPANT");
                    ps.addBatch();
                    if (match) {
                        matched++;
                    }
                }
                ps.executeBatch();
                audit.record(ctx, "CALCULATION_RUN", "parallel_diff", partnerId + ":" + periodKey,
                        null,
                        "{\"payees\":" + joined.size() + ",\"matched\":" + matched + "}",
                        "PARALLEL_DIFF", "cent-for-cent diff generated",
                        "eim.calc.run.completed", partnerId + ":" + periodKey,
                        "{\"partner\":\"" + partnerId + "\",\"period\":\"" + periodKey
                                + "\",\"payees\":" + joined.size() + ",\"matched\":" + matched + "}");
                return Response.ok(Map.of("periodKey", periodKey,
                        "payees", joined.size(), "matched", matched,
                        "discrepant", joined.size() - matched)).build();
            }
        } catch (SQLException e) {
            throw new WebApplicationException("diff generation failed: " + e.getMessage(), 500);
        }
    }

    // ------------------------------------------------------- report (ONB-3)
    @GET
    @Path("/partners/{partnerId}/periods/{periodKey}")
    @PermitAll
    public Response report(@PathParam("partnerId") String partnerId,
                           @PathParam("periodKey") String periodKey,
                           @Context ContainerRequestContext request) throws SQLException {
        ctx(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT diff_id::text, payee_id::text, eim_payout::text, legacy_payout::text, "
                             + "delta_cents::text, status, coalesce(resolution_note,''), "
                             + "coalesce(resolved_by,'') FROM parallel_diff "
                             + "WHERE partner_id = CAST(? AS uuid) AND period_key = ? "
                             + "ORDER BY status DESC, payee_id")) {
            ps.setString(1, partnerId);
            ps.setString(2, periodKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("diffId", rs.getString(1));
                    row.put("payeeId", rs.getString(2));
                    row.put("eimPayout", rs.getString(3));
                    row.put("legacyPayout", rs.getString(4));
                    row.put("deltaCents", rs.getString(5));
                    row.put("status", rs.getString(6));
                    row.put("resolutionNote", rs.getString(7));
                    row.put("resolvedBy", rs.getString(8));
                    rows.add(row);
                    if ("MATCH".equals(rs.getString(6))) {
                        matched++;
                    }
                }
            }
        }
        int payees = rows.size();
        BigDecimal rate = payees == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(matched * 10000L / payees).movePointLeft(2);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodKey", periodKey);
        body.put("payees", payees);
        body.put("matched", matched);
        body.put("matchRate", rate.toPlainString());
        body.put("signoff", signoffState(partnerId, periodKey));
        body.put("rows", rows);
        return Response.ok(body).build();
    }

    // ------------------------------------------------------- triage (ONB-3)
    @POST
    @Path("/diffs/{diffId}/resolve")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response resolve(@PathParam("diffId") String diffId, JsonObject body,
                            @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String resolution = body == null ? null : body.getString("resolution", null);
        String note = body == null ? null : body.getString("note", null);
        if (!"RESOLVED_ADJUSTED".equals(resolution) && !"RESOLVED_EXPLAINED".equals(resolution)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "resolution must be RESOLVED_ADJUSTED or RESOLVED_EXPLAINED")).build();
        }
        if (note == null || note.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "note is mandatory for discrepancy triage")).build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE parallel_diff SET status = ?, resolution_note = ?, resolved_by = ?, "
                             + "resolved_at = now() WHERE diff_id = CAST(? AS uuid) "
                             + "AND status = 'DISCREPANT'")) {
            ps.setString(1, resolution);
            ps.setString(2, note);
            ps.setString(3, ctx.actor());
            ps.setString(4, diffId);
            if (ps.executeUpdate() == 0) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "diff row not found or not DISCREPANT")).build();
            }
        } catch (SQLException e) {
            throw new WebApplicationException("triage failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "APPROVAL", "parallel_diff", diffId,
                "{\"status\":\"DISCREPANT\"}", "{\"status\":\"" + resolution + "\"}",
                "PARALLEL_TRIAGE", note,
                "eim.dispute.updated", diffId,
                "{\"diff\":\"" + diffId + "\",\"resolution\":\"" + resolution + "\"}");
        return Response.ok(Map.of("diffId", diffId, "status", resolution)).build();
    }

    // ------------------------------------------------------- sign-off (ONB-4)
    @POST
    @Path("/partners/{partnerId}/periods/{periodKey}/signoff")
    @PermitAll
    @Transactional
    public Response signoff(@PathParam("partnerId") String partnerId,
                            @PathParam("periodKey") String periodKey,
                            @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver required to sign off a parallel period"))
                    .build();
        }
        requirePartner(ctx, partnerId);
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM parallel_signoff WHERE partner_id = CAST(? AS uuid) "
                            + "AND period_key = ?")) {
                ps.setString(1, partnerId);
                ps.setString(2, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(Map.of("error", "period already signed off")).build();
                    }
                }
            }
            int payees;
            int matched;
            StringBuilder evidence = new StringBuilder();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payee_id::text, eim_payout::text, legacy_payout::text, status "
                            + "FROM parallel_diff WHERE partner_id = CAST(? AS uuid) AND period_key = ? "
                            + "ORDER BY payee_id")) {
                ps.setString(1, partnerId);
                ps.setString(2, periodKey);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> lines = new ArrayList<>();
                    while (rs.next()) {
                        lines.add(rs.getString(1) + "|" + rs.getString(2) + "|"
                                + rs.getString(3) + "|" + rs.getString(4));
                    }
                    payees = lines.size();
                    matched = (int) lines.stream().filter(l -> l.endsWith("|MATCH")).count();
                    for (String l : lines) {
                        evidence.append(l).append('\n');
                    }
                }
            }
            if (payees == 0) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "no diff generated for this period — run the diff first"))
                        .build();
            }
            if (matched != payees) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "sign-off refused: match rate "
                                + matched + "/" + payees + " — 100.0% cent-for-cent required (ONB-4)"))
                                .build();
            }
            String hash = sha256(evidence.toString());
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO parallel_signoff (signoff_id, partner_id, period_key, payees, "
                            + "matched, match_rate, signed_off_by, evidence_hash) "
                            + "VALUES (?,CAST(? AS uuid),?,?,?,?,?,?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, partnerId);
                ps.setString(3, periodKey);
                ps.setInt(4, payees);
                ps.setInt(5, matched);
                ps.setBigDecimal(6, new BigDecimal("100.0000"));
                ps.setString(7, ctx.actor());
                ps.setString(8, hash);
                ps.executeUpdate();
            }
            audit.record(ctx, "APPROVAL", "parallel_signoff", partnerId + ":" + periodKey,
                    null,
                    "{\"payees\":" + payees + ",\"match_rate\":\"100.0000\","
                            + "\"evidence_hash\":\"" + hash + "\"}",
                    "PARALLEL_SIGNOFF", "cent-for-cent sign-off recorded",
                    "eim.parallel.signedoff", partnerId + ":" + periodKey,
                    "{\"partner\":\"" + partnerId + "\",\"period\":\"" + periodKey
                            + "\",\"matchRate\":\"100.0000\",\"evidenceHash\":\"" + hash + "\"}");
            return Response.ok(Map.of("periodKey", periodKey, "payees", payees,
                    "matchRate", "100.0000", "evidenceHash", hash,
                    "signedOffBy", ctx.actor())).build();
        } catch (SQLException e) {
            throw new WebApplicationException("sign-off failed: " + e.getMessage(), 500);
        }
    }

    // ------------------------------------------------------- telemetry (ONB-6)
    @GET
    @Path("/close-cycle")
    @PermitAll
    public List<Map<String, Object>> closeCycle(@Context ContainerRequestContext request) throws SQLException {
        TenantContext caller = ctx(request);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT p.period_key, p.ends_on::text, p.locked_at::text, "
                             + "(SELECT count(*) FROM generate_series(p.ends_on, p.locked_at::date, "
                             + "interval '1 day') d WHERE extract(isodow FROM d) < 6) "
                             + "FROM payout_period p WHERE p.tenant_id = CAST(? AS uuid) "
                             + "AND p.status = 'LOCKED' AND p.locked_at IS NOT NULL ORDER BY p.ends_on")) {
            ps.setString(1, caller.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("periodKey", rs.getString(1));
                    row.put("periodEnd", rs.getString(2));
                    row.put("lockedAt", rs.getString(3));
                    row.put("closeBusinessDays", rs.getLong(4));
                    row.put("withinSla", rs.getLong(4) <= 3);
                    out.add(row);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers
    private String signoffState(String partnerId, String periodKey) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT evidence_hash FROM parallel_signoff "
                             + "WHERE partner_id = CAST(? AS uuid) AND period_key = ?")) {
            ps.setString(1, partnerId);
            ps.setString(2, periodKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void requirePartner(TenantContext ctx, String partnerId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM partner_tenant WHERE partner_id = CAST(? AS uuid) "
                             + "AND tenant_id = CAST(? AS uuid)")) {
            ps.setString(1, partnerId);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new WebApplicationException(Response.status(404)
                            .entity(Map.of("error", "unknown partner for tenant")).build());
                }
            }
        } catch (SQLException e) {
            throw new WebApplicationException("partner lookup failed: " + e.getMessage(), 500);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
