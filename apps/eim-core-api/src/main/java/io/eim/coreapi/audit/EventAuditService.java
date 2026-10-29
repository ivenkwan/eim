package io.eim.coreapi.audit;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;

/**
 * Event-audit writer (FR-612): every consumed Kafka event is persisted with
 * its full payload, sha256 hash and schema version. Idempotent under
 * at-least-once delivery via the dedupe index (topic, event_id).
 */
@ApplicationScoped
public class EventAuditService {

    @Inject
    AgroalDataSource ds;

    public void recordConsumed(String topic, String eventId, String payload,
                               String producer, String consumer, String correlationId,
                               String tenantId, String outcome, String errorDetail) {
        String hash = sha256(payload);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_audit_log (event_id, topic, payload, payload_hash, "
                             + "producer, consumer, correlation_id, tenant_id, outcome, error_detail, occurred_at) "
                             + "VALUES (?,?,?::jsonb,?,?,?,?,?,?,?,now()) "
                             + "ON CONFLICT DO NOTHING")) {
            ps.setString(1, eventId);
            ps.setString(2, topic);
            ps.setString(3, payload);
            ps.setString(4, hash);
            ps.setString(5, producer);
            ps.setString(6, consumer);
            ps.setString(7, correlationId);
            ps.setObject(8, tenantId == null || tenantId.isBlank() ? null : java.util.UUID.fromString(tenantId));
            ps.setString(9, outcome);
            ps.setString(10, errorDetail);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("event-audit write failed", e);
        }
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
