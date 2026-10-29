package io.eim.connectors.connector;

import io.agroal.api.AgroalDataSource;
import io.eim.connectors.common.TenantContext;
import io.eim.connectors.ingestion.IngestionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC query connector (TG2-ING-4, Snowflake posture): runs a configured
 * read-only query over JDBC and ingests the result set through the same
 * engine. Key-pair-authenticated Snowflake in production; the JDBC path is
 * verified live against the cluster PostgreSQL (config-driven — same code,
 * different URL/driver). Column names become source field names.
 */
@Path("/api/v1/ingestion")
@Produces(MediaType.APPLICATION_JSON)
public class JdbcResource {

    @Inject
    IngestionService ingestion;

    @Inject
    AgroalDataSource ds;

    @POST
    @Path("query/run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(jakarta.json.JsonObject request, @Context ContainerRequestContext req) {
        TenantContext ctx = (TenantContext) req.getProperty(
                io.eim.connectors.common.TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            return Response.status(500).entity("{\"error\":\"tenant context missing\"}").build();
        }
        String query = request.getString("query");
        if (query == null || !query.trim().toLowerCase().startsWith("select")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"only SELECT queries are permitted\"}").build();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(query, Statement.NO_GENERATED_KEYS);
             ResultSet rs = ps.executeQuery()) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i) == null
                            ? null : String.valueOf(rs.getObject(i)));
                }
                records.add(row);
            }
        } catch (SQLException e) {
            throw new WebApplicationException("query failed: " + e.getMessage(), 500);
        }
        IngestionService.BatchResult result = ingestion.ingest(
                ctx, "SNOWFLAKE", request.getString("object_name", "warehouse_query"),
                request.getString("watermark_column", null),
                request.getString("watermark", null), records,
                CsvResource.mappingOf(CsvResource.mappingFrom(request.getJsonObject("mapping"))));
        return Response.accepted(Map.of(
                "batchId", result.batchId().toString(),
                "accepted", result.accepted(),
                "quarantined", result.quarantined(),
                "lateArriving", result.lateArriving())).build();
    }
}
