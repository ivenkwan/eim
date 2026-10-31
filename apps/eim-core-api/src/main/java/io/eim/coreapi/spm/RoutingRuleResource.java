package io.eim.coreapi.spm;

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
 * Account-routing rules feeding credit assignment (TG8-HIE-2, FR-1003):
 * ordered rules (account key match → component filter → route to a
 * territory or payee) and the resolution endpoint the ingestion path
 * calls — `resolve` returns the payee(s) a transaction's account should
 * credit, merging territory assignments (with splits) and direct routes in
 * priority order.
 */
@Path("/api/v1/spm/routing")
@Produces(MediaType.APPLICATION_JSON)
public class RoutingRuleResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin required")).build();
        }
        var priority = body != null && body.getJsonNumber("priority") != null
                ? body.getInt("priority") : 100;
        String routeTerritory = body == null || body.containsKey("routeToTerritoryId") && body.isNull("routeToTerritoryId")
                ? null : body.getString("routeToTerritoryId", null);
        String routePayee = body == null || body.containsKey("routeToPayeeId") && body.isNull("routeToPayeeId")
                ? null : body.getString("routeToPayeeId", null);
        if (routeTerritory == null && routePayee == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "routeToTerritoryId or routeToPayeeId is required")).build();
        }
        UUID ruleId = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO routing_rule (rule_id, tenant_id, priority, match_account_key, "
                             + "match_component, route_to_territory_id, route_to_payee_id) "
                             + "VALUES (?,?,?,?,?,CAST(? AS uuid),CAST(? AS uuid))")) {
            ps.setObject(1, ruleId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setInt(3, priority);
            ps.setString(4, body.containsKey("matchAccountKey") && body.isNull("matchAccountKey") ? null : body.getString("matchAccountKey", null));
            ps.setString(5, body.containsKey("matchComponent") && body.isNull("matchComponent") ? null : body.getString("matchComponent", null));
            ps.setString(6, routeTerritory);
            ps.setString(7, routePayee);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("rule insert failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "routing_rule", ruleId.toString(), null,
                "{\"priority\":" + priority + "}",
                "ROUTING_RULE_CREATED", "account routing rule (FR-1003)",
                "eim.hierarchy.changed", ruleId.toString(),
                "{\"rule\":\"" + ruleId + "\"}");
        return Response.ok(Map.of("ruleId", ruleId.toString(), "priority", priority)).build();
    }

    /**
     * Resolve the payees an account should credit (FR-1003): highest-priority
     * active rule whose account key matches (or catch-all) wins; routing to a
     * territory fans out to the territory's current payee assignments with
     * their split fractions. No rule → empty list (caller falls back to CRM
     * credit data).
     */
    @GET
    @Path("/resolve")
    @PermitAll
    public Response resolve(@jakarta.ws.rs.QueryParam("accountKey") String accountKey,
                            @jakarta.ws.rs.QueryParam("component") String component,
                            @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (accountKey == null || accountKey.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "accountKey is required")).build();
        }
        List<Map<String, Object>> routed = new ArrayList<>();
        String matchedRule = null;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rule_id::text, route_to_territory_id::text, route_to_payee_id::text "
                             + "FROM routing_rule "
                             + "WHERE tenant_id = CAST(? AS uuid) AND active "
                             + "AND (match_account_key IS NULL OR match_account_key = ?) "
                             + "AND (match_component IS NULL OR match_component = ?) "
                             + "ORDER BY priority, created_at LIMIT 1")) {
            ps.setString(1, ctx.tenantId());
            ps.setString(2, accountKey);
            ps.setString(3, component);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    matchedRule = rs.getString(1);
                    String territory = rs.getString(2);
                    String payee = rs.getString(3);
                    if (payee != null) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("payeeId", payee);
                        r.put("splitPct", "1.000000");
                        r.put("via", "rule");
                        routed.add(r);
                    } else if (territory != null) {
                        try (PreparedStatement aps = c.prepareStatement(
                                "SELECT payee_id::text, split_pct::text FROM territory_assignment "
                                        + "WHERE territory_id = CAST(? AS uuid) "
                                        + "AND payee_id IS NOT NULL "
                                        + "AND (effective_end IS NULL OR effective_end >= CURRENT_DATE)")) {
                            aps.setString(1, territory);
                            try (ResultSet ars = aps.executeQuery()) {
                                while (ars.next()) {
                                    Map<String, Object> r = new LinkedHashMap<>();
                                    r.put("payeeId", ars.getString(1));
                                    r.put("splitPct", ars.getString(2));
                                    r.put("via", "territory");
                                    routed.add(r);
                                }
                            }
                        }
                    }
                }
            }
        }
        return Response.ok(Map.of("accountKey", accountKey,
                "matchedRule", matchedRule == null ? "" : matchedRule,
                "payees", routed)).build();
    }

    @GET
    @PermitAll
    public List<Map<String, Object>> list(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rule_id::text, priority, coalesce(match_account_key,''), "
                             + "coalesce(match_component,''), coalesce(route_to_territory_id::text,''), "
                             + "coalesce(route_to_payee_id::text,''), active "
                             + "FROM routing_rule WHERE tenant_id = CAST(? AS uuid) "
                             + "ORDER BY priority, created_at")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ruleId", rs.getString(1));
                    r.put("priority", rs.getInt(2));
                    r.put("matchAccountKey", rs.getString(3));
                    r.put("matchComponent", rs.getString(4));
                    r.put("routeToTerritoryId", rs.getString(5));
                    r.put("routeToPayeeId", rs.getString(6));
                    r.put("active", rs.getBoolean(7));
                    out.add(r);
                }
            }
        }
        return out;
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
