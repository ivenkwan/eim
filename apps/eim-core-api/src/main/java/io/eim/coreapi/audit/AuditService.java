package io.eim.coreapi.audit;

import io.agroal.api.AgroalDataSource;
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
 * Audit-first mutation path (AD-9 / ADR-009): audit row + outbox row commit
 * in the SAME transaction as the caller's business write. If the audit insert
 * fails, the whole business change fails (FR-601.4).
 */
@ApplicationScoped
public class AuditService {

    @Inject
    AgroalDataSource ds;

    /**
     * Record an action-audit row and the corresponding outbox event inside the
     * caller's transaction. Parameterized statements only (NFR: SQL binding).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void record(TenantContext ctx, String actionType, String targetEntity,
                       String targetRecordId, String beforeState, String afterState,
                       String reasonCode, String reasonComment, String aggregateType,
                       String aggregateId, String eventPayload) {
        try (Connection c = ds.getConnection()) {
            insertAudit(c, ctx, actionType, targetEntity, targetRecordId,
                    beforeState, afterState, reasonCode, reasonComment);
            insertOutbox(c, ctx, aggregateType, aggregateId, eventPayload);
        } catch (SQLException e) {
            throw new IllegalStateException("audit write failed — aborting business change (FR-601.4)", e);
        }
    }

    private void insertAudit(Connection c, TenantContext ctx, String actionType,
                             String targetEntity, String targetRecordId,
                             String before, String after, String reasonCode,
                             String reasonComment) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log (tenant_id, actor, actor_type, action_type, target_entity, "
                        + "target_record_id, before_state, after_state, reason_code, reason_comment, "
                        + "correlation_id, source_channel, outcome, occurred_at) "
                        + "VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?)")) {
            ps.setObject(1, uuidOrNull(ctx.tenantId()));
            ps.setString(2, ctx.actor());
            ps.setString(3, ctx.actorType());
            ps.setString(4, actionType);
            ps.setString(5, targetEntity);
            ps.setString(6, targetRecordId);
            ps.setString(7, before);
            ps.setString(8, after);
            ps.setString(9, reasonCode);
            ps.setString(10, reasonComment);
            ps.setString(11, ctx.correlationId());
            ps.setString(12, "API");
            ps.setString(13, "SUCCESS");
            ps.setTimestamp(14, Timestamp.from(OffsetDateTime.now().toInstant()));
            ps.executeUpdate();
        }
    }

    private void insertOutbox(Connection c, TenantContext ctx, String aggregateType,
                              String aggregateId, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, "
                        + "correlation_id, tenant_id, occurred_at) VALUES (?,?,?::jsonb,?,?,now())")) {
            ps.setString(1, aggregateType);
            ps.setString(2, aggregateId);
            ps.setString(3, payload);
            ps.setString(4, ctx.correlationId());
            ps.setObject(5, uuidOrNull(ctx.tenantId()));
            ps.executeUpdate();
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
