package io.eim.connectors.connector;

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
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HubSpot connector (FR-201.1/TG2-ING-3): private-app bearer token against
 * CRM v3 deals, normalized into the canonical record shape and pushed
 * through the shared ingestion engine (quarantine/watermark/audit/true-up).
 * Verified against WireMock; the portal endpoint comes from config — no
 * credentials in source.
 */
@Path("/api/v1/ingestion")
@Produces(MediaType.APPLICATION_JSON)
public class HubspotResource {

    private static final Logger LOG = Logger.getLogger(HubspotResource.class);

    @Inject
    IngestionService ingestion;

    @Inject
    @RestClient
    HubspotClient client;

    @POST
    @Path("hubspot/run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(jakarta.json.JsonObject request,
                        @Context ContainerRequestContext req) {
        TenantContext ctx = (TenantContext) req.getProperty(
                io.eim.connectors.common.TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            return Response.status(500).entity(Map.of("error", "tenant context missing")).build();
        }
        // Private-app token rides the request body (from the secret store at
        // call time) — never source, never logs.
        String token = request.getString("token");
        String watermark = request.containsKey("watermark") && !request.isNull("watermark")
                ? request.getString("watermark") : null;
        String defaultCurrency = request.containsKey("default_currency") && !request.isNull("default_currency")
                ? request.getString("default_currency") : "USD";

        HubspotClient.DealsPage page = client.deals("Bearer " + token, 100, watermark);
        List<Map<String, Object>> records = new ArrayList<>();
        for (HubspotClient.Deal deal : page.results()) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("external_deal_id", deal.id());
            rec.put("close_date", isoDate(deal.properties().get("closedate")));
            rec.put("amount", deal.properties().get("amount"));
            rec.put("currency", firstNonBlank(deal.properties().get("currency"), defaultCurrency));
            Object email = deal.properties().get("payee_email");
            if (email != null && !String.valueOf(email).isBlank()) {
                rec.put("payee_email", email);
            }
            records.add(rec);
        }
        IngestionService.BatchResult result = ingestion.ingest(
                ctx, "HUBSPOT", "deals", "external_deal_id",
                watermark, records,
                CsvResource.mappingOf(CsvResource.mappingFrom(request.getJsonObject("mapping"))));
        LOG.infof("HubSpot sync: %d accepted / %d quarantined",
                result.accepted(), result.quarantined());
        return Response.accepted(Map.of(
                "batchId", result.batchId().toString(),
                "accepted", result.accepted(),
                "quarantined", result.quarantined(),
                "watermarkAfter", result.watermarkAfter() == null ? "" : result.watermarkAfter())).build();
    }

    /** HubSpot closedate is epoch millis or ISO — normalize to ISO date. */
    private static String isoDate(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        if (s.matches("\\d{10,}")) {
            return LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(Long.parseLong(s)),
                    java.time.ZoneOffset.UTC).toString();
        }
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    private static String firstNonBlank(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }
}
