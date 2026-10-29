package io.eim.connectors.connector;

import io.eim.connectors.common.TenantContext;
import io.eim.connectors.ingestion.IngestionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Salesforce connector (FR-201.2/TG2-ING-2): OAuth 2.0 password-grant
 * against the token endpoint, then a SOQL query mapped through the same
 * ingestion engine. Verified against WireMock in the unit suite; the real
 * org endpoints come from config (SFDC_URI) — no credentials in source.
 */
@Path("/api/v1/ingestion")
@Produces(MediaType.APPLICATION_JSON)
public class SfdcResource {

    private static final Logger LOG = Logger.getLogger(SfdcResource.class);

    @Inject
    IngestionService ingestion;

    @Inject
    @RestClient
    SfdcClient client;

    @POST
    @Path("salesforce/run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(jakarta.json.JsonObject request,
                        @jakarta.ws.rs.core.Context jakarta.ws.rs.container.ContainerRequestContext req) {
        TenantContext ctx = (TenantContext) req.getProperty(
                io.eim.connectors.common.TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            return Response.status(500).entity("{\"error\":\"tenant context missing\"}").build();
        }
        // Token exchange: credentials ride the request body (from the secret
        // store at call time) — never source, never logs.
        SfdcClient.TokenResponse token = client.token(
                "password", request.getString("client_id"),
                request.getString("client_secret"), request.getString("username"),
                request.getString("password"));
        SfdcClient.QueryResponse queried = client.query(
                "Bearer " + token.accessToken(), request.getString("soql"));

        List<Map<String, Object>> records = new ArrayList<>();
        for (List<Object> row : queried.records()) {
            Map<String, Object> rec = new LinkedHashMap<>();
            for (int i = 0; i < row.size() && i < queried.fields().size(); i++) {
                rec.put(queried.fields().get(i), row.get(i));
            }
            records.add(rec);
        }
        IngestionService.BatchResult result = ingestion.ingest(
                ctx, "SALESFORCE", "opportunity", "SystemModStamp",
                (request.containsKey("watermark") ? request.getString("watermark") : null), records,
                CsvResource.mappingOf(CsvResource.mappingFrom(request.getJsonObject("mapping"))));
        LOG.infof("SFDC sync: %d accepted / %d quarantined",
                result.accepted(), result.quarantined());
        return Response.accepted(Map.of(
                "batchId", result.batchId().toString(),
                "accepted", result.accepted(),
                "quarantined", result.quarantined())).build();
    }
}
