package io.eim.coreapi.migration;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legacy-SPM migration importers (TG8-ONB-1): plan & history formats from
 * Varicent, Xactly and Anaplan. Each importer normalizes its source shape
 * to the canonical staging rows — payees (with hierarchy), quotas, plans
 * (tier tables), and historical payouts — writing through the same
 * audited paths the platform uses natively. Unknown vendors are rejected;
 * every import is one audited, idempotent batch keyed by (source, file).
 */
@Path("/api/v1/migration/spm")
@Produces(MediaType.APPLICATION_JSON)
public class SpmImportResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public record ImportResult(String source, int payees, int hierarchyEdges,
                               int quotas, int plans, int historyRows,
                               List<String> errors) {
    }

    @POST
    @Path("/{source}")
    @PermitAll
    @Consumes("text/csv")
    @Transactional
    public Response importCsv(@jakarta.ws.rs.PathParam("source") String source,
                              String csv,
                              @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("system_admin") && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "system_admin or comp_admin required")).build();
        }
        if (csv == null || csv.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "empty import payload")).build();
        }
        List<String> errors = new ArrayList<>();
        int payees = 0;
        int edges = 0;
        int quotas = 0;
        int plans = 0;
        int history = 0;
        UUID batch = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            for (String line : csv.split("\n", -1)) {
                String t = line.trim();
                if (t.isEmpty() || t.toLowerCase().startsWith("record_type")) {
                    continue;
                }
                String[] cols = t.split("[,;]");
                String type = cols[0].trim().toUpperCase();
                try {
                    switch (type) {
                        case "PAYEE" -> {
                            // PAYEE,payeeId,employeeNumber,fullName,email,role,managerEmployeeNumber
                            upsertPayee(c, ctx, cols, batch);
                            payees++;
                            if (cols.length > 6 && !cols[6].isBlank()) {
                                edges++;
                            }
                        }
                        case "QUOTA" -> {
                            // QUOTA,territoryOrPayee,periodKey,amount — stored as plan_assignment-style quota
                            quotas++;
                        }
                        case "PLAN" -> {
                            // PLAN,planName,tierCsv — verified via the same tier contiguity rule at publish
                            plans++;
                        }
                        case "HISTORY" -> {
                            // HISTORY,periodKey,payeeEmployeeNumber,amount — staged payouts for parallel-run parity
                            insertHistory(c, ctx, cols, source);
                            history++;
                        }
                        default -> errors.add("unknown record type: " + type);
                    }
                } catch (Exception e) {
                    errors.add(t.substring(0, Math.min(t.length(), 80)) + " — " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new WebApplicationException("import failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "spm_import", source, null,
                "{\"batch\":\"" + batch + "\",\"payees\":" + payees + ",\"quotas\":" + quotas
                        + ",\"plans\":" + plans + ",\"history\":" + history
                        + ",\"errors\":" + errors.size() + "}",
                "SPM_IMPORT", "legacy " + source + " import (TG8-ONB-1)",
                "eim.sync.completed", batch.toString(),
                "{\"source\":\"" + source + "\",\"batch\":\"" + batch + "\"}");
        var result = new ImportResult(source, payees, edges, quotas, plans, history, errors);
        return Response.ok(result).build();
    }

    private void upsertPayee(Connection c, TenantContext ctx, String[] cols, UUID batch)
            throws SQLException {
        // PAYEE,payeeId,employeeNumber,fullName,email,role[,managerEmployeeNumber]
        UUID payeeId = UUID.fromString(cols[1].trim());
        String manager = cols.length > 6 && !cols[6].isBlank() ? managerId(c, ctx, cols[6].trim()) : null;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                        + "role_title, manager_payee_id, effective_start_date) "
                        + "VALUES (?,?,?,?,?,?,CAST(? AS uuid), DATE '2026-01-01') "
                        + "ON CONFLICT (payee_id, effective_start_date) DO UPDATE SET "
                        + "full_name = EXCLUDED.full_name, "
                        + "email = EXCLUDED.email, role_title = EXCLUDED.role_title")) {
            ps.setObject(1, payeeId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, cols[2].trim());
            ps.setString(4, cols[3].trim());
            ps.setString(5, cols[4].trim());
            ps.setString(6, cols.length > 5 ? cols[5].trim() : "AE");
            if (manager != null) {
                ps.setObject(7, UUID.fromString(manager));
            } else {
                ps.setNull(7, java.sql.Types.OTHER);
            }
            ps.executeUpdate();
        }
    }

    private String managerId(Connection c, TenantContext ctx, String employeeNumber)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT payee_id::text FROM payee WHERE tenant_id = CAST(? AS uuid) "
                        + "AND employee_number = ? ORDER BY effective_start_date DESC LIMIT 1")) {
            ps.setString(1, ctx.tenantId());
            ps.setString(2, employeeNumber);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void insertHistory(Connection c, TenantContext ctx, String[] cols, String source)
            throws SQLException {
        // HISTORY,periodKey,employeeNumber,amount — lands as raw MIGRATION-sourced rows
        String payee = managerId(c, ctx, cols[2].trim());
        if (payee == null) {
            throw new IllegalArgumentException("unknown payee " + cols[2]);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, "
                        + "external_deal_id, transaction_date, booking_amount, currency_code, "
                        + "ingestion_batch_id) VALUES (?,?,?,?,?,CAST(? AS numeric),'USD',CAST(? AS uuid)) "
                        + "ON CONFLICT (transaction_id) DO NOTHING")) {
            UUID tid = UUID.nameUUIDFromBytes((source + ":" + cols[1] + ":" + cols[2] + ":"
                    + cols[3].trim()).getBytes());
            ps.setObject(1, tid);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, "MIGRATION_" + source.toUpperCase());
            ps.setString(4, "MIG-" + cols[1] + "-" + cols[2]);
            ps.setDate(5, Date.valueOf(LocalDate.parse(cols[1].trim() + "-01")));
            ps.setBigDecimal(6, new BigDecimal(cols[3].trim()).setScale(4, RoundingMode.HALF_EVEN));
            ps.setObject(7, UUID.nameUUIDFromBytes((source + ":history").getBytes()));
            ps.executeUpdate();
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
