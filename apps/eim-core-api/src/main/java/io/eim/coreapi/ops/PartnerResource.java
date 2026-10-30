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
 * Partner onboarding registry (TG6-ONB-1): RevOps registers a partner tenant
 * with its IdP federation posture (gateway OIDC today, SAML federation for
 * partners that bring their own IdP) and advances it through the onboarding
 * lifecycle ONBOARDING → BACKFILLED → PARALLEL_RUN → SIGNED_OFF → LIVE.
 * Every step is audited as CONFIG_CHANGE so the Phase 1 GA evidence trail
 * shows who moved which partner forward.
 */
@Path("/api/v1/partners")
@Produces(MediaType.APPLICATION_JSON)
public class PartnerResource {

    private static final List<String> LIFECYCLE =
            List.of("ONBOARDING", "BACKFILLED", "PARALLEL_RUN", "SIGNED_OFF", "LIVE");

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public record PartnerUpsert(String name, String idpEntityId, String idpPosture,
                                Integer payeeTarget) {
    }

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(PartnerUpsert body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("system_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or system_admin required")).build();
        }
        if (body == null || body.name() == null || body.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "name is required")).build();
        }
        String posture = body.idpPosture() == null || body.idpPosture().isBlank()
                ? "GATEWAY_OIDC" : body.idpPosture();
        if (!posture.equals("GATEWAY_OIDC") && !posture.equals("SAML_FEDERATED")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "idpPosture must be GATEWAY_OIDC or SAML_FEDERATED")).build();
        }
        UUID partnerId = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO partner_tenant (partner_id, tenant_id, name, idp_entity_id, "
                             + "idp_posture, payee_target, status) VALUES (?,?,?,?,?,?, 'ONBOARDING')")) {
            ps.setObject(1, partnerId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, body.name());
            ps.setString(4, body.idpEntityId());
            ps.setString(5, posture);
            ps.setInt(6, body.payeeTarget() == null ? 100 : body.payeeTarget());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("partner insert failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "partner_tenant", partnerId.toString(),
                null,
                "{\"name\":\"" + body.name() + "\",\"idp_posture\":\"" + posture + "\","
                        + "\"status\":\"ONBOARDING\"}",
                "PARTNER_ONBOARD", "partner registered for parallel run",
                "eim.partner.onboarded", partnerId.toString(),
                "{\"partner\":\"" + body.name() + "\",\"idpPosture\":\"" + posture + "\"}");
        return Response.ok(Map.of("partnerId", partnerId.toString(),
                "name", body.name(), "idpPosture", posture,
                "status", "ONBOARDING")).build();
    }

    @GET
    @PermitAll
    public List<Map<String, Object>> list(@Context ContainerRequestContext request) throws SQLException {
        TenantContext caller = ctx(request);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT partner_id::text, tenant_id::text, name, idp_entity_id, idp_posture, "
                             + "payee_target, status, created_at::text FROM partner_tenant "
                             + "WHERE tenant_id = CAST(? AS uuid) ORDER BY created_at")) {
            ps.setString(1, caller.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("partnerId", rs.getString(1));
                    row.put("name", rs.getString(3));
                    row.put("idpEntityId", rs.getString(4));
                    row.put("idpPosture", rs.getString(5));
                    row.put("payeeTarget", rs.getInt(6));
                    row.put("status", rs.getString(7));
                    row.put("createdAt", rs.getString(8));
                    out.add(row);
                }
                return out;
            }
        }
    }

    @POST
    @Path("/{partnerId}/status")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response advance(@PathParam("partnerId") String partnerId, JsonObject body,
                            @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("system_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or system_admin required")).build();
        }
        String target = body == null ? null : body.getString("status", null);
        if (target == null || !LIFECYCLE.contains(target)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "status must be one of " + LIFECYCLE)).build();
        }
        try (Connection c = ds.getConnection()) {
            String current;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status FROM partner_tenant WHERE partner_id = CAST(? AS uuid) "
                            + "AND tenant_id = CAST(? AS uuid)")) {
                ps.setString(1, partnerId);
                ps.setString(2, ctx.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "unknown partner for tenant")).build();
                    }
                    current = rs.getString(1);
                }
            }
            if (LIFECYCLE.indexOf(target) != LIFECYCLE.indexOf(current) + 1) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "lifecycle must advance one step: " + current
                                + " -> " + target + " is not allowed")).build();
            }
            // Gate the sign-off→LIVE jump on two consecutive 100% periods (ONB-4).
            if ("SIGNED_OFF".equals(target)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM parallel_signoff s JOIN partner_tenant p "
                                + "ON p.partner_id = s.partner_id "
                                + "WHERE s.partner_id = CAST(? AS uuid) AND s.match_rate = 100.0")) {
                    ps.setString(1, partnerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) < 2) {
                            return Response.status(Response.Status.CONFLICT)
                                    .entity(Map.of("error", "SIGNED_OFF requires two consecutive "
                                            + "100% sign-offs (ONB-4); found " + rs.getInt(1)))
                                    .build();
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE partner_tenant SET status = ? WHERE partner_id = CAST(? AS uuid)")) {
                ps.setString(1, target);
                ps.setString(2, partnerId);
                ps.executeUpdate();
            }
            audit.record(ctx, "CONFIG_CHANGE", "partner_tenant", partnerId,
                    "{\"status\":\"" + current + "\"}",
                    "{\"status\":\"" + target + "\"}",
                    "PARTNER_LIFECYCLE", "partner lifecycle advanced",
                    "eim.partner.onboarded", partnerId,
                    "{\"partner\":\"" + partnerId + "\",\"status\":\"" + target + "\"}");
        } catch (SQLException e) {
            throw new WebApplicationException("partner status change failed: " + e.getMessage(), 500);
        }
        return Response.ok(Map.of("partnerId", partnerId, "status", target)).build();
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
