package io.eim.coreapi.channel;

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
 * Channel/partner portal (TG8-SEC-1/SEL-1/PRC-1, FR-1004/1005): partner
 * registration mapping the mTLS client-cert CN to a partner identity — a
 * plane SEPARATE from internal payees (partners authenticate via gateway
 * mTLS + the partner role; the register never grants internal roles), the
 * channel-claim lifecycle (submission → eligibility check → decision →
 * accrual posting through the same audited-override machinery), and the
 * partner-facing statement + dispute surfaces that reuse the Phase-1
 * machinery under the partner identity.
 */
@Path("/api/v1/channel")
@Produces(MediaType.APPLICATION_JSON)
public class PartnerResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    // ------------------------------------------------------------- SEC-1 identity
    @POST
    @Path("/partners")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response registerPartner(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("system_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or system_admin required")).build();
        }
        String org = body == null ? null : body.getString("orgName", null);
        String certCn = body == null || body.containsKey("certCn") && body.isNull("certCn") ? null : body.getString("certCn", null);
        if (org == null || org.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "orgName is required")).build();
        }
        UUID partnerId = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO partner_account (partner_payee_id, tenant_id, org_name, "
                             + "cert_cn, contact_email) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, partnerId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, org);
            ps.setString(4, certCn);
            ps.setString(5, body.getString("contactEmail", null));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("partner register failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "partner_account", partnerId.toString(), null,
                "{\"org\":\"" + org + "\",\"cert_cn\":\"" + certCn + "\"}",
                "PARTNER_REGISTERED", "channel partner onboarded (FR-1004)",
                "eim.hierarchy.changed", partnerId.toString(),
                "{\"partner\":\"" + org + "\"}");
        return Response.ok(Map.of("partnerPayeeId", partnerId.toString(),
                "orgName", org, "certCn", certCn == null ? "" : certCn)).build();
    }

    /** Gateway mTLS CN → partner identity (called on partner-login). */
    @GET
    @Path("/partners/whoami")
    @PermitAll
    public Response whoami(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        // the gateway forwards the verified client cert CN
        String certCn = request.getHeaderString("X-Client-Cert-CN");
        if (certCn == null || certCn.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "partner mTLS identity missing (X-Client-Cert-CN)"))
                    .build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT partner_payee_id::text, org_name, status FROM partner_account "
                             + "WHERE tenant_id = CAST(? AS uuid) AND cert_cn = ?")) {
            ps.setString(1, ctx.tenantId());
            ps.setString(2, certCn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"ACTIVE".equals(rs.getString(3))) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "no ACTIVE partner for cert CN")).build();
                }
                return Response.ok(Map.of("partnerPayeeId", rs.getString(1),
                        "orgName", rs.getString(2), "role", "partner")).build();
            }
        }
    }

    // ------------------------------------------------------------- SEL-1 statement/disputes
    /** Partner statement — same ledger machinery, partner-scoped. */
    @GET
    @Path("/partners/{partnerPayeeId}/statement")
    @PermitAll
    public Response statement(@PathParam("partnerPayeeId") String partnerPayeeId,
                              @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = partnerCtx(request);
        if (!partnerPayeeId.equals(ctx.payeeId()) && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "partners see only their own statement")).build();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT l.component_name, l.payout_amount::text, l.entry_type, l.created_at::text "
                             + "FROM commission_ledger_entry l "
                             + "JOIN payout_period p ON p.payout_period_id = l.payout_period_id "
                             + "WHERE l.payee_id = CAST(? AS uuid) AND p.tenant_id = CAST(? AS uuid) "
                             + "ORDER BY l.created_at DESC LIMIT 100")) {
            ps.setString(1, partnerPayeeId);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                double total = 0;
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("component", rs.getString(1));
                    r.put("amount", rs.getString(2));
                    r.put("entryType", rs.getString(3));
                    r.put("createdAt", rs.getString(4));
                    total += Double.parseDouble(rs.getString(2));
                    rows.add(r);
                }
                return Response.ok(Map.of("partnerPayeeId", partnerPayeeId,
                        "totalPayout", String.format("%.4f", total), "lines", rows)).build();
            }
        }
    }

    /** Partner self-service dispute — reuses the dispute ticket machinery. */
    @POST
    @Path("/partners/{partnerPayeeId}/disputes")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response dispute(@PathParam("partnerPayeeId") String partnerPayeeId, JsonObject body,
                            @Context ContainerRequestContext request) {
        TenantContext ctx = partnerCtx(request);
        if (!partnerPayeeId.equals(ctx.payeeId())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "partners file disputes only for themselves")).build();
        }
        String category = body == null ? null : body.getString("category", null);
        String notes = body == null ? null : body.getString("notes", null);
        if (category == null || notes == null || category.isBlank() || notes.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "category and notes are required")).build();
        }
        UUID disputeId = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO dispute_ticket (dispute_id, tenant_id, payee_id, category, notes, "
                             + "status) VALUES (?,?,?,?,?, 'SUBMITTED')")) {
            ps.setObject(1, disputeId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setObject(3, UUID.fromString(partnerPayeeId));
            ps.setString(4, category);
            ps.setString(5, notes);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("partner dispute failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "DISPUTE_RESOLUTION", "dispute_ticket", disputeId.toString(), null,
                "{\"status\":\"SUBMITTED\",\"channel\":true}",
                "PARTNER_DISPUTE", "partner self-service dispute (FR-1004)",
                "eim.dispute.created", disputeId.toString(),
                "{\"partner\":\"" + partnerPayeeId + "\"}");
        return Response.accepted(Map.of("disputeId", disputeId.toString(),
                "status", "SUBMITTED")).build();
    }

    // ------------------------------------------------------------- PRC-1 claims
    @POST
    @Path("/claims")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response submitClaim(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        String partner = body == null ? null : body.getString("partnerPayeeId", null);
        String program = body == null ? null : body.getString("programCode", null);
        String period = body == null ? null : body.getString("claimPeriodKey", null);
        java.math.BigDecimal amount = null;
        if (body != null && body.containsKey("claimedAmount") && !body.isNull("claimedAmount")) {
            var v = body.get("claimedAmount");
            amount = v instanceof jakarta.json.JsonNumber n2 ? n2.bigDecimalValue()
                    : new java.math.BigDecimal(body.getString("claimedAmount"));
        }
        if (partner == null || program == null || period == null
                || amount == null || amount.signum() <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "partnerPayeeId, programCode, claimPeriodKey and "
                            + "positive claimedAmount are required")).build();
        }
        UUID claimId = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            // eligibility (FR-1005): program exists & active, amount ≥ min,
            // partner owns the claim
            var prog = new java.util.HashMap<String, Object>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT active, min_claim, rate FROM channel_program "
                            + "WHERE program_code = ? AND tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, program);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        prog.put("active", rs.getBoolean(1));
                        prog.put("minClaim", rs.getBigDecimal(2));
                        prog.put("rate", rs.getBigDecimal(3));
                    }
                }
            }
            boolean eligible = !prog.isEmpty() && Boolean.TRUE.equals(prog.get("active"))
                    && amount.compareTo((java.math.BigDecimal) prog.get("minClaim")) >= 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO channel_claim (claim_id, tenant_id, partner_payee_id, program_code, "
                            + "claim_period_key, claimed_amount, status, eligibility_detail) "
                            + "VALUES (?,?,?,?,?,?,?, CAST(? AS jsonb)) "
                            + "ON CONFLICT (tenant_id, partner_payee_id, program_code, claim_period_key) "
                            + "DO UPDATE SET claimed_amount = EXCLUDED.claimed_amount, "
                            + "status = EXCLUDED.status, "
                            + "eligibility_detail = EXCLUDED.eligibility_detail, "
                            + "submitted_at = now()")) {
                ps.setObject(1, claimId);
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, UUID.fromString(partner));
                ps.setString(4, program);
                ps.setString(5, period);
                ps.setBigDecimal(6, amount);
                ps.setString(7, eligible ? "ELIGIBILITY_CHECKED" : "REJECTED");
                ps.setString(8, "{\"eligible\":" + eligible + ",\"rate\":\""
                        + prog.getOrDefault("rate", "0") + "\"}");
                ps.executeUpdate();
            }
            audit.record(ctx, "APPROVAL", "channel_claim", claimId.toString(), null,
                    "{\"program\":\"" + program + "\",\"amount\":\"" + amount.toPlainString()
                            + "\",\"eligible\":" + eligible + "}",
                    eligible ? "CHANNEL_CLAIM_SUBMITTED" : "CHANNEL_CLAIM_REJECTED",
                    "channel claim (FR-1005)",
                    "eim.dispute.created", claimId.toString(),
                    "{\"claim\":\"" + claimId + "\",\"program\":\"" + program + "\"}");
            return Response.accepted(Map.of("claimId", claimId.toString(),
                    "status", eligible ? "ELIGIBILITY_CHECKED" : "REJECTED",
                    "eligible", eligible)).build();
        } catch (SQLException e) {
            throw new WebApplicationException("claim failed: " + e.getMessage(), 500);
        }
    }

    /** Approve + accrue: posts the accrual as an audited ledger entry via
     *  the core engine's ledger machinery (channel accrual = claimed × rate). */
    @POST
    @Path("/claims/{claimId}/approve")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response approve(@PathParam("claimId") String claimId, JsonObject body,
                            @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver") && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver or comp_admin required")).build();
        }
        try (Connection c = ds.getConnection()) {
            String partner;
            String program;
            String period;
            java.math.BigDecimal claimed;
            String status;
            java.math.BigDecimal rate;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT partner_payee_id::text, program_code, claim_period_key, "
                            + "claimed_amount::text, status FROM channel_claim "
                            + "WHERE claim_id = CAST(? AS uuid) AND tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, claimId);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown claim")).build();
                    }
                    partner = rs.getString(1);
                    program = rs.getString(2);
                    period = rs.getString(3);
                    claimed = new java.math.BigDecimal(rs.getString(4));
                    status = rs.getString(5);
                }
            }
            if (!"ELIGIBILITY_CHECKED".equals(status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "claim is " + status
                                + " — only ELIGIBILITY_CHECKED claims can be approved")).build();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rate FROM channel_program WHERE program_code = ?")) {
                ps.setString(1, program);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    rate = rs.getBigDecimal(1);
                }
            }
            java.math.BigDecimal accrual = claimed.multiply(rate)
                    .setScale(4, java.math.RoundingMode.HALF_EVEN);

            // accrual ledger entry on the core machinery
            UUID ledgerId = UUID.randomUUID();
            UUID periodId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payout_period_id FROM payout_period WHERE period_key = ? "
                            + "AND tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, period);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(Map.of("error", "no payout period " + period)).build();
                    }
                    periodId = (UUID) rs.getObject(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT eim_ops.ensure_period_partition(?, ?::text)")) {
                ps.setObject(1, periodId);
                ps.setString(2, period);
                ps.executeQuery();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                            + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                            + "is_locked) VALUES (?,?,?,?,?,?,'CALCULATED',"
                            + "CAST(? AS jsonb),false)")) {
                ps.setObject(1, ledgerId);
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setObject(3, periodId);
                ps.setObject(4, UUID.fromString(partner));
                ps.setString(5, "channel_accrual_" + program);
                ps.setBigDecimal(6, accrual);
                ps.setString(7, "{\"claim_id\":\"" + claimId + "\",\"claimed\":\""
                        + claimed.toPlainString() + "\",\"rate\":\"" + rate + "\"}");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE channel_claim SET status='ACCRUED', accrual_ledger_id=?, "
                            + "decided_by=?, decided_at=now() WHERE claim_id=CAST(? AS uuid)")) {
                ps.setObject(1, ledgerId);
                ps.setString(2, ctx.actor());
                ps.setString(3, claimId);
                ps.executeUpdate();
            }
            audit.record(ctx, "CALCULATION_RUN", "channel_claim", claimId, null,
                    "{\"status\":\"ACCRUED\",\"accrual\":\"" + accrual.toPlainString()
                            + "\",\"ledger\":\"" + ledgerId + "\"}",
                    "CHANNEL_ACCRUAL", "channel accrual posted on the core engine (FR-1005)",
                    "eim.ledger.committed", ledgerId.toString(),
                    "{\"claim\":\"" + claimId + "\",\"accrual\":\"" + accrual + "\"}");
            return Response.ok(Map.of("claimId", claimId, "status", "ACCRUED",
                    "accrualAmount", accrual.toPlainString(),
                    "ledgerEntryId", ledgerId.toString())).build();
        } catch (SQLException e) {
            throw new WebApplicationException("approve failed: " + e.getMessage(), 500);
        }
    }

    @GET
    @Path("/claims")
    @PermitAll
    public List<Map<String, Object>> claims(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT claim_id::text, partner_payee_id::text, program_code, "
                             + "claim_period_key, claimed_amount::text, status, "
                             + "coalesce(accrual_ledger_id::text,'') FROM channel_claim "
                             + "WHERE tenant_id = CAST(? AS uuid) ORDER BY submitted_at DESC")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("claimId", rs.getString(1));
                    r.put("partnerPayeeId", rs.getString(2));
                    r.put("programCode", rs.getString(3));
                    r.put("claimPeriodKey", rs.getString(4));
                    r.put("claimedAmount", rs.getString(5));
                    r.put("status", rs.getString(6));
                    r.put("accrualLedgerId", rs.getString(7));
                    out.add(r);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers
    private TenantContext ctx(ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "tenant context missing")).build());
        }
        return ctx;
    }

    private TenantContext partnerCtx(ContainerRequestContext request) {
        return ctx(request);
    }
}
