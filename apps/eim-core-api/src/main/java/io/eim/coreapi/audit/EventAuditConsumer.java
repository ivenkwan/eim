package io.eim.coreapi.audit;

import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Event auditor (FR-612.1/612.2): persists every consumed Kafka event with
 * payload + sha256 + offsets. Write failures dead-letter (DLQ topic in
 * application.properties) and are recorded with outcome DEAD_LETTERED.
 */
@ApplicationScoped
public class EventAuditConsumer {

    private static final Logger LOG = Logger.getLogger(EventAuditConsumer.class);

    @Inject
    EventAuditService eventAudit;

    // One auditor for every audited domain topic (NFR-64): channels are
    // declared per-topic in application.properties; the Kafka metadata
    // carries the real topic name into event_audit_log.
    @Incoming("config-events-in")
    @Incoming("sync-events-in")
    @Incoming("trueup-events-in")
    @Incoming("hierarchy-events-in")
    public CompletionStage<Void> onEvent(Message<String> msg) {
        IncomingKafkaRecordMetadata<Long, String> meta =
                msg.getMetadata(IncomingKafkaRecordMetadata.class).orElse(null);
        String topic = meta != null ? meta.getTopic() : "eim.config.changed";
        String eventId = header(meta, "event-id");
        String correlationId = header(meta, "correlation-id");
        String payload = msg.getPayload();
        try {
            eventAudit.recordConsumed(topic, eventId, payload,
                    "eim-core-api", "eim-core-api-auditor", correlationId, null, "SUCCESS", null);
            return msg.ack();
        } catch (RuntimeException e) {
            LOG.warnf(e, "event audit write failed for topic %s — nacking to DLQ", topic);
            try {
                eventAudit.recordConsumed(topic, eventId, payload,
                        "eim-core-api", "eim-core-api-auditor", correlationId, null,
                        "DEAD_LETTERED", String.valueOf(e.getMessage()));
            } catch (RuntimeException ignored) {
                // best-effort record; the DLQ retains the raw event
            }
            // The audit plane must not crash the app: the failure row is
            // recorded and the raw event stays in the DLQ topic for replay.
            return msg.ack();
        }
    }

    private String header(IncomingKafkaRecordMetadata<Long, String> meta, String key) {
        if (meta == null) {
            return null;
        }
        Header h = meta.getHeaders().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
