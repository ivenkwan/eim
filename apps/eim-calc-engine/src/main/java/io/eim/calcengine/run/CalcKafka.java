package io.eim.calcengine.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * calc.run orchestration over Kafka (arch §7.1, TG3-CAL-14): consumes
 * eim.calc.run.requested, executes the wavefront run, announces
 * eim.calc.run.completed with the run stats, and records a CALCULATION_RUN
 * audit row for every attempt (FR-612.4).
 */
@ApplicationScoped
public class CalcKafka {

    private static final Logger LOG = Logger.getLogger(CalcKafka.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    WavefrontRunner runner;

    @Inject
    AgroalDataSource ds;

    @Inject
    @Channel("calc-run-out")
    Emitter<String> completed;

    @Incoming("calc-run-in")
    public CompletionStage<Void> onRunRequested(
            org.eclipse.microprofile.reactive.messaging.Message<String> msg) {
        String payload = msg.getPayload();
        try {
            JsonNode req = JSON.readTree(payload);
            UUID tenantId = UUID.fromString(req.path("tenantId").asText());
            UUID periodId = UUID.fromString(req.path("payoutPeriodId").asText());
            String hash = req.path("planVersionHash").asText();
            WavefrontRunner.RunStats stats = runner.run(tenantId, periodId, hash, null);
            audit(tenantId, "SUCCESS", stats);
            completed.send(JSON.writeValueAsString(java.util.Map.of(
                    "runId", stats.runId().toString(),
                    "status", stats.status(),
                    "payees", stats.payees(),
                    "transactions", stats.transactions(),
                    "ledgerEntries", stats.ledgerEntries(),
                    "durationMs", stats.durationMs())));
        } catch (Exception e) {
            LOG.errorf(e, "kafka-triggered calculation run failed");
            try {
                audit(UUID.nameUUIDFromBytes(payload.getBytes()),
                        "FAILED", String.valueOf(e.getMessage()));
            } catch (SQLException ignore) {
                // audit is best-effort here; the error is logged above
            }
        }
        return msg.ack();
    }

    private void audit(UUID tenantId, String outcome, WavefrontRunner.RunStats stats)
            throws SQLException {
        audit(tenantId, outcome, "waves=" + stats.waves() + " payees=" + stats.payees()
                + " entries=" + stats.ledgerEntries() + " ms=" + stats.durationMs());
    }

    private void audit(UUID tenantId, String outcome, String detail) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log (tenant_id, actor, actor_type, action_type, target_entity, "
                             + "target_record_id, before_state, after_state, reason_code, reason_comment, "
                             + "correlation_id, source_channel, outcome, occurred_at) "
                             + "VALUES (?::uuid,'calc-engine','SYSTEM','CALCULATION_RUN','calc_run',"
                             + "'runtime',NULL,?::jsonb,NULL,'calculation run',"
                             + "gen_random_uuid()::text,'KAFKA',?,now())")) {
            ps.setObject(1, tenantId);
            ps.setString(2, "{\"detail\":\"" + detail.replace("\"", "'") + "\"}");
            ps.setString(3, outcome);
            ps.executeUpdate();
        }
    }
}
