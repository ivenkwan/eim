package io.eim.coreapi.migration;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin REST client to the plan-compiler publish gate (FR-406) used by the
 * legacy-SPM importer: migration PLAN rows go through exactly the same
 * type-check/tier-contiguity/registry gate as interactive publishing — no
 * side door. The endpoint is the in-cluster service address (fixed origin,
 * never caller-supplied — SSRF posture), and the auth header rides the
 * caller's own gateway token when present.
 */
@ApplicationScoped
public class PlanPublishClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @ConfigProperty(name = "eim.migration.plan-compiler.url",
            defaultValue = "http://eim-plan-compiler:8084")
    String planCompilerUrl;

    private PlanPublishClient() {
    }

    /**
     * POST the model JSON to /api/v1/plans/publish and return the plan
     * version hash from the response (null on any non-200).
     */
    public String publishModel(String tenantId, String actor, String modelJson) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(planCompilerUrl + "/api/v1/plans/publish"))
                    .header("Content-Type", "application/json")
                    .header("X-Tenant-Id", tenantId)
                    .header("X-Actor", actor)
                    .header("X-Roles", "comp_admin")
                    .POST(HttpRequest.BodyPublishers.ofString(modelJson))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return extract(resp.body(), "planVersionHash");
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + key.length() + 4;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }
}
