package io.eim.coreapi.accounting;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accounting book policies (TG7-ACC-1/2, FR-901/904): each book carries its
 * own conventions, functional currency, expedient threshold, fringe rate and
 * toggleable matrix knobs. Creating a book generates the default ASC 606
 * classification decision table FROM the policy; Controllers may upload a
 * bespoke table instead — it is validated on the KIE DMN runtime before it
 * is stored, versioned per upload and audited as CONFIG_CHANGE (FR-613).
 */
@Path("/api/v1/accounting/books")
@Produces(MediaType.APPLICATION_JSON)
public class BookPolicyResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @Inject
    DmnClassifier classifier;

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver") && !ctx.hasRole("comp_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver (Controller) required")).build();
        }
        String code = body.getString("bookCode", null);
        String currency = body.getString("functionalCurrency", "USD");
        if (code == null || code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "bookCode is required")).build();
        }
        String convention = body.getString("amortizationConvention", "EXACT_DAILY");
        String sePolicy = body.getString("seSpiffPolicy", "FORMULAIC_ONLY");
        String renewalPolicy = body.getString("renewalPolicy", "CAP_NON_COMMENSURATE");
        String downgradePolicy = body.getString("downgradePolicy", "PRO_RATE_IMPAIR");
        boolean capMgr = body.getBoolean("capitalizeManagerOverrides", true);
        int expedient = body.getInt("expedientThresholdMonths", 12);
        java.math.BigDecimal fringe = body.getJsonNumber("fringeRate") != null
                ? body.getJsonNumber("fringeRate").bigDecimalValue()
                : new java.math.BigDecimal("0.08500");
        String dmn = DmnClassifier.defaultDmn(capMgr, sePolicy, renewalPolicy, expedient);
        List<String> problems = classifier.validate(dmn);
        if (!problems.isEmpty()) {
            throw new WebApplicationException("generated decision table invalid: " + problems, 500);
        }
        UUID bookId = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO accounting_book_policy (book_id, tenant_id, book_code, description, "
                             + "functional_currency, amortization_convention, expedient_threshold_months, "
                             + "fringe_rate, capitalize_manager_overrides, se_spiff_policy, renewal_policy, "
                             + "downgrade_policy, classification_dmn, classification_dmn_version) "
                             + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1)")) {
            ps.setObject(1, bookId);
            ps.setObject(2, UUID.fromString(ctx.tenantId()));
            ps.setString(3, code);
            ps.setString(4, body.getString("description", null));
            ps.setString(5, currency);
            ps.setString(6, convention);
            ps.setInt(7, expedient);
            ps.setBigDecimal(8, fringe);
            ps.setBoolean(9, capMgr);
            ps.setString(10, sePolicy);
            ps.setString(11, renewalPolicy);
            ps.setString(12, downgradePolicy);
            ps.setString(13, dmn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WebApplicationException("book insert failed: " + e.getMessage(), 500);
        }
        audit.record(ctx, "CONFIG_CHANGE", "accounting_book_policy", bookId.toString(),
                null,
                "{\"book\":\"" + code + "\",\"currency\":\"" + currency
                        + "\",\"dmn_version\":1}",
                "BOOK_CREATED", "accounting book registered with default classification matrix",
                "eim.accounting.classified", bookId.toString(),
                "{\"book\":\"" + code + "\",\"dmnVersion\":1}");
        return Response.ok(Map.of("bookId", bookId.toString(), "bookCode", code,
                "dmnVersion", 1)).build();
    }

    @GET
    @PermitAll
    public List<Map<String, Object>> list(@Context ContainerRequestContext request) throws SQLException {
        TenantContext ctx = ctx(request);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT book_id::text, book_code, description, functional_currency, "
                             + "amortization_convention, expedient_threshold_months, fringe_rate::text, "
                             + "capitalize_manager_overrides, se_spiff_policy, renewal_policy, "
                             + "downgrade_policy, classification_dmn_version, status "
                             + "FROM accounting_book_policy WHERE tenant_id = CAST(? AS uuid) "
                             + "ORDER BY book_code")) {
            ps.setString(1, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bookId", rs.getString(1));
                    row.put("bookCode", rs.getString(2));
                    row.put("description", rs.getString(3));
                    row.put("functionalCurrency", rs.getString(4));
                    row.put("amortizationConvention", rs.getString(5));
                    row.put("expedientThresholdMonths", rs.getInt(6));
                    row.put("fringeRate", rs.getString(7));
                    row.put("capitalizeManagerOverrides", rs.getBoolean(8));
                    row.put("seSpiffPolicy", rs.getString(9));
                    row.put("renewalPolicy", rs.getString(10));
                    row.put("downgradePolicy", rs.getString(11));
                    row.put("dmnVersion", rs.getInt(12));
                    row.put("status", rs.getString(13));
                    out.add(row);
                }
                return out;
            }
        }
    }

    /** Controller-editable decision table: validate on KIE, version, audit. */
    @PUT
    @Path("/{bookId}/dmn")
    @PermitAll
    @Consumes(MediaType.TEXT_XML)
    @Transactional
    public Response uploadDmn(@PathParam("bookId") String bookId, String dmnXml,
                              @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "finance_approver (Controller) required to edit the "
                            + "classification decision table")).build();
        }
        if (dmnXml == null || dmnXml.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "decision table XML body required")).build();
        }
        List<String> problems = classifier.validate(dmnXml);
        if (!problems.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "decision table failed DMN validation",
                            "problems", problems.toString())).build();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE accounting_book_policy SET classification_dmn = ?, "
                             + "classification_dmn_version = classification_dmn_version + 1, "
                             + "updated_at = now() "
                             + "WHERE book_id = CAST(? AS uuid) AND tenant_id = CAST(? AS uuid) "
                             + "RETURNING classification_dmn_version")) {
            ps.setString(1, dmnXml);
            ps.setString(2, bookId);
            ps.setString(3, ctx.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "unknown book for tenant")).build();
                }
                int version = rs.getInt(1);
                audit.record(ctx, "CONFIG_CHANGE", "accounting_book_policy", bookId,
                        null, "{\"dmn_version\":" + version + "}",
                        "CLASSIFICATION_TABLE_EDIT", "bespoke classification decision table uploaded",
                        "eim.accounting.classified", bookId,
                        "{\"book\":\"" + bookId + "\",\"dmnVersion\":" + version + "}");
                return Response.ok(Map.of("bookId", bookId, "dmnVersion", version)).build();
            }
        } catch (SQLException e) {
            throw new WebApplicationException("dmn update failed: " + e.getMessage(), 500);
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
