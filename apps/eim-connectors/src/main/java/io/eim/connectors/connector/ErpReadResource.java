package io.eim.connectors.connector;

import io.agroal.api.AgroalDataSource;
import io.eim.connectors.audit.AuditService;
import io.eim.connectors.common.TenantContext;
import io.eim.connectors.common.TenantContextFilter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERP reads (TG7-ING-1, FR-206.1): pull bookings/contracts with recognized
 * revenue from NetSuite (SuiteQL) or SAP (OData) into `erp_revenue_event`,
 * feeding PO-weighted amortization (FR-902.3) and the completeness picture.
 * Credentials come from environment-configured secret references — never
 * literals (NFR-53); the token is fetched per call and discarded.
 */
@Path("/api/v1/erp")
@Produces(MediaType.APPLICATION_JSON)
public class ErpReadResource {

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @Inject
    @org.eclipse.microprofile.rest.client.inject.RestClient
    ErpClient.NetSuite netSuite;

    @Inject
    @org.eclipse.microprofile.rest.client.inject.RestClient
    ErpClient.Sap sap;

    @ConfigProperty(name = "erp.netsuite.token", defaultValue = "${ERP_NETSUITE_TOKEN:}")
    String netSuiteToken;

    @ConfigProperty(name = "erp.sap.token", defaultValue = "${ERP_SAP_TOKEN:}")
    String sapToken;

    @POST
    @Path("netsuite/revenue-events")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response readNetSuite(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String bearer = netSuiteToken.isBlank()
                ? request.getHeaderString("X-Erp-Token") : netSuiteToken;
        if (bearer == null || bearer.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "ERP token not configured (set erp.netsuite.token "
                            + "from the secret store) or X-Erp-Token header")).build();
        }
        String q = "SELECT contract_ref, period_key, revenue_amount, customer, subsidiary "
                + "FROM revenue_events";
        ErpClient.NetSuite.SuiteQlResponse resp;
        try {
            resp = netSuite.suiteql("Bearer " + bearer, q);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "NetSuite read failed: " + e.getMessage())).build();
        }
        List<ErpClient.NetSuite.SuiteQlRow> items =
                resp == null || resp.items() == null ? List.of() : resp.items();
        int rows = persist(ctx, items.size(), i -> {
            ErpClient.NetSuite.SuiteQlRow item = items.get(i);
            return Map.of(
                    "contractRef", nullSafe(item.contract_ref()),
                    "periodKey", nullSafe(item.period_key()),
                    "amount", item.revenue_amount() == null ? "0"
                            : new BigDecimal(item.revenue_amount().toString())
                                    .setScale(4, RoundingMode.HALF_EVEN).toPlainString());
        }, "NETSUITE");
        audit.record(ctx, "CONNECTOR_RUN", "erp_revenue_event", "netsuite",
                "{\"rows\":" + rows + "}", "NetSuite revenue events pulled",
                "eim.sync.completed", "netsuite", "{\"rows\":" + rows + "}");
        return Response.ok(Map.of("rows", rows, "source", "NETSUITE")).build();
    }

    @POST
    @Path("sap/contracts")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response readSap(JsonObject body, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("finance_approver")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or finance_approver required")).build();
        }
        String bearer = sapToken.isBlank() ? request.getHeaderString("X-Erp-Token") : sapToken;
        if (bearer == null || bearer.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "ERP token not configured (set erp.sap.token "
                            + "from the secret store) or X-Erp-Token header")).build();
        }
        ErpClient.Sap.OdataResponse resp;
        try {
            resp = sap.contracts("Bearer " + bearer, "IsActive eq true");
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "SAP read failed: " + e.getMessage())).build();
        }
        List<Map<String, String>> contracts = new ArrayList<>();
        for (var row : resp == null || resp.value() == null ? List.<ErpClient.Sap.OdataRow>of()
                : resp.value()) {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("contractRef", nullSafe(row.ContractRef()));
            c.put("customer", nullSafe(row.Customer()));
            c.put("currency", nullSafe(row.Currency()));
            c.put("startDate", nullSafe(row.StartDate()));
            c.put("endDate", nullSafe(row.EndDate()));
            contracts.add(c);
        }
        audit.record(ctx, "CONNECTOR_RUN", "erp_contract", "sap",
                "{\"contracts\":" + contracts.size() + "}", "SAP contracts pulled",
                "eim.sync.completed", "sap", "{\"contracts\":" + contracts.size() + "}");
        return Response.ok(Map.of("contracts", contracts, "source", "SAP")).build();
    }

    // ---------------------------------------------------------------- helpers
    @FunctionalInterface
    private interface RowAdapter {
        Map<String, String> asMap(int index);
    }

    private int persist(TenantContext ctx, int count, RowAdapter adapter, String source) {
        int rows = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO erp_revenue_event (event_id, tenant_id, contract_ref, "
                             + "period_key, revenue_amount, source_system) "
                             + "VALUES (?,?,?,?,CAST(? AS numeric),?) "
                             + "ON CONFLICT (tenant_id, contract_ref, period_key, source_system) "
                             + "DO UPDATE SET revenue_amount = EXCLUDED.revenue_amount")) {
            for (int i = 0; i < count; i++) {
                Map<String, String> m = adapter.asMap(i);
                if (m.get("contractRef") == null || m.get("periodKey") == null) {
                    continue;
                }
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(ctx.tenantId()));
                ps.setString(3, m.get("contractRef"));
                ps.setString(4, m.get("periodKey"));
                ps.setBigDecimal(5, new BigDecimal(m.get("amount")));
                ps.setString(6, source);
                ps.addBatch();
                rows++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new WebApplicationException("erp_revenue_event write failed: " + e.getMessage(), 500);
        }
        return rows;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
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
