package io.eim.coreapi.audit;

import io.agroal.api.AgroalDataSource;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Outbox exporter (FR-601.4 / arch §12.2): drains committed outbox rows to
 * Kafka, then marks them published — no event without an audit record, and no
 * business change without an event trail. Crash-safe: rows are re-delivered
 * on failure; consumers are idempotent (NFR-04).
 */
@ApplicationScoped
public class OutboxExporter {

    private static final Logger LOG = Logger.getLogger(OutboxExporter.class);

    @Inject
    AgroalDataSource ds;

    @Inject
    @Channel("config-events-out")
    Emitter<String> configEvents;

    @Scheduled(every = "5s", identity = "outbox-exporter")
    void exportPending() {
        int published = 0;
        while (published < 200) {
            final Row row = nextUnpublished();
            if (row == null) {
                break;
            }
            OutgoingKafkaRecordMetadata<Object> metadata = OutgoingKafkaRecordMetadata.builder()
                    .withKey(row.aggregateId())
                    .withHeaders(java.util.List.of(
                            new RecordHeader("event-id", String.valueOf(row.outboxId()).getBytes(StandardCharsets.UTF_8)),
                            new RecordHeader("correlation-id",
                                    (row.correlationId() == null ? "" : row.correlationId())
                                            .getBytes(StandardCharsets.UTF_8))))
                    .build();
            configEvents.send(Message.of(row.payload())
                    .withMetadata(Metadata.of(metadata))
                    .withAck(() -> {
                        markPublished(row.outboxId());
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }));
            published++;
        }
        if (published > 0) {
            LOG.infof("outbox exported %d event(s)", published);
        }
    }

    private record Row(long outboxId, String aggregateType, String aggregateId,
                       String payload, String correlationId) {
    }

    private Row nextUnpublished() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT outbox_id, aggregate_type, aggregate_id, payload, correlation_id "
                             + "FROM transactional_outbox WHERE published_at IS NULL "
                             + "ORDER BY outbox_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)) : null;
        } catch (SQLException e) {
            LOG.warn("outbox poll failed", e);
            return null;
        }
    }

    private void markPublished(long outboxId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE transactional_outbox SET published_at = now(), attempts = attempts + 1 "
                             + "WHERE outbox_id = ?")) {
            ps.setLong(1, outboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "failed to mark outbox %d published — will re-export (idempotent consumers)", outboxId);
        }
    }
}
