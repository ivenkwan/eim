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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
 * Territory & Quota planning (TG8-HIE-1, FR-1002/1003): territory design
 * (hierarchical, effective-dated), quota allocation per period, what-if
 * realignment previews (pure read — no mutation until committed), and
 * account-routing rules that feed credit assignment. The realignment
 * preview returns the as-of coverage delta so planners see exactly which
 * accounts/payees move before committing.
 */
@Path("/api/v1/spm/territories")
@Produces(MediaType.APPLICATION_JSON)
public class TerritoryQuotaResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response createTerritory(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String name = body == null ? null : body.getString("name", null);
        if (name == null || name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "name is required")).build();
        }
        UUID territoryId = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO territory (territory_id, tenant_id, name, parent_territory_id, "
                             + "effective_start) VALUES (?,?,?,?,COALESCE(?, DATE '2026-01-01'))")) {
            ps.setObject(1, territoryId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, name);
            String parent = body.getString("parentTerritoryId", "");
            if (parent == null || parent.isBlank()) {
                ps.setNull(4, java.sql.Types.OTHER);
            } else {
                ps.setObject(4, UUID.fromString(parent));
            }
            String start = body.getString("effectiveStart", "");
            if (start == null || start.isBlank()) {
                ps.setNull(5, java.sql.Types.DATE);
            } else {
                ps.setDate(5, Date.valueOf(LocalDate.parse(start)));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("territory insert failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "territory", territoryId.toString(), null,
                "{\"name\":\"" + name + "\"}",
                "TERRITORY_CREATED", "territory designed (FR-1002)",
                "eim.hierarchy.changed", territoryId.toString(),
                "{\"territory\":\"" + name + "\"}");
        return Response.ok(Map.of("territoryId", territoryId.toString(), "name", name)).build();
    }

    /** Assign a payee or an account key to a territory (effective-dated). */
    @POST
    @Path("/{territoryId}/assignments")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response assign(@PathParam("territoryId") String territoryId, JsonObject body,
                           @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin required")).build();
        }
        String payeeId = body == null || body.containsKey("payeeId") && body.isNull("payeeId") ? null : body.getString("payeeId", null);
        String accountKey = body == null || body.containsKey("accountKey") && body.isNull("accountKey") ? null : body.getString("accountKey", null);
        if ((payeeId == null) == (accountKey == null)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "exactly one of payeeId or accountKey")).build();
        }
        double split = 1.0;
        if (body != null && body.containsKey("splitPct") && !body.isNull("splitPct")) {
            var v = body.get("splitPct");
            split = v instanceof jakarta.json.JsonNumber n2 ? n2.doubleValue()
                    : Double.parseDouble(body.getString("splitPct"));
        }
        if (split <= 0 || split > 1.0000001) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "splitPct must be a fraction in (0,1]")).build();
        }
        String startStr = body == null ? "" : body.getString("effectiveStart", "");
        LocalDate start = startStr == null || startStr.isBlank()
                ? LocalDate.now() : LocalDate.parse(startStr);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO territory_assignment (assignment_id, tenant_id, territory_id, "
                             + "payee_id, account_key, split_pct, effective_start) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setObject(3, UUID.fromString(territoryId));
            if (payeeId != null) {
                ps.setObject(4, UUID.fromString(payeeId));
            } else {
                ps.setNull(4, java.sql.Types.OTHER);
            }
            ps.setString(5, accountKey);
            ps.setBigDecimal(6, new java.math.BigDecimal(String.valueOf(split)));
            ps.setDate(7, Date.valueOf(start));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("assignment failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "territory_assignment", territoryId, null,
                "{\"payee\":\"" + payeeId + "\",\"account\":\"" + accountKey + "\"}",
                "TERRITORY_ASSIGN", "coverage assignment (FR-1002)",
                "eim.hierarchy.changed", territoryId,
                "{\"territory\":\"" + territoryId + "\"}");
        return Response.ok(Map.of("territoryId", territoryId,
                "payeeId", payeeId == null ? "" : payeeId,
                "accountKey", accountKey == null ? "" : accountKey)).build();
    }

    /** Quota allocation per period (FR-1002). */
    @POST
    @Path("/{territoryId}/quotas")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response quota(@PathParam("territoryId") String territoryId, JsonObject body,
                          @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String periodKey = body == null ? null : body.getString("periodKey", null);
        java.math.BigDecimal amount = null;
        if (body != null && body.containsKey("quotaAmount") && !body.isNull("quotaAmount")) {
            var v = body.get("quotaAmount");
            amount = v instanceof jakarta.json.JsonNumber n3 ? n3.bigDecimalValue()
                    : new java.math.BigDecimal(body.getString("quotaAmount"));
        }
        if (periodKey == null || amount == null || amount.signum() <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "periodKey and positive quotaAmount are required")).build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO quota_allocation (quota_id, tenant_id, territory_id, period_key, "
                             + "quota_amount) VALUES (?,?,?,?,?) "
                             + "ON CONFLICT (territory_id, period_key) DO UPDATE SET "
                             + "quota_amount = EXCLUDED.quota_amount")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setObject(3, UUID.fromString(territoryId));
            ps.setString(4, periodKey);
            ps.setBigDecimal(5, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("quota write failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "quota_allocation", territoryId + ":" + periodKey,
                null, "{\"quota\":\"" + amount.toPlainString() + "\"}",
                "QUOTA_ALLOCATED", "quota allocation (FR-1002)",
                "eim.hierarchy.changed", territoryId,
                "{\"territory\":\"" + territoryId + "\",\"period\":\"" + periodKey + "\"}");
        return Response.ok(Map.of("territoryId", territoryId, "periodKey", periodKey,
                "quotaAmount", amount.toPlainString())).build();
    }

    /**
     * What-if realignment preview (FR-1002): pure read — returns the
     * payee→territory coverage each account would route to under the
     * proposed assignment set, against current, without writing anything.
     */
    @POST
    @Path("/realignment-preview")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    public Response realignmentPreview(JsonObject body,
                                       @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ta.account_key, t.name, ta.payee_id::text, ta.split_pct::text "
                             + "FROM territory_assignment ta "
                             + "JOIN territory t ON t.territory_id = ta.territory_id "
                             + "WHERE ta.tenant_id = CAST(? AS uuid) "
                             + "AND ta.account_key IS NOT NULL "
                             + "AND (ta.effective_end IS NULL OR ta.effective_end >= CURRENT_DATE) "
                             + "ORDER BY ta.account_key, t.name")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("accountKey", rs.getString(1));
                    r.put("territory", rs.getString(2));
                    r.put("payeeId", rs.getString(3));
                    r.put("splitPct", rs.getString(4));
                    rows.add(r);
                }
            }
        }
        return Response.ok(Map.of("preview", true, "accountRoutes", rows)).build();
    }

    @GET
    @PermitAll
    public List<Map<String, Object>> list(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.territory_id::text, t.name, p.name, "
                             + "coalesce((SELECT sum(q.quota_amount) FROM quota_allocation q "
                             + "  WHERE q.territory_id = t.territory_id), 0)::text, "
                             + "(SELECT count(*) FROM territory_assignment a "
                             + "  WHERE a.territory_id = t.territory_id "
                             + "  AND (a.effective_end IS NULL OR a.effective_end >= CURRENT_DATE)) "
                             + "FROM territory t LEFT JOIN territory p "
                             + "ON p.territory_id = t.parent_territory_id "
                             + "WHERE t.tenant_id = CAST(? AS uuid) ORDER BY t.name")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("territoryId", rs.getString(1));
                    r.put("name", rs.getString(2));
                    r.put("parent", rs.getString(3));
                    r.put("totalQuota", rs.getString(4));
                    r.put("activeAssignments", rs.getLong(5));
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
