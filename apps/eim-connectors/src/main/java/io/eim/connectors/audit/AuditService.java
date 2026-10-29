package io.eim.connectors.audit;

import io.agroal.api.AgroalDataSource;
import io.eim.connectors.common.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit-first mutation path for the connectors service (AD-9 / ADR-009):
 * audit row + outbox row commit in the SAME transaction as the ingestion or
 * hierarchy write. Audit failure aborts the business change (FR-601.4).
 * Parameterized statements only.
 */
@ApplicationScoped
public class AuditService {

    @Inject
    AgroalDataSource ds;

    @Transactional(Transactional.TxType.MANDATORY)
    public void record(TenantContext ctx, String actionType, String targetEntity,
                       String targetRecordId, String detailJson, String reason,
                       String aggregateType, String aggregateId, String eventPayload) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_log (tenant_id, actor, actor_type, action_type, target_entity, "
                            + "target_record_id, before_state, after_state, reason_code, reason_comment, "
                            + "correlation_id, source_channel, outcome, occurred_at) "
                            + "VALUES (?,?,?,?,?,?,NULL,?::jsonb,NULL,?,?,?,'SUCCESS',now())")) {
                ps.setObject(1, uuidOrNull(ctx.tenantId()));
                ps.setString(2, ctx.actor());
                ps.setString(3, "USER");
                ps.setString(4, actionType);
                ps.setString(5, targetEntity);
                ps.setString(6, targetRecordId);
                ps.setString(7, detailJson);
                ps.setString(8, reason);
                ps.setString(9, ctx.correlationId());
                ps.setString(10, "API");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, "
                            + "correlation_id, tenant_id, occurred_at) VALUES (?,?,?::jsonb,?,?,now())")) {
                ps.setString(1, aggregateType);
                ps.setString(2, aggregateId);
                ps.setString(3, eventPayload);
                ps.setString(4, ctx.correlationId());
                ps.setObject(5, uuidOrNull(ctx.tenantId()));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("audit write failed — aborting business change (FR-601.4)", e);
        }
    }

    private UUID uuidOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
