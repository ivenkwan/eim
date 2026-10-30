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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV/XLSX upload connector (FR-201.1/201.5): manual upload with schema
 * validation, per-row quarantine and the full audit trail. Records are
 * comma-separated with a header row; the mapping comes from the request or
 * defaults to the canonical field names.
 */
@Path("/api/v1/ingestion/csv")
@Produces(MediaType.APPLICATION_JSON)
public class CsvResource {

    public record CsvUpload(String objectName, String csv, java.util.Map<String, String> mapping) {
    }

    public record CsvResult(boolean accepted, String batchId, int total, int ingested,
                            int quarantined, String watermarkAfter, int lateArriving) {
    }

    @Inject
    IngestionService ingestion;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response upload(CsvUpload upload, @Context ContainerRequestContext request) {
        TenantContext ctx = tenant(request);
        if (upload == null || upload.csv() == null || upload.csv().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("csv body is required")).build();
        }
        Map<String, String> mapping = mappingOf(upload.mapping());
        List<Map<String, Object>> records;
        try {
            records = parseCsv(upload.csv());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        }
        IngestionService.BatchResult result;
        try {
            result = ingestion.ingest(
                    ctx, "CSV",
                    upload.objectName() == null ? "manual_upload" : upload.objectName(),
                    "deal_id", null, records, mapping);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        }
        return Response.status(Response.Status.ACCEPTED).entity(new CsvResult(
                true, result.batchId().toString(), result.total(), result.accepted(),
                result.quarantined(), result.watermarkAfter(), result.lateArriving())).build();
    }

    /** Minimal RFC-4180-ish parser: header row + comma split + quote-aware. */
    static List<Map<String, Object>> parseCsv(String csv) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return out;
        }
        List<String> header = split(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            List<String> fields = split(lines.get(i));
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 0; col < header.size(); col++) {
                row.put(header.get(col), col < fields.size() ? fields.get(col) : null);
            }
            out.add(row);
        }
        return out;
    }

    private static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    static Map<String, String> mappingOf(java.util.Map<String, String> overrides) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("external_deal_id", "deal_id");
        mapping.put("transaction_date", "close_date");
        mapping.put("booking_amount", "amount");
        mapping.put("currency_code", "currency");
        mapping.put("payee_email", "payee_email");
        mapping.put("split_pct", "split_pct");
        if (overrides != null) {
            mapping.putAll(overrides);
        }
        return mapping;
    }

    /** JsonObject -> plain Map for the Jackson-friendly mappingOf. */
    static java.util.Map<String, String> mappingFrom(jakarta.json.JsonObject json) {
        if (json == null) {
            return null;
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, jakarta.json.JsonValue> e : json.entrySet()) {
            jakarta.json.JsonValue v = e.getValue();
            if (v != null && v.getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
                out.put(e.getKey(), ((jakarta.json.JsonString) v).getString());
            }
        }
        return out;
    }

    private TenantContext tenant(ContainerRequestContext request) {
        TenantContext ctx = (TenantContext) request.getProperty(
                io.eim.connectors.common.TenantContextFilter.CTX_PROPERTY);
        if (ctx == null || ctx.tenantId().isBlank()) {
            throw new WebApplicationException("tenant context missing", 500);
        }
        return ctx;
    }

    private String error(String message) {
        return "{\"error\":\"" + message + "\"}";
    }
}
