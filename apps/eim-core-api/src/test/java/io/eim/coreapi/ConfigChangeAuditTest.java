package io.eim.coreapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class ConfigChangeAuditTest {

  private static PostgreSQLContainer<?> container;
  private static PGSimpleDataSource dataSource;
  private static ConfigChangeService service;

  @BeforeAll
  static void start() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is required for PostgreSQL tests");
    container = new PostgreSQLContainer<>("postgres:16-alpine");
    container.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setURL(container.getJdbcUrl());
    dataSource.setUser(container.getUsername());
    dataSource.setPassword(container.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    service = new ConfigChangeService(dataSource, new ObjectMapper());
  }

  @AfterAll
  static void stop() {
    if (container != null) {
      container.stop();
    }
  }

  @BeforeEach
  void clean() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE configuration_change, audit_log, transactional_outbox");
    }
  }

  private static AuditContext context() {
    return new AuditContext(
        UUID.randomUUID(), "actor-1", ActorType.USER, UUID.randomUUID(), "CORE_API");
  }

  private static ConfigChangeService.Command command() {
    return new ConfigChangeService.Command(
        "plan", "p-1", "adjust", new ObjectMapper().createObjectNode().put("k", 1));
  }

  private static long count(String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String columnType(String table, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT data_type FROM information_schema.columns WHERE table_name='"
                    + table
                    + "' AND column_name='"
                    + column
                    + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  @Test
  void oneAcceptedChangeWritesOneRowPerTable() throws SQLException {
    AuditContext context = context();
    ConfigChangeService.Accepted accepted = service.submit(command(), context);
    assertEquals(1, count("configuration_change"));
    assertEquals(1, count("audit_log"));
    assertEquals(1, count("transactional_outbox"));
    assertNotNull(accepted.requestId());
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT request_id, correlation_id FROM audit_log")) {
      rs.next();
      assertEquals(accepted.requestId(), rs.getObject("request_id", UUID.class));
      assertEquals(context.correlationId(), rs.getObject("correlation_id", UUID.class));
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("SELECT aggregate_id FROM transactional_outbox")) {
      rs.next();
      UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
      try (Statement st = connection.createStatement();
          ResultSet change = st.executeQuery("SELECT id FROM configuration_change")) {
        change.next();
        assertEquals(change.getObject("id", UUID.class), aggregateId);
      }
    }
  }

  @Test
  void jsonbColumnsAndTriggersExist() throws SQLException {
    assertEquals("jsonb", columnType("configuration_change", "after_state"));
    assertEquals("jsonb", columnType("audit_log", "after_state"));
    assertEquals("jsonb", columnType("transactional_outbox", "payload"));
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM pg_trigger WHERE tgname IN ('audit_log_append_only',"
                    + " 'configuration_change_append_only')")) {
      rs.next();
      assertEquals(2, rs.getInt(1));
    }
  }

  @Test
  void outboxFailureRollsBackAllRows() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE FUNCTION fail_outbox() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'forced'; END; $$ LANGUAGE plpgsql");
      statement.execute(
          "CREATE TRIGGER fail_outbox_trg BEFORE INSERT ON transactional_outbox FOR EACH ROW EXECUTE FUNCTION fail_outbox()");
    }
    try {
      assertThrows(
          ConfigChangePersistenceException.class, () -> service.submit(command(), context()));
      assertEquals(0, count("configuration_change"));
      assertEquals(0, count("audit_log"));
      assertEquals(0, count("transactional_outbox"));
    } finally {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER IF EXISTS fail_outbox_trg ON transactional_outbox");
        statement.execute("DROP FUNCTION IF EXISTS fail_outbox()");
      }
    }
  }

  @Test
  void appendOnlyRejectsUpdateAndDelete() throws SQLException {
    service.submit(command(), context());
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE audit_log SET reason_comment='x'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM audit_log"));
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("UPDATE configuration_change SET reason='x'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM configuration_change"));
    }
    assertEquals(1, count("audit_log"));
    assertEquals(1, count("configuration_change"));
  }

  @Test
  void concurrentSubmissionsAreIsolated() throws Exception {
    int threads = 2;
    CountDownLatch start = new CountDownLatch(1);
    List<ConfigChangeService.Accepted> results = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                  results.add(service.submit(command(), context()));
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
              });
      worker.start();
      workers.add(worker);
    }
    start.countDown();
    for (Thread worker : workers) {
      worker.join(TimeUnit.SECONDS.toMillis(30));
    }
    assertEquals(2, results.size());
    assertTrue(!results.get(0).requestId().equals(results.get(1).requestId()));
    assertEquals(2, count("configuration_change"));
    assertEquals(2, count("audit_log"));
    assertEquals(2, count("transactional_outbox"));
  }
}
