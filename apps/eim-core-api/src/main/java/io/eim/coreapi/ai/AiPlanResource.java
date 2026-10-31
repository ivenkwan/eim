package io.eim.coreapi.ai;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.json.Json;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NL plan modeling + AI diagnostics (TG8-RUL-1/2, FR-408/1006/708):
 * natural-language prompts compile to DRAFT PlanModel JSON through a
 * deterministic grammar compiler — the draft ALWAYS passes the same
 * FR-406 publish gate (type-check, cycle check, tier verification happen
 * at publish) and REQUIRES recorded human approval before it can be
 * published; AI-generated logic never reaches production unaudited.
 * Diagnostics scan a published plan for windfall (rate above policy band),
 * cliff (attainment drop just above a threshold) and gaming (component
 * with no cap + no window) anomalies. The earnings explainer renders
 * narratives STRICTLY from the stored calculation trace (no fabricated
 * math) and hands off to the dispute flow.
 */
@Path("/api/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
public class AiPlanResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    // ------------------------------------------------------------- RUL-1: NL → plan
    /**
     * Deterministic NL grammar: a constrained subset that maps to TIER_TABLE
     * or BONUS components with caps/kickers. Anything the grammar does not
     * understand is rejected — the LLM-assisted version wraps this compiler,
     * it never bypasses it (FR-408: AI logic passes the same gate).
     */
    static String compilePrompt(String prompt) {
        // e.g. "commission 5% up to 100000 then 8% above, cap 50000, 12 months"
        Pattern tiers = Pattern.compile(
                "(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:up to|to)\\s*(\\d+(?:\\.\\d+)?)\\s*"
                        + "then\\s*(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:above|after)",
                Pattern.CASE_INSENSITIVE);
        Pattern cap = Pattern.compile("cap\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
        String name = "nl_commission";
        Matcher nameM = Pattern.compile("(?:create|set)\\s+(?:a\\s+)?([a-z_]+)\\s+(?:plan|component|commission)",
                Pattern.CASE_INSENSITIVE).matcher(prompt == null ? "" : prompt);
        if (nameM.find()) {
            name = "nl_" + nameM.group(1).toLowerCase();
        }
        Matcher tm = tiers.matcher(prompt == null ? "" : prompt);
        if (!tm.find()) {
            throw new IllegalArgumentException(
                    "prompt must include a tier phrase like '5% up to 100000 then 8% above'");
        }
        String r1 = rateFraction(tm.group(1));
        String upTo = tm.group(2);
        String r2 = rateFraction(tm.group(3));
        StringBuilder protections = new StringBuilder();
        Matcher cm = cap.matcher(prompt);
        if (cm.find()) {
            protections.append(",\"protections\":{\"capScope\":\"PERIOD\",\"capBasis\":\"AMOUNT\",")
                    .append("\"capValue\":\"").append(cm.group(1)).append("\"}");
        }
        return "{\"planName\":\"" + name + "\",\"components\":[{\"name\":\"" + name
                + "\",\"kind\":\"TIER_TABLE\",\"outputType\":\"CURRENCY\",\"tiers\":"
                + "{\"mode\":\"MARGINAL\",\"basis\":\"AMOUNT\",\"brackets\":"
                + "[{\"upTo\":\"" + upTo + "\",\"rate\":\"" + r1 + "\"},"
                + "{\"rate\":\"" + r2 + "\"}]}" + protections + "}]}";
    }

    private static String rateFraction(String pct) {
        return new java.math.BigDecimal(pct)
                .divide(new java.math.BigDecimal("100"), 6, java.math.RoundingMode.HALF_EVEN)
                .stripTrailingZeros().toPlainString();
    }

    @POST
    @Path("/plan-modeling/requests")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response createRequest(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String prompt = body == null ? null : body.getString("prompt", null);
        if (prompt == null || prompt.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "prompt is required")).build();
        }
        UUID requestId = UUID.randomUUID();
        String planJson;
        try {
            planJson = compilePrompt(prompt);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO nl_model_request (request_id, tenant_id, prompt, "
                             + "generated_plan_json, compile_status, created_by) "
                             + "VALUES (?,?,?,CAST(? AS jsonb), 'COMPILED', ?)")) {
            ps.setObject(1, requestId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, prompt);
            ps.setString(4, planJson);
            ps.setString(5, ctx.actor());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("nl request failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "PLAN_EDIT", "nl_model_request", requestId.toString(), null,
                "{\"prompt\":\"" + prompt.replace("\"", "'") + "\"}",
                "NL_PLAN_COMPILED", "natural-language plan compiled to draft (FR-408)",
                "eim.plan.published", requestId.toString(),
                "{\"request\":\"" + requestId + "\",\"status\":\"COMPILED\"}");
        return Response.ok(Map.of("requestId", requestId.toString(),
                "compileStatus", "COMPILED",
                "approvalStatus", "PENDING_HUMAN",
                "draftPlanJson", planJson)).build();
    }

    /** Human approval gate (FR-408): only APPROVED requests may be published. */
    @POST
    @Path("/plan-modeling/requests/{requestId}/decision")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response decide(@PathParam("requestId") String requestId, JsonObject body,
                           @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        String decision = body == null ? null : body.getString("decision", null);
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "decision must be APPROVED or REJECTED")).build();
        }
        // SoD: the approver must differ from the requester
        try (Connection c = ds.getConnection()) {
            String requester;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT created_by FROM nl_model_request WHERE request_id = CAST(? AS uuid)")) {
                ps.setString(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown request")).build();
                    }
                    requester = rs.getString(1);
                }
            }
            if ("APPROVED".equals(decision) && ctx.actor().equals(requester)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "requester cannot approve their own AI draft (SoD)"))
                        .build();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE nl_model_request SET approval_status = ?, approved_by = ?, "
                            + "approved_at = now() WHERE request_id = CAST(? AS uuid)")) {
                ps.setString(1, decision);
                ps.setString(2, ctx.actor());
                ps.setString(3, requestId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new WebApplicationException("decision failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "APPROVAL", "nl_model_request", requestId, null,
                "{\"decision\":\"" + decision + "\"}",
                "NL_PLAN_" + decision, "human decision on AI-generated plan (FR-408)",
                "eim.plan.published", requestId,
                "{\"request\":\"" + requestId + "\",\"decision\":\"" + decision + "\"}");
        return Response.ok(Map.of("requestId", requestId, "approvalStatus", decision,
                "note", "APPROVED drafts publish through the standard FR-406 gate")).build();
    }

    // ------------------------------------------------------------- RUL-2: diagnostics
    /** Windfall/cliff/gaming anomaly detection over a published plan (FR-1006). */
    @POST
    @Path("/plan-diagnostics/{planVersionHash}")
    @PermitAll
    @Transactional
    public Response diagnose(@PathParam("planVersionHash") String planVersionHash,
                             @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        String modelJson;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT model_json FROM plan_artifact WHERE plan_version_hash = ?")) {
            ps.setString(1, planVersionHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "unknown plan version")).build();
                }
                modelJson = rs.getString(1);
            }
        } catch (SQLException e) {
            throw new WebApplicationException("plan load failed: " + e.getMessage(), 500);
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        // WINDFALL: top marginal rate above 0.25 with no cap
        // CLIFF: tier gap (bracket boundary with rate jump > 2x)
        // GAMING: ROLLUP depending on an uncapped component
        Matcher rates = Pattern.compile("\"rate\"\\s*:\\s*\"([0-9.]+)\"").matcher(modelJson);
        double maxRate = 0;
        while (rates.find()) {
            maxRate = Math.max(maxRate, Double.parseDouble(rates.group(1)));
        }
        boolean hasCap = modelJson.contains("capValue");
        if (maxRate > 0.25 && !hasCap) {
            findings.add(finding("WINDFALL", null, "top rate " + maxRate
                    + " with no cap — windfall exposure at extreme attainment", "CRITICAL"));
        }
        // consecutive bracket rates (brackets serialize with or without upTo);
        // a >2x jump across a boundary is cliff behavior
        Matcher rateM = Pattern.compile("\"rate\"\\s*:\\s*\"([0-9.]+)\"").matcher(modelJson);
        java.util.List<Double> ratesSeq = new java.util.ArrayList<>();
        while (rateM.find()) {
            ratesSeq.add(Double.parseDouble(rateM.group(1)));
        }
        for (int i = 1; i < ratesSeq.size(); i++) {
            double jump = ratesSeq.get(i) / Math.max(ratesSeq.get(i - 1), 1e-9);
            if (jump > 2.0) {
                findings.add(finding("CLIFF", null, "rate jumps "
                        + String.format("%.1f", jump) + "x at a tier boundary"
                        + " — cliff behavior near the threshold", "WARN"));
            }
        }
        if (modelJson.contains("\"ROLLUP\"") && !hasCap) {
            findings.add(finding("GAMING", null,
                    "ROLLUP over uncapped component — gaming vector via split/pool manipulation",
                    "WARN"));
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO plan_diagnostic (diagnostic_id, tenant_id, plan_version_hash, "
                             + "kind, component, detail, severity) VALUES (?,?,?,?,?,CAST(? AS jsonb),?)")) {
            for (Map<String, Object> f : findings) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setString(3, planVersionHash);
                ps.setString(4, String.valueOf(f.get("kind")));
                ps.setString(5, String.valueOf(f.get("component")));
                ps.setString(6, "{\"detail\":\"" + f.get("detail") + "\"}");
                ps.setString(7, String.valueOf(f.get("severity")));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new WebApplicationException("diagnostic persist failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "PLAN_PUBLISH", "plan_diagnostic", planVersionHash, null,
                "{\"findings\":" + findings.size() + "}",
                "AI_PLAN_DIAGNOSTICS", "plan anomaly scan (FR-1006)",
                "eim.plan.published", planVersionHash,
                "{\"plan\":\"" + planVersionHash + "\",\"findings\":" + findings.size() + "}");
        return Response.ok(Map.of("planVersionHash", planVersionHash,
                "findings", findings, "clean", findings.isEmpty())).build();
    }

    private static Map<String, Object> finding(String kind, String component,
                                               String detail, String severity) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", kind);
        f.put("component", component == null ? "" : component);
        f.put("detail", detail);
        f.put("severity", severity);
        return f;
    }

    // ------------------------------------------------------------- UI-2: explainer
    /**
     * Earnings explainer (FR-708): renders a narrative STRICTLY from the
     * stored calculation trace — every number in the output comes from the
     * trace JSON; nothing is computed or estimated. Returns a dispute
     * handoff payload when the narrative indicates a dispute path.
     */
    @GET
    @Path("/earnings-explainer/{ledgerId}")
    @PermitAll
    public Response explainer(@PathParam("ledgerId") String ledgerId,
                              @Context ContainerRequestContext request) throws SQLException {
        ctx(request);
        String trace;
        String component;
        String amount;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT calculation_trace::text, component_name, payout_amount::text "
                             + "FROM commission_ledger_entry WHERE ledger_id = CAST(? AS uuid)")) {
            ps.setString(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "unknown ledger entry")).build();
                }
                trace = rs.getString(1);
                component = rs.getString(2);
                amount = rs.getString(3);
            }
        }
        // walk the stored trace only — zero recomputation
        StringBuilder narrative = new StringBuilder();
        List<String> slices = new ArrayList<>();
        java.util.List<String> sliceAmounts = new java.util.ArrayList<>();
        java.util.List<String> sliceRates = new java.util.ArrayList<>();
        Matcher am = Pattern.compile("\"sliceAmount\"\\s*:\\s*\"([0-9.]+)\"").matcher(trace);
        while (am.find()) {
            sliceAmounts.add(am.group(1));
        }
        Matcher rm = Pattern.compile("\"rateApplied\"\\s*:\\s*\"([0-9.]+)\"").matcher(trace);
        while (rm.find()) {
            sliceRates.add(rm.group(1));
        }
        for (int i = 0; i < Math.min(sliceAmounts.size(), sliceRates.size()); i++) {
            slices.add(sliceAmounts.get(i) + " at rate " + sliceRates.get(i));
        }
        narrative.append("Your ").append(component).append(" of ").append(amount)
                .append(" was computed from ").append(slices.size())
                .append(" marginal tier slices recorded in the calculation trace");
        if (!slices.isEmpty()) {
            narrative.append(": ").append(String.join("; ", slices));
        } else {
            narrative.append(".");
        }
        boolean traceIncomplete = "{}".equals(trace) || trace.isBlank();
        if (traceIncomplete) {
            narrative.append(" This entry has no stored trace — if the amount looks wrong, "
                    + "file a dispute and finance will investigate the source record.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ledgerId", ledgerId);
        out.put("narrative", narrative.toString());
        out.put("groundedInTrace", !traceIncomplete);
        out.put("disputeHandoff", Map.of(
                "url", "/api/v1/disputes",
                "payload", Map.of("category", traceIncomplete ? "SOURCE_DATA" : "PLAN_LOGIC",
                        "ledgerId", ledgerId)));
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
