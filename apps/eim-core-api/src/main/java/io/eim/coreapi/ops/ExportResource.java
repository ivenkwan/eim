package io.eim.coreapi.ops;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import io.eim.coreapi.audit.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Payroll export (TG4-EXP-1/2, FR-801/601.2): one net line per payee keyed
 * by employee_number from ADP/Workday/NetSuite-journal CSV templates,
 * negative-net flagging, sha256 file-hash logging, and byte-identical
 * regeneration for locked periods (idempotent rows straight from the
 * committed ledger). Every generation is audited EXPORT_GENERATION.
 */
@Path("/api/v1/admin/exports")
@Produces(MediaType.APPLICATION_JSON)
public class ExportResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public record ExportRequest(String periodId, String template) {
    }

    @POST
    @PermitAll
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response generate(ExportRequest body,
                             @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String periodId = body == null ? null : body.periodId();
        String template = body == null ? null : body.template();
        return doGenerate(ctx, periodId, template, request);
    }

    @GET
    @PermitAll
    public Response generateGet(@QueryParam("periodId") String periodId,
                                @QueryParam("template") String template,
                                @QueryParam("format") String format,
                                @Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        Response result = doGenerate(ctx, periodId, template, request);
        if ("file".equals(format)) {
            // stream the CSV bytes directly (download path)
            Object entity = result.getEntity();
            if (entity instanceof Map<?, ?> m && m.get("csv") != null) {
                return Response.ok(m.get("csv"))
                        .header("Content-Disposition",
                                "attachment; filename=payroll-" + template + "-" + periodId + ".csv")
                        .type("text/csv").build();
            }
        }
        return result;
    }

    private Response doGenerate(TenantContext ctx, String periodId, String template,
                                ContainerRequestContext request) throws SQLException {
        if (periodId == null || periodId.isBlank() || template == null || template.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "periodId and template are required")).build();
        }
        List<String> allowed = List.of("ADP", "WORKDAY", "NETSUITE_JOURNAL");
        if (!allowed.contains(template)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "template must be one of " + allowed)).build();
        }

        List<String[]> lines = new ArrayList<>(); // employee_number, name, net, negative flag
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT p.employee_number, p.full_name, sum(l.payout_amount)::text "
                             + "FROM commission_ledger_entry l JOIN payee p "
                             + "ON p.payee_id = l.payee_id "
                             + "WHERE l.payout_period_id = CAST(? AS uuid) "
                             + "AND l.entry_type IN ('CALCULATED','MANUAL_OVERRIDE',"
                             + "'DRAW_ADVANCE','DRAW_RECOVERY','CLAWBACK','PRIOR_PERIOD_TRUEUP') "
                             + "GROUP BY p.employee_number, p.full_name "
                             + "ORDER BY p.employee_number")) {
            ps.setString(1, periodId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String net = rs.getString(3);
                    boolean negative = new java.math.BigDecimal(net).signum() < 0;
                    lines.add(new String[]{rs.getString(1), rs.getString(2), net,
                            negative ? "Y" : "N"});
                }
            }
        }

        String csv = render(template, lines);
        String hash = sha256(csv);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO export_run (export_id, tenant_id, payout_period_id, template, "
                             + "file_hash, row_count, created_by) "
                             + "VALUES (gen_random_uuid(),CAST(? AS uuid),CAST(? AS uuid),"
                             + "?,?,?,?)")) {
            ps.setString(1, ctx.tenantId());
            ps.setString(2, periodId);
            ps.setString(3, template);
            ps.setString(4, hash);
            ps.setInt(5, lines.size());
            ps.setString(6, ctx.actor());
            ps.executeUpdate();
        }

        audit.record(ctx, "EXPORT_GENERATION", "export_run", hash,
                null,
                "{\"template\":\"" + template + "\",\"rows\":" + lines.size()
                        + ",\"file_hash\":\"" + hash + "\"}",
                "PAYROLL_EXPORT", null, "export.generated", hash,
                "{\"template\":\"" + template + "\",\"period\":\"" + periodId + "\"}");

        return Response.ok(Map.of(
                "template", template,
                "rows", lines.size(),
                "fileHash", hash,
                "csv", csv)).build();
    }

    /** Template rendering — byte-identical for the same ledger content. */
    private static String render(String template, List<String[]> lines) {
        StringBuilder csv = new StringBuilder();
        switch (template) {
            case "ADP" -> {
                csv.append("Employee ID,Employee Name,Net Pay,Negative Net Flag\n");
                for (String[] l : lines) {
                    csv.append(l[0]).append(",\"").append(l[1]).append("\",")
                            .append(l[2]).append(',').append(l[3]).append('\n');
                }
            }
            case "WORKDAY" -> {
                csv.append("Worker ID,Worker,Total Net,Negative\n");
                for (String[] l : lines) {
                    csv.append(l[0]).append(",\"").append(l[1]).append("\",")
                            .append(l[2]).append(',').append(l[3]).append('\n');
                }
            }
            case "NETSUITE_JOURNAL" -> {
                csv.append("Employee No,Employee,Amount,Negative Net\n");
                for (String[] l : lines) {
                    csv.append(l[0]).append(",\"").append(l[1]).append("\",")
                            .append(l[2]).append(',').append(l[3]).append('\n');
                }
            }
            default -> throw new IllegalArgumentException(template);
        }
        return csv.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private TenantContext ctx(ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            throw new jakarta.ws.rs.WebApplicationException(Response.status(500)
                    .entity(Map.of("error", "tenant context missing")).build());
        }
        return ctx;
    }
}
