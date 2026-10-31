package io.eim.connectors.ingestion;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Raw source snapshot offload (TG2-ING-8 debt, closed in TG-9 / FR-203.3):
 * every ingestion batch's raw payload is written to the S3 objectstore
 * under a versioned, hash-named key BEFORE parsing — the DB row keeps the
 * batch lineage, the objectstore keeps the immutable bytes. The stored
 * sha256 lets the audit plane verify the snapshot against the object later.
 *
 * The objectstore endpoint is service-internal (SSRF posture: only the
 * configured origin is ever addressed, never a caller-supplied URL).
 */
@ApplicationScoped
public class SourceSnapshotOffloader {

    private static final Logger LOG = Logger.getLogger(SourceSnapshotOffloader.class);

    @ConfigProperty(name = "eim.ingestion.snapshot-offload.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "eim.ingestion.snapshot-offload.endpoint",
            defaultValue = "http://eim-objectstore:9000")
    String endpoint;

    @ConfigProperty(name = "eim.ingestion.snapshot-offload.bucket", defaultValue = "eim-raw-snapshots")
    String bucket;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    AgroalDataSource ds;

    /**
     * Offload the raw payload for a batch. Returns the object key (null when
     * disabled), recording the key + sha256 on the ingestion_batch row.
     */
    public String offload(java.util.UUID batchId, String tenantId, String objectName, String payload) {
        if (!enabled || payload == null || payload.isEmpty()) {
            return null;
        }
        String hash = sha256(payload);
        String key = objectName + "/" + batchId + "-" + hash + ".raw";
        int code = put(key, payload);
        if (code != 200) {
            // offload failure must not block ingestion (the DB payload remains
            // the source of truth); it is a warning for the runbook
            LOG.warnf("snapshot offload returned %d for batch %s — DB payload retained", code, batchId);
            return null;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ingestion_batch SET snapshot_object_key = ? "
                             + "WHERE ingestion_batch_id = CAST(? AS uuid)")) {
            ps.setString(1, key);
            ps.setString(2, batchId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "snapshot key recording failed for batch %s", batchId);
        }
        return key;
    }

    private int put(String key, String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/" + bucket + "/" + encode(key)))
                    .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/octet-stream")
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        } catch (IOException | InterruptedException e) {
            LOG.warnf("snapshot offload unreachable: %s", e.getMessage());
            return -1;
        }
    }

    private static String encode(String key) {
        return key.replaceAll("[^a-zA-Z0-9/_-]", "_");
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(input.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
