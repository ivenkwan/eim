package io.eim.connectors.hierarchy;

import io.agroal.api.AgroalDataSource;
import io.eim.connectors.audit.AuditService;
import io.eim.connectors.common.TenantContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
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
 * Effective-dated hierarchy admin (FR-302/TG2-HIE-2..3): SCD-2 payee CRUD
 * with cycle/overlap validation (the AGE trigger projects every write into
 * the graph in the same transaction) and a full audit trail per edit.
 * Manager moves mid-period produce dated rows; rollup resolution is as-of
 * via eim_ops.rollup_scope_asof (FR-302.1).
 */
@Path("/api/v1/org")
@Produces(MediaType.APPLICATION_JSON)
public class HierarchyResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public record PayeeUpsert(String payeeId, String employeeNumber, String fullName,
                              String email, String roleTitle, String managerPayeeId,
                              String effectiveStart, String effectiveEnd) {
    }

    @POST
    @Path("payee")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(PayeeUpsert body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        UUID payeeId = body.payeeId() == null ? UUID.randomUUID() : UUID.fromString(body.payeeId());
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, "
                             + "manager_payee_id, effective_start_date, effective_end_date) VALUES (?,?,?,?,?,?,?,?,?)")) {
            bind(ps, ctx, payeeId, body);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "payee insert failed: " + e.getMessage())).build());
        }
        validateNoCycle();
        audit.record(ctx, "CONFIG_CHANGE", "payee", payeeId.toString(),
                "{\"action\":\"create\",\"email\":\"" + body.email() + "\"}", "hierarchy admin",
                "hierarchy.changed", payeeId.toString(),
                "{\"payee\":\"" + payeeId + "\",\"manager\":\"" + body.managerPayeeId() + "\"}");
        return Response.accepted(Map.of("payeeId", payeeId.toString())).build();
    }

    @PUT
    @Path("payee/{payeeId}/manager")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response moveManager(@PathParam("payeeId") String payeeId, jakarta.json.JsonObject body,
                                @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        UUID id = UUID.fromString(payeeId);
        String newManager = body.getString("manager_payee_id");
        LocalDate fromDate = LocalDate.parse(body.getString("effective_start"));
        LocalDate toDate = body.containsKey("effective_end") && !body.isNull("effective_end")
                ? LocalDate.parse(body.getString("effective_end")) : null;
        try (Connection c = ds.getConnection()) {
            // Close the current row (SCD-2) then insert the new effective-dated row.
            try (PreparedStatement close = c.prepareStatement(
                    "UPDATE payee SET effective_end_date = ?::date - 1 "
                            + "WHERE payee_id = ? AND effective_end_date IS NULL")) {
                close.setDate(1, java.sql.Date.valueOf(fromDate));
                close.setObject(2, id);
                int closed = close.executeUpdate();
                if (closed == 0) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "payee not found or already closed")).build();
                }
            }
            Map<String, Object> base = loadPayee(c, ctx, id);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, "
                            + "manager_payee_id, effective_start_date, effective_end_date) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setString(3, (String) base.get("employee_number"));
                ps.setString(4, (String) base.get("full_name"));
                ps.setString(5, (String) base.get("email"));
                ps.setString(6, (String) base.get("role_title"));
                ps.setObject(7, UUID.fromString(newManager));
                ps.setDate(8, java.sql.Date.valueOf(fromDate));
                if (toDate == null) {
                    ps.setNull(9, java.sql.Types.DATE);
                } else {
                    ps.setDate(9, java.sql.Date.valueOf(toDate));
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "manager move failed: " + e.getMessage())).build());
        }
        validateNoCycle();
        audit.record(ctx, "CONFIG_CHANGE", "payee", id.toString(),
                "{\"action\":\"manager_move\",\"to\":\"" + newManager + "\",\"from\":\"" + fromDate + "\"}",
                "hierarchy admin",
                "hierarchy.changed", id.toString(),
                "{\"payee\":\"" + id + "\",\"manager\":\"" + newManager + "\",\"from\":\"" + fromDate + "\"}");
        return Response.accepted(Map.of("payeeId", payeeId, "manager", newManager,
                "effectiveStart", fromDate.toString())).build();
    }

    @jakarta.ws.rs.GET
    @Path("rollup/{managerPayeeId}")
    public Response rollup(@PathParam("managerPayeeId") String managerPayeeId,
                           @jakarta.ws.rs.QueryParam("asOf") String asOf,
                           @Context ContainerRequestContext request) {
        ctx(request); // authenticated only; graph read has no tenant scope in dev
        List<String> payees = new ArrayList<>();
        String fn = asOf == null ? "SELECT payee_id::text FROM eim_ops.rollup_scope(?)"
                : "SELECT payee_id::text FROM eim_ops.rollup_scope_asof(?, ?::date)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(fn)) {
            ps.setObject(1, UUID.fromString(managerPayeeId));
            if (asOf != null) {
                ps.setDate(2, java.sql.Date.valueOf(asOf));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payees.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new WebApplicationException("rollup failed: " + e.getMessage(), 500);
        }
        return Response.ok(Map.of("manager", managerPayeeId,
                "asOf", asOf == null ? "current" : asOf,
                "payees", payees, "count", payees.size())).build();
    }

    @jakarta.ws.rs.GET
    @Path("wavefronts")
    public Response wavefronts(@Context ContainerRequestContext request) {
        ctx(request);
        List<Map<String, Object>> levels = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payee_id::text, level FROM eim_ops.wavefront_levels() ORDER BY level DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("payeeId", rs.getString(1));
                row.put("level", rs.getInt(2));
                levels.add(row);
            }
        } catch (SQLException e) {
            throw new WebApplicationException("wavefront query failed: " + e.getMessage(), 500);
        }
        return Response.ok(Map.of("levels", levels, "maxLevel",
                levels.isEmpty() ? 0 : levels.get(0).get("level"))).build();
    }

    // ---------------------------------------------------------------- helpers
    private void bind(PreparedStatement ps, TenantContext ctx, UUID payeeId, PayeeUpsert b) throws SQLException {
        ps.setObject(1, payeeId);
        ps.setObject(2, UUID.fromString(ctx.tenantId()));
        ps.setString(3, b.employeeNumber());
        ps.setString(4, b.fullName());
        ps.setString(5, b.email());
        ps.setString(6, b.roleTitle());
        ps.setObject(7, b.managerPayeeId() == null ? null : UUID.fromString(b.managerPayeeId()));
        ps.setDate(8, java.sql.Date.valueOf(LocalDate.parse(b.effectiveStart())));
        if (b.effectiveEnd() == null) {
            ps.setNull(9, java.sql.Types.DATE);
        } else {
            ps.setDate(9, java.sql.Date.valueOf(LocalDate.parse(b.effectiveEnd())));
        }
    }

    private Map<String, Object> loadPayee(Connection c, TenantContext ctx, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT employee_number, full_name, email, role_title FROM payee "
                        + "WHERE payee_id = ? AND tenant_id::text = ? ORDER BY effective_start_date DESC LIMIT 1")) {
            ps.setObject(1, id);
            ps.setString(2, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new WebApplicationException(Response.status(500)
                            .entity(Map.of("error", "payee vanished mid-move")).build());
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("employee_number", rs.getString(1));
                m.put("full_name", rs.getString(2));
                m.put("email", rs.getString(3));
                m.put("role_title", rs.getString(4));
                return m;
            }
        }
    }

    private void validateNoCycle() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT eim_ops.hierarchy_has_cycle()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getBoolean(1)) {
                throw new WebApplicationException("hierarchy cycle detected — edit rejected (FR-302.2)", 409);
            }
        } catch (SQLException e) {
            throw new WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "cycle validation failed: " + e.getMessage())).build());
        }
    }

    private TenantContext ctx(ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(
                io.eim.connectors.common.TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            throw new WebApplicationException("tenant context missing", 500);
        }
        return ctx;
    }
}
