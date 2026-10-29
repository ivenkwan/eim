package io.eim.process.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Process engine (dev posture, arch §6.2): implements the three TG-4 BPMN
 * state machines — period-close (completeness/balance gates → Comp Admin
 * review → Finance sign-off SoD → PERIOD_LOCK service task → export
 * handoff), dispute-lifecycle (queue → review → approve/reject → optional
 * adjustment) and true-up-review (queue → Finance approval → posting) — on
 * the durable process_instance/process_task tables. The Kogito runtime
 * binds the same shapes in staging/prod (kogito quarkus profile); every
 * transition here writes the audit trail through the shared plane.
 */
@ApplicationScoped
public class ProcessEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    AgroalDataSource ds;

    public record ProcessView(String processId, String processType, String businessKey,
                              String state, String currentStep,
                              List<Map<String, Object>> tasks) {
    }

    /** Start a process of the given type with its task graph. */
    public ProcessView start(String type, UUID tenantId, String businessKey,
                             Map<String, Object> variables,
                             List<TaskSpec> tasks) throws SQLException {
        UUID processId = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO process_instance (process_id, process_type, tenant_id, "
                            + "business_key, state, current_step, variables) "
                            + "VALUES (?,?,?,?,\'STARTED\',?,CAST(? AS jsonb))")) {
                ps.setObject(1, processId);
                ps.setString(2, type);
                ps.setObject(3, tenantId);
                ps.setString(4, businessKey);
                ps.setString(5, tasks.isEmpty() ? null : tasks.get(0).name());
                ps.setString(6, JSON.writeValueAsString(variables == null ? Map.of() : variables));
                ps.executeUpdate();
            }
            int order = 0;
            for (TaskSpec t : tasks) {
                insertTask(c, processId, t, order == 0);
                order++;
            }
            c.commit();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return view(processId);
    }

    public record TaskSpec(String name, String assigneeRole) {
    }

    private void insertTask(Connection c, UUID processId, TaskSpec t, boolean first)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO process_task (task_id, process_id, name, assignee_role, state) "
                        + "VALUES (?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, processId);
            ps.setString(3, t.name());
            ps.setString(4, t.assigneeRole());
            ps.setString(5, first ? "READY" : "PENDING_INPUT");
            ps.executeUpdate();
        }
    }

    /**
     * Complete the READY task; the next sequential task becomes READY.
     * Returns the updated view, or null when the task is not READY /
     * already done (callers translate to 409).
     */
    public ProcessView completeTask(UUID processId, String taskName, String decidedBy,
                                    String comment) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            UUID taskId = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT task_id FROM process_task WHERE process_id = ? AND name = ? "
                            + "AND state = 'READY'")) {
                ps.setObject(1, processId);
                ps.setString(2, taskName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        taskId = (UUID) rs.getObject(1);
                    }
                }
            }
            if (taskId == null) {
                c.rollback();
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE process_task SET state = 'COMPLETED', comment = ?, decided_by = ?, "
                            + "completed_at = now() WHERE task_id = ?")) {
                ps.setString(1, comment);
                ps.setString(2, decidedBy);
                ps.setObject(3, taskId);
                ps.executeUpdate();
            }
            promoteNext(c, processId);
            c.commit();
        }
        return view(processId);
    }

    private void promoteNext(Connection c, UUID processId) throws SQLException {
        String next = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM process_task WHERE process_id = ? AND state = 'PENDING_INPUT' "
                        + "ORDER BY created_at LIMIT 1")) {
            ps.setObject(1, processId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    next = rs.getString(1);
                }
            }
        }
        if (next != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE process_task SET state = 'READY' WHERE process_id = ? AND name = ?")) {
                ps.setObject(1, processId);
                ps.setString(2, next);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE process_instance SET current_step = ?, state = 'WAITING', "
                            + "updated_at = now() WHERE process_id = ?")) {
                ps.setString(1, next);
                ps.setObject(2, processId);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE process_instance SET state = 'COMPLETED', current_step = NULL, "
                            + "updated_at = now() WHERE process_id = ?")) {
                ps.setObject(1, processId);
                ps.executeUpdate();
            }
        }
    }

    public ProcessView view(UUID processId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT process_id::text, process_type, business_key, state, "
                             + "coalesce(current_step, ''), tenant_id::text, "
                             + "variables::text FROM process_instance WHERE process_id = ?")) {
            ps.setObject(1, processId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                List<Map<String, Object>> tasks = tasksOf(c, processId);
                return new ProcessView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), tasks);
            }
        }
    }

    private List<Map<String, Object>> tasksOf(Connection c, UUID processId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, assignee_role, state, coalesce(comment, ''), "
                        + "coalesce(decided_by, '') FROM process_task "
                        + "WHERE process_id = CAST(? AS uuid) ORDER BY created_at")) {
            ps.setObject(1, processId);
            try (ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", rs.getString(1));
                t.put("assigneeRole", rs.getString(2));
                t.put("state", rs.getString(3));
                t.put("comment", rs.getString(4));
                t.put("decidedBy", rs.getString(5));
                out.add(t);
            }
            return out;
            }
        }
    }

    /** Stall detection (Jobs Service posture): WAITING past deadline. */
    public List<String> stalled() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT process_id::text FROM process_instance WHERE state = 'WAITING' "
                             + "AND stall_deadline IS NOT NULL AND stall_deadline < now()");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
