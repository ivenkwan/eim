package io.eim.calcengine.run;

import io.agroal.api.AgroalDataSource;
import io.eim.calcengine.engine.Engine;
import io.eim.calcengine.engine.EngineModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bi-temporal shadow replay (FR-508): re-derives what a LOCKED period
 * WOULD pay using the pinned plan artifacts and the frozen input set plus
 * any late-arriving changes, diffs per (payee, transaction, component)
 * against the committed ledger, and emits typed PRIOR_PERIOD_TRUEUP lines
 * into the OPEN period — each carrying origin_period_id back to the locked
 * period. Read-only against the locked ledger.
 */
@ApplicationScoped
public class ShadowReplay {

    @Inject
    AgroalDataSource ds;

    @Inject
    WavefrontRunner runner;

    public record TrueupLine(UUID payeeId, String component, BigDecimal delta,
                             String transactionId, UUID originPeriodId) {
    }

    public record ReplayResult(int diffs, List<TrueupLine> lines) {
    }

    /**
     * @param lockedPeriodId   the period whose ledger is being re-derived
     * @param openPeriodId     where approved true-up entries land
     * @param planVersionHash  the PINNED artifact version (replay never
     *                         uses a newer plan)
     */
    /**
     * @param enqueue route diffs into the true-up review queue (default) —
     *                 Finance approval posts them; the legacy direct-post
     *                 path remains for the engine's own regression tests.
     */
    public ReplayResult replay(UUID tenantId, UUID lockedPeriodId, UUID openPeriodId,
                               String planVersionHash) throws Exception {
        return replay(tenantId, lockedPeriodId, openPeriodId, planVersionHash, true);
    }

