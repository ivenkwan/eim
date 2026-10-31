package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit-ops surface for operators/monitors (read endpoints; runs as owner,
 * so RLS is not applied — these expose counts and seal status only, and are
 * themselves audited at the gateway level).
 */
@Path("/api/v1/admin/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AdminOpsResource {

    @Inject
    AgroalDataSource ds;

    @Context
    ContainerRequestContext request;

    /** Role gate that works in both oidc and header (gateway-trust) modes. */
    private boolean deny(String... roles) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null) {
            return true;
        }
        for (String role : roles) {
            if (ctx.hasRole(role)) {
                return false;
            }
        }
        return true;
    }

    /** AUD-9 evidence endpoint: mutation/outbox/event counts + gap. */
    @GET
    @Path("/reconcile")
    public Map<String, Object> reconcile(@QueryParam("hours") int hours) throws SQLException {
        if (deny("finance_approver", "comp_admin", "system_admin")) {
            return Map.of("error", "forbidden");
        }
        int window = hours <= 0 ? 24 : hours;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM eim_ops.reconcile_audit_full(?)")) {
            ps.setTimestamp(1, Timestamp.from(java.time.OffsetDateTime.now().minusHours(window).toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("window_hours", window);
                out.put("audited_mutations", rs.getLong(1));
                out.put("outbox_rows", rs.getLong(2));
                out.put("published_events", rs.getLong(3));
                out.put("raw_gap", rs.getLong(4));
                out.put("api_mutations", rs.getLong(5));
                out.put("api_paired_outbox", rs.getLong(6));
                out.put("api_gap", rs.getLong(7));
                out.put("api_gap_alert", rs.getLong(7) != 0);
                return out;
            }
        }
    }

    /** AUD-6: seal a day on demand (idempotent; refuses empty days). */
    @POST
    @Path("/seals")
        public Map<String, Object> seal(@QueryParam("day") String day) throws SQLException {
        if (deny("finance_approver", "system_admin")) {
            return Map.of("error", "forbidden");
        }
        LocalDate target = day == null ? LocalDate.now().minusDays(1) : LocalDate.parse(day);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT eim_ops.seal_audit_day(?)")) {
            ps.setDate(1, java.sql.Date.valueOf(target));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Map.of("day", target.toString(), "result", rs.getString(1));
            }
        }
    }

    /** AUD-6 verification: recomputes every seal; ok=false pages (runbook). */
    @GET
    @Path("/seals/verify")
        public List<Map<String, Object>> verifySeals() throws SQLException {
        if (deny("finance_approver", "system_admin")) {
            return List.of(Map.of("error", "forbidden"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seal_day::text, ok, detail FROM eim_ops.verify_audit_seals()");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(Map.of("day", rs.getString(1), "ok", rs.getBoolean(2), "detail", rs.getString(3)));
            }
        }
        return out;
    }

    /** DAT-4 reconciliation view: graph drift lines (empty = clean). */
    @GET
    @Path("/graph/reconcile")
        public Map<String, Object> reconcileGraph() throws SQLException {
        if (deny("comp_admin", "system_admin")) {
            return Map.of("error", "forbidden");
        }
        List<String> drift = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT drift FROM eim_ops.reconcile_graph()");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                drift.add(rs.getString(1));
            }
        }
        return Map.of("drift", drift, "clean", drift.isEmpty());
    }
}
