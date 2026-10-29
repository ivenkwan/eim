package io.eim.coreapi.config;

import io.agroal.api.AgroalDataSource;
import io.eim.coreapi.audit.AuditService;
import io.eim.coreapi.audit.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned configuration registry (FR-613): every change writes a
 * configuration_change audit record and an outbox event in the same
 * transaction (AD-9). Governed entities land PENDING_APPROVAL and only
 * activate on explicit approval (FR-613.2).
 */
@ApplicationScoped
public class ConfigRegistryService {

    private static final java.util.Set<String> GOVERNED_ENTITIES = java.util.Set.of(
            "policy.asc606", "rbac", "gateway.policy", "workflow.definition", "plan.published");

    @Inject
    AgroalDataSource ds;

    @Inject
    AuditService audit;

    public record ConfigVersion(UUID id, String entity, int version, String payload,
                                boolean governed, String status) {
    }

    @Transactional
    public ConfigVersion submit(TenantContext ctx, String entity, String payload, String reason) {
        boolean governed = GOVERNED_ENTITIES.contains(entity);
        int nextVersion = latestVersion(entity) + 1;
        UUID id = UUID.randomUUID();
        String status = governed ? "PENDING_APPROVAL" : "ACTIVE";

        try (Connection c = ds.getConnection()) {
            supersedeLatest(c, entity);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO configuration (configuration_id, tenant_id, entity, version, payload, "
                            + "governed, status, created_by) VALUES (?,?,?,?,?::jsonb,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, uuidOrNull(ctx.tenantId()));
                ps.setString(3, entity);
                ps.setInt(4, nextVersion);
                ps.setString(5, payload);
                ps.setBoolean(6, governed);
                ps.setString(7, status);
                ps.setString(8, ctx.actor());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO configuration_change (tenant_id, entity, record_id, version_before, "
                            + "version_after, actor, actor_type, reason, governed, correlation_id) "
                            + "VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)")) {
                ps.setObject(1, uuidOrNull(ctx.tenantId()));
                ps.setString(2, entity);
                ps.setString(3, id.toString());
                ps.setString(4, null);
                ps.setString(5, payload);
                ps.setString(6, ctx.actor());
                ps.setString(7, ctx.actorType());
                ps.setString(8, reason);
                ps.setBoolean(9, governed);
                ps.setString(10, ctx.correlationId());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("config registry write failed", e);
        }

        String event = "{\"entity\":\"" + entity + "\",\"version\":" + nextVersion
                + ",\"status\":\"" + status + "\",\"governed\":" + governed + "}";
        audit.record(ctx, "CONFIG_CHANGE", "configuration", id.toString(),
                null, payload, governed ? "GOVERNED_SUBMIT" : "DIRECT_CHANGE", reason,
                "configuration.changed", id.toString(), event);
        return new ConfigVersion(id, entity, nextVersion, payload, governed, status);
    }

    @Transactional
    public Optional<ConfigVersion> approve(TenantContext ctx, UUID configurationId, String approvalNote) {
        ConfigVersion v = find(configurationId);
        if (v == null) {
            return Optional.empty();
        }
        try (Connection c = ds.getConnection()) {
            supersedeLatest(c, v.entity());
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE configuration SET status='ACTIVE' WHERE configuration_id=?")) {
                ps.setObject(1, configurationId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("approval write failed", e);
        }
        audit.record(ctx, "APPROVAL", "configuration", configurationId.toString(),
                "{\"status\":\"PENDING_APPROVAL\"}", "{\"status\":\"ACTIVE\"}",
                "GOVERNED_APPROVED", approvalNote,
                "configuration.changed", configurationId.toString(),
                "{\"entity\":\"" + v.entity() + "\",\"status\":\"ACTIVE\"}");
        return Optional.of(new ConfigVersion(v.id(), v.entity(), v.version(), v.payload(), v.governed(), "ACTIVE"));
    }

    public ConfigVersion find(UUID id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT entity, version, payload, governed, status FROM configuration "
                             + "WHERE configuration_id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ConfigVersion(id, rs.getString(1), rs.getInt(2),
                        rs.getString(3), rs.getBoolean(4), rs.getString(5)) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int latestVersion(String entity) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT coalesce(max(version), 0) FROM configuration WHERE entity = ?")) {
            ps.setString(1, entity);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void supersedeLatest(Connection c, String entity) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE configuration SET status='SUPERSEDED' "
                        + "WHERE entity = ? AND status = 'ACTIVE'")) {
            ps.setString(1, entity);
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
