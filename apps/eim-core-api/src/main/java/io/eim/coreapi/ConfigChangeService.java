package io.eim.coreapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class ConfigChangeService {

  private final DataSource dataSource;
  private final ObjectMapper objectMapper;

  public ConfigChangeService(DataSource dataSource, ObjectMapper objectMapper) {
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
  }

  public Accepted submit(Command command, AuditContext context) {
    String afterState = serialize(command.payload());
    UUID changeId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID correlationId = context.correlationId();
    UUID outboxId = UUID.randomUUID();
    String headers = buildHeaders(context);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertConfigurationChange(connection, changeId, command, context, afterState);
        insertAuditLog(connection, requestId, command, context, afterState);
        insertOutbox(connection, outboxId, changeId, command, context, afterState, headers);
        connection.commit();
      } catch (SQLException exception) {
        rollbackQuietly(connection);
        throw new ConfigChangePersistenceException("Failed to persist configuration change", exception);
      }
    } catch (SQLException exception) {
      throw new ConfigChangePersistenceException("Failed to persist configuration change", exception);
    }
    return new Accepted(true, requestId, correlationId);
  }

  private String serialize(JsonNode payload) {
    if (payload == null || payload.isNull()) {
      throw new IllegalArgumentException("payload is required");
    }
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("payload must be valid JSON", exception);
    }
  }

  private String buildHeaders(AuditContext context) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", context.tenantId().toString());
    node.put("correlationId", context.correlationId().toString());
    node.put("actorId", context.actorId());
    return node.toString();
  }

  private void insertConfigurationChange(Connection connection, UUID id, Command command, AuditContext context, String afterState) throws SQLException {
    String sql = "INSERT INTO configuration_change (id, tenant_id, entity, entity_id, version, before_state, after_state, reason, actor_id, actor_type, correlation_id) VALUES (?, ?, ?, ?, 1, NULL, ?::jsonb, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.setObject(2, context.tenantId());
      statement.setString(3, command.entity());
      setNullableString(statement, 4, command.entityId());
      statement.setString(5, afterState);
      statement.setString(6, command.reason());
      statement.setString(7, context.actorId());
      statement.setString(8, context.actorType().name());
      statement.setObject(9, context.correlationId());
      statement.executeUpdate();
    }
  }

  private void insertAuditLog(Connection connection, UUID requestId, Command command, AuditContext context, String afterState) throws SQLException {
    String sql = "INSERT INTO audit_log (id, tenant_id, request_id, actor_id, actor_type, action_type, target_entity, target_id, before_state, after_state, reason_code, reason_comment, correlation_id, source_channel, outcome) VALUES (?, ?, ?, ?, ?, 'CONFIG_CHANGE', ?, ?, NULL, ?::jsonb, NULL, ?, ?, 'CORE_API', 'SUCCESS')";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, context.tenantId());
      statement.setObject(3, requestId);
      statement.setString(4, context.actorId());
      statement.setString(5, context.actorType().name());
      statement.setString(6, command.entity());
      setNullableString(statement, 7, command.entityId());
      statement.setString(8, afterState);
      statement.setString(9, command.reason());
      statement.setObject(10, context.correlationId());
      statement.executeUpdate();
    }
  }

  private void insertOutbox(Connection connection, UUID outboxId, UUID changeId, Command command, AuditContext context, String afterState, String headers) throws SQLException {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("id", changeId.toString());
    payload.put("tenantId", context.tenantId().toString());
    payload.put("entity", command.entity());
    if (command.entityId() != null) {
      payload.put("entityId", command.entityId());
    }
    payload.put("reason", command.reason());
    payload.set("afterState", command.payload());
    payload.put("actorId", context.actorId());
    payload.put("actorType", context.actorType().name());
    payload.put("correlationId", context.correlationId().toString());

    String sql = "INSERT INTO transactional_outbox (id, aggregate_type, aggregate_id, event_type, payload, headers) VALUES (?, 'configuration_change', ?, 'config.change.accepted', ?::jsonb, ?::jsonb)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, outboxId);
      statement.setObject(2, changeId);
      statement.setString(3, payload.toString());
      statement.setString(4, headers);
      statement.executeUpdate();
    }
  }

  private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value);
    }
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // suppressed onto the ConfigChangePersistenceException raised by the caller
    }
  }

  public record Command(String entity, String entityId, String reason, JsonNode payload) {
  }

  public record Accepted(boolean accepted, UUID requestId, UUID correlationId) {
  }
}
