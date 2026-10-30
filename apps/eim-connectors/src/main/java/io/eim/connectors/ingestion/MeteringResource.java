package io.eim.connectors.ingestion;

import io.agroal.api.AgroalDataSource;
import io.eim.connectors.audit.AuditService;
import io.eim.connectors.common.TenantContext;
import io.eim.connectors.common.TenantContextFilter;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * High-volume usage/metering ingestion (TG7-ING-2, FR-205/NFR-21): bulk
 * newline-delimited meter events (meter_id,payee_id,date,quantity) at ≥1M
 * rows per tenant-month. Rows stream into raw_transaction as METERING
 * source rows via JDBC batching (2,000/batch) with quota enforcement from
 * the same tenant-quota registry as the CRM connectors.
 */
@Path("/api/v1/ingestion")
@Produces(MediaType.APPLICATION_JSON)
public class MeteringResource {

    private static final int BATCH_SIZE = 2_000;

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    @Inject
    IngestionService ingestion;

    @POST
    @Path("metering")
    @PermitAll
    @Consumes("text/csv")
    @Transactional
    public Response ingest(String csv, @Context ContainerRequestContext request) {
        TenantContext ctx = ctx(request);
        if (!ctx.hasRole("comp_admin") && !ctx.hasRole("system_admin")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "comp_admin or system_admin required")).build();
        }
        if (csv == null || csv.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "empty metering payload")).build();
        }
        long quota = ingestion.tenantQuotaRows(ctx);
        UUID batchId = UUID.randomUUID();
        int total = 0;
        long start = System.nanoTime();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, "
                             + "external_deal_id, transaction_date, booking_amount, currency_code, "
                             + "ingestion_batch_id) VALUES (?,CAST(? AS uuid),'METERING',?,?,"
                             + "CAST(? AS numeric),'USD',CAST(? AS uuid))")) {
            List<String> pending = new ArrayList<>();
            for (String line : csv.split("\n", -1)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("meter_id") || t.startsWith("#")) {
                    continue;
                }
                pending.add(t);
            }
            if (pending.size() > quota) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "tenant quota exceeded (NFR-33)",
                                "rows", pending.size(), "quota", quota)).build();
            }
            for (String t : pending) {
                String[] cols = t.split("[,;]");
                if (cols.length < 4) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "bad line (meter_id,payee_id,date,quantity): "
                                    + t)).build();
                }
                try {
                    LocalDate.parse(cols[2].trim());
                    new BigDecimal(cols[3].trim());
                } catch (Exception e) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "bad date or quantity: " + t)).build();
                }
                ps.setObject(1, UUID.nameUUIDFromBytes((batchId + ":" + cols[0].trim()
                        + ":" + total).getBytes()));
                ps.setString(2, ctx.tenantId());
                ps.setString(3, cols[0].trim());
                ps.setDate(4, java.sql.Date.valueOf(cols[2].trim()));
                ps.setBigDecimal(5, new BigDecimal(cols[3].trim()));
                ps.setObject(6, batchId);
                ps.addBatch();
                total++;
                if (total % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new WebApplicationException("metering insert failed: " + e.getMessage(), 500);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        audit.record(ctx, "CONNECTOR_RUN", "raw_transaction", batchId.toString(),
                "{\"rows\":" + total + ",\"ms\":" + ms + "}",
                "usage/metering bulk load",
                "eim.sync.completed", batchId.toString(),
                "{\"rows\":" + total + ",\"batch\":\"" + batchId + "\"}");
        return Response.accepted(Map.of("rows", total, "batchId", batchId.toString(),
                "ms", ms)).build();
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
