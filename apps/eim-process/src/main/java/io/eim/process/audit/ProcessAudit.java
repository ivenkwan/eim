package io.eim.process.audit;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Process audit sink: every process transition lands in the shared audit
 * plane with the correlation id (FR-612.3) — the same trail the E2E probe
 * follows through all three streams.
 */
@ApplicationScoped
public class ProcessAudit {

    @Inject
    AgroalDataSource ds;

    public void processEvent(String tenantId, String processType, String event,
                             String detailJson, String actor) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log (tenant_id, actor, actor_type, action_type, target_entity, "
                             + "target_record_id, before_state, after_state, reason_code, reason_comment, "
                             + "correlation_id, source_channel, outcome, occurred_at) "
                             + "VALUES (CAST(? AS uuid),?,'SYSTEM','APPROVAL','process_instance',"
                             + "?::text,NULL,CAST(? AS jsonb),NULL,?,gen_random_uuid()::text,"
                             + "'PROCESS','SUCCESS',now())")) {
            ps.setString(1, tenantId);
            ps.setString(2, actor == null ? "process-engine" : actor);
            ps.setString(3, processType);
            ps.setString(4, "{\"event\":\"" + event + "\",\"detail\":" + detailJson + "}");
            ps.setString(5, event);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("process audit write failed", e);
        }
    }
}