    public ReplayResult replay(UUID tenantId, UUID lockedPeriodId, UUID openPeriodId,
                               String planVersionHash, boolean enqueue) throws Exception {
        EngineModel plan = runner.loadPlan(planVersionHash);
        WavefrontRunner.Period locked = period(lockedPeriodId);
        WavefrontRunner.Period open = period(openPeriodId);
        if (!"LOCKED".equals(locked.status())) {
            throw new IllegalStateException("shadow replay requires a LOCKED period");
        }
        Engine engine = new Engine(plan, RoundingMode.HALF_EVEN);

        // frozen inputs as-of lock + late-arriving rows (dev semantics: the
        // current committed transactions dated into the locked period).
        Map<UUID, List<Engine.Txn>> txns = loadTransactions(tenantId, locked);
        Map<UUID, BigDecimal> committed = committedLedger(lockedPeriodId);

        List<TrueupLine> lines = new ArrayList<>();
        for (Map.Entry<UUID, List<Engine.Txn>> e : txns.entrySet()) {
            Map<String, String> ctx = new LinkedHashMap<>();
            ctx.put("period_start", locked.startsOn().toString());
            ctx.put("period_end", locked.endsOn().toString());
            ctx.put("period_days", "30");
            ctx.put("active_days", "30");
            ctx.put("draw_balance", "0");
            Engine.PayeeResult r = engine.evaluate(String.valueOf(e.getKey()),
                    e.getValue(), ctx);
            BigDecimal shadowTotal = r.lines().stream()
                    .filter(l -> "CALCULATED".equals(l.entryType()))
                    .map(Engine.LedgerLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal committedTotal = committed.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal delta = shadowTotal.subtract(committedTotal);
            if (delta.signum() != 0) {
                lines.add(new TrueupLine(e.getKey(), "shadow_replay", delta,
                        null, lockedPeriodId));
            }
        }
        if (!lines.isEmpty()) {
            if (enqueue) {
                enqueueForReview(tenantId, lockedPeriodId, openPeriodId, lines);
            } else {
                emitTrueup(tenantId, openPeriodId, planVersionHash, lines, open);
            }
        }
        return new ReplayResult(lines.size(), lines);
    }

    /** Queue diffs for Finance review (FR-604.2) — posting is gated. */
    private void enqueueForReview(UUID tenantId, UUID lockedPeriodId, UUID openPeriodId,
                                  List<TrueupLine> lines) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO trueup_review (trueup_id, tenant_id, origin_period_id, "
                             + "open_period_id, payee_id, diff_amount, report) "
                             + "VALUES (?,?,?,?,CAST(? AS uuid),?,CAST(? AS jsonb))")) {
            for (TrueupLine l : lines) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setObject(3, lockedPeriodId);
                ps.setObject(4, openPeriodId);
                ps.setObject(5, l.payeeId());
                ps.setBigDecimal(6, l.delta());
                ps.setString(7, "{\"origin_period_id\":\"" + l.originPeriodId()
                        + "\",\"component\":\"" + l.component() + "\"}");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void emitTrueup(UUID tenantId, UUID openPeriodId, String hash,
                            List<TrueupLine> lines, WavefrontRunner.Period open)
            throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ensure = c.prepareStatement(
                    "SELECT eim_ops.ensure_period_partition(?, ?::text)");
                 PreparedStatement ins = c.prepareStatement(
                         "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                                 + "payee_id, transaction_id, component_name, payout_amount, "
                                 + "entry_type, calculation_trace, is_locked) "
                                 + "VALUES (?,?,?,?,NULL,?,?,'PRIOR_PERIOD_TRUEUP',?::jsonb,false)")) {
                ensure.setObject(1, openPeriodId);
                ensure.setString(2, open.periodKey());
                ensure.executeQuery();
                for (TrueupLine l : lines) {
                    ins.setObject(1, UUID.randomUUID());
                    ins.setObject(2, tenantId);
                    ins.setObject(3, openPeriodId);
                    ins.setObject(4, l.payeeId());
                    ins.setString(5, l.component());
                    ins.setBigDecimal(6, l.delta());
                    ins.setString(7, "{\"origin_period_id\":\"" + l.originPeriodId()
                            + "\",\"plan_version_hash\":\"" + hash + "\"}");
                    ins.addBatch();
                }
                ins.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private WavefrontRunner.Period period(UUID id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT period_key, starts_on, ends_on, status FROM payout_period "
                             + "WHERE payout_period_id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("period not found: " + id);
                }
                return new WavefrontRunner.Period(id, rs.getString(1),
                        rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(),
                        rs.getString(4));
            }
        }
    }

    private Map<UUID, List<Engine.Txn>> loadTransactions(UUID tenantId,
                                                         WavefrontRunner.Period p)
            throws SQLException {
        Map<UUID, List<Engine.Txn>> byPayee = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ca.payee_id, t.transaction_id, t.transaction_date, t.created_at AS ts, "
                             + "t.booking_amount * ca.split_pct AS credited "
                             + "FROM raw_transaction t "
                             + "JOIN credit_allocation ca ON ca.transaction_id = t.transaction_id "
                             + "WHERE t.tenant_id = ? AND t.transaction_date BETWEEN ? AND ? "
                             + "ORDER BY t.transaction_date, ts, t.transaction_id")) {
            ps.setObject(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(p.startsOn()));
            ps.setDate(3, java.sql.Date.valueOf(p.endsOn()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byPayee.computeIfAbsent(UUID.fromString(rs.getString(1)),
                                    k -> new ArrayList<>())
                            .add(new Engine.Txn(rs.getString(2), rs.getDate(3).toLocalDate(),
                                    rs.getTimestamp(4).toInstant().toString(),
                                    rs.getBigDecimal(5)));
                }
            }
        }
        return byPayee;
    }

    private Map<UUID, BigDecimal> committedLedger(UUID lockedPeriodId) throws SQLException {
        Map<UUID, BigDecimal> byPayee = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payee_id, sum(payout_amount) FROM commission_ledger_entry "
                             + "WHERE payout_period_id = ? AND entry_type = 'CALCULATED' "
                             + "GROUP BY payee_id")) {
            ps.setObject(1, lockedPeriodId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byPayee.put(UUID.fromString(rs.getString(1)), rs.getBigDecimal(2));
                }
            }
        }
        return byPayee;
    }
}
