package io.eim.calcengine.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.eim.calcengine.engine.Engine;
import io.eim.calcengine.engine.EngineModel;
import io.eim.calcengine.engine.Precise;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wavefront scheduler + atomic ledger commit (FR-503/507, NFR-04): loads
 * the AGE-resolved wavefront levels, computes payees level-by-level
 * (leaves first, managers read their reports' outputs — the wave barrier),
 * and commits ALL ledger entries + traces in ONE database transaction —
 * any worker failure aborts the whole run leaving zero rows.
 */
@ApplicationScoped
public class WavefrontRunner {

    private static final Logger LOG = Logger.getLogger(WavefrontRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    AgroalDataSource ds;

    public record RunStats(UUID runId, String status, int waves, int payees, int transactions,
                           int ledgerEntries, long durationMs, String error) {
    }

    /** Fault-injection hook (TST-3): abort when this payee starts. */
    private final AtomicReference<String> failAtPayee = new AtomicReference<>();

    public void failAtPayee(String payeeId) {
        failAtPayee.set(payeeId);
    }

    public RunStats run(UUID tenantId, UUID payoutPeriodId, String planVersionHash,
                        String roundingMode) throws Exception {
        return run(tenantId, payoutPeriodId, planVersionHash, roundingMode, null);
    }

    /**
     * Incremental recalculation (TG7-CAL-2, FR-509): when a payee filter is
     * supplied, only those payees AND their manager chain are recomputed and
     * only their ledger rows are replaced — untouched payees keep their
     * published rows byte-identical (deterministic replay for the rest).
     */
    public RunStats run(UUID tenantId, UUID payoutPeriodId, String planVersionHash,
                        String roundingMode, java.util.Set<UUID> payeeFilter) throws Exception {
        long start = System.nanoTime();
        UUID runId = UUID.randomUUID();
        EngineModel plan = loadPlan(planVersionHash);
        Period p = loadPeriod(tenantId, payoutPeriodId);
        if ("LOCKED".equals(p.status)) {
            throw new IllegalStateException("period is LOCKED (FR-603) — use shadow replay");
        }

        // Wavefronts (AGE-resolved levels; level 0 = leaf).
        Map<Integer, List<UUID>> waves = loadWavefronts();
        Map<UUID, String> payeeNames = loadPayeeNames();
        if (payeeFilter != null && !payeeFilter.isEmpty()) {
            waves = scopeWaves(waves, payeeFilter);
        }

        RoundingMode engineRounding = "HALF_UP".equals(roundingMode)
                ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
        Engine engine = new Engine(plan, engineRounding);

        // Cohort strategy (NFR-20 vs NFR-21): small cohorts load in one
        // query (fastest for the 500×100k benchmark); enterprise cohorts
        // stream per-payee so a 1M-row month never exhausts the heap.
        int txnCount0 = countTransactions(tenantId, p);
        Map<UUID, List<Engine.Txn>> cohort = txnCount0 <= COHORT_LOAD_LIMIT
                ? loadTransactions(tenantId, p) : null;

        // compute phase — waves sequential, payees parallel
        Map<UUID, Engine.PayeeResult> results = new LinkedHashMap<>();
        Map<String, BigDecimal> rollupSums = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            for (Map.Entry<Integer, List<UUID>> wave : waves.entrySet()) {
                List<Future<?>> futures = new ArrayList<>();
                for (UUID payeeId : wave.getValue()) {
                    futures.add(pool.submit(() -> {
                        if (payeeId.toString().equals(failAtPayee.get())) {
                            throw new IllegalStateException(
                                    "injected fault at payee " + payeeId + " (TST-3)");
                        }
                        Map<String, String> ctx = payeeCtx(payeeId, p, rollupSums);
                        List<Engine.Txn> slice = cohort != null
                                ? cohort.getOrDefault(payeeId, List.of())
                                : loadPayeeTransactions(tenantId, p, payeeId);
                        Engine.PayeeResult r = engine.evaluate(
                                String.valueOf(payeeId), slice, ctx);
                        synchronized (results) {
                            results.put(payeeId, r);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(); // wave barrier — any failure surfaces here
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // Manager ROLLUP components (FR-303/503): value = sum over direct
        // reports of the referenced component, resolved across the wave
        // barrier (reports always sit at lower levels).
        Map<UUID, List<UUID>> reportsOf = loadManagedBy();
        for (EngineModel.Component c : plan.components()) {
            if (c.kind() != EngineModel.Kind.ROLLUP) {
                continue;
            }
            String metric = depsOf(c, plan).isEmpty() ? "commission"
                    : depsOf(c, plan).iterator().next();
            for (Map.Entry<UUID, List<UUID>> mgr : reportsOf.entrySet()) {
                BigDecimal sum = BigDecimal.ZERO;
                for (UUID report : mgr.getValue()) {
                    Engine.PayeeResult rr = results.get(report);
                    if (rr != null && rr.components().containsKey(metric)) {
                        sum = Precise.add(sum, rr.components().get(metric));
                    }
                }
                if (sum.signum() != 0) {
                    Engine.PayeeResult mgrResult = results.computeIfAbsent(mgr.getKey(),
                            k -> new Engine.PayeeResult(new LinkedHashMap<>(), new ArrayList<>(),
                                    new ArrayList<>()));
                    mgrResult.components().put(c.name(), sum);
                    mgrResult.lines().add(new Engine.LedgerLine(c.name(), "CALCULATED",
                            Precise.roundPayout(sum, engineRounding), null, null, null, null));
                }
            }
        }

        List<Object[]> ledgerRows = new ArrayList<>();
        for (Map.Entry<Integer, List<UUID>> wave : waves.entrySet()) {
            for (UUID payeeId : wave.getValue()) {
                Engine.PayeeResult r = results.get(payeeId);
                if (r == null) {
                    continue;
                }
                for (Engine.LedgerLine line : r.lines()) {
                    // zero-value components (payees with no transactions in
                    // the period) stay in traces, not in the ledger
                    if (line.amount().signum() == 0) {
                        continue;
                    }
                    String traceJson = buildTrace(payeeId, r, p, planVersionHash);
                    ledgerRows.add(new Object[]{payeeId, line, traceJson});
                }
            }
        }

        int entries = commitLedger(tenantId, payoutPeriodId, planVersionHash,
                payeeNames, p, ledgerRows, payeeFilter);
        long dur = (System.nanoTime() - start) / 1_000_000;
        // transactions counted in the aggregate SQL (per-payee slices are no
        // longer resident at this point)
        int txnCount = txnCount0;
        recordRun(runId, tenantId, payoutPeriodId, planVersionHash, "COMPLETED",
                waves.size(), results.size(), txnCount, entries, dur, null);
        return new RunStats(runId, "COMPLETED", waves.size(), results.size(),
                txnCount, entries, dur, null);
    }

    /** manager -> direct reports (open versions). */
    private Map<UUID, List<UUID>> loadManagedBy() throws SQLException {
        Map<UUID, List<UUID>> by = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT manager_payee_id, payee_id FROM payee "
                             + "WHERE manager_payee_id IS NOT NULL AND effective_end_date IS NULL "
                             + "ORDER BY payee_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                by.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new ArrayList<>())
                        .add(UUID.fromString(rs.getString(2)));
            }
        }
        return by;
    }

    private static java.util.Set<String> depsOf(EngineModel.Component c, EngineModel plan) {
        java.util.Set<String> deps = new java.util.LinkedHashSet<>();
        if (c.dependsOn() != null) {
            deps.addAll(c.dependsOn());
        }
        if (c.formula() != null) {
            for (String token : c.formula().split("[^A-Za-z0-9_@]+")) {
                if (token.startsWith("@") && plan.component(token.substring(1)) != null) {
                    deps.add(token.substring(1));
                }
            }
        }
        deps.removeIf(d -> plan.component(d) == null);
        return deps;
    }

    /** Records a FAILED run (audit trail) without any ledger writes. */
    public void recordFailure(UUID runId, UUID tenantId, UUID payoutPeriodId,
                              String planVersionHash, long durationMs, String error) {
        try {
            recordRun(runId, tenantId, payoutPeriodId, planVersionHash, "FAILED",
                    null, null, null, 0, durationMs, error);
        } catch (SQLException e) {
            LOG.errorf(e, "failed to record failed run %s", runId);
        }
    }

    private Map<String, String> payeeCtx(UUID payeeId, Period p,
                                         Map<String, BigDecimal> rollupSums) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("period_start", p.startsOn.toString());
        ctx.put("period_end", p.endsOn.toString());
        long periodDays = Math.max(1, ChronoUnit.DAYS.between(p.startsOn, p.endsOn) + 1);
        ctx.put("period_days", String.valueOf(periodDays));
        ctx.put("active_days", String.valueOf(periodDays)); // proration via payee versions (TG-3)
        ctx.put("draw_balance", "0");
        ctx.put("origin_period_id", String.valueOf(p.payoutPeriodId));
        BigDecimal rollup = rollupSums.get(String.valueOf(payeeId));
        if (rollup != null) {
            ctx.put("report_rollup", rollup.toPlainString());
        }
        return ctx;
    }

    private String buildTrace(UUID payeeId, Engine.PayeeResult r, Period p,
                              String planVersionHash) {
        try {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("payee_id", String.valueOf(payeeId));
            trace.put("period", p.periodKey);
            trace.put("plan_version_hash", planVersionHash);
            trace.put("components", r.components().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().toPlainString(),
                            (a, b) -> a, LinkedHashMap::new)));
            trace.put("steps", r.trace());
            String json = JSON.writeValueAsString(trace);
            if (json.length() > 16_384) {
                throw new IllegalStateException(
                        "trace exceeds 16KB (NFR-07) for payee " + payeeId);
            }
            return json;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Restrict the wavefronts to the dirty payees plus every manager
     * ancestor (rollups recompute along the chain, FR-509).
     */
    private Map<Integer, List<UUID>> scopeWaves(Map<Integer, List<UUID>> waves,
                                                java.util.Set<UUID> dirty) throws SQLException {
        java.util.Set<UUID> scope = new java.util.HashSet<>(dirty);
        Map<UUID, UUID> managerOf = new java.util.HashMap<>();
        try (java.sql.Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT payee_id, manager_payee_id FROM payee "
                             + "WHERE manager_payee_id IS NOT NULL")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    managerOf.put((UUID) rs.getObject(1), (UUID) rs.getObject(2));
                }
            }
        }
        for (UUID p : dirty) {
            UUID m = managerOf.get(p);
            while (m != null && scope.add(m)) {
                m = managerOf.get(m);
            }
        }
        Map<Integer, List<UUID>> out = new java.util.LinkedHashMap<>();
        int kept = 0;
        for (Map.Entry<Integer, List<UUID>> w : waves.entrySet()) {
            List<UUID> keptPayees = new java.util.ArrayList<>();
            for (UUID id : w.getValue()) {
                if (scope.contains(id)) {
                    keptPayees.add(id);
                }
            }
            if (!keptPayees.isEmpty()) {
                out.put(w.getKey(), keptPayees);
                kept += keptPayees.size();
            }
        }
        return out;
    }

    /** ALL entries in one transaction — atomic-or-clean (FR-507.1). */
    private int commitLedger(UUID tenantId, UUID payoutPeriodId, String planVersionHash,
                             Map<UUID, String> payeeNames, Period p,
                             List<Object[]> rows) throws SQLException {
        return commitLedger(tenantId, payoutPeriodId, planVersionHash, payeeNames, p, rows, null);
    }

    private int commitLedger(UUID tenantId, UUID payoutPeriodId, String planVersionHash,
                             Map<UUID, String> payeeNames, Period p,
                             List<Object[]> rows, java.util.Set<UUID> payeeFilter)
            throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ensure = c.prepareStatement(
                    "SELECT eim_ops.ensure_period_partition(?, ?::text)");
                 PreparedStatement wipe = c.prepareStatement(
                         "DELETE FROM commission_ledger_entry WHERE payout_period_id = ? "
                                 + "AND entry_type NOT IN ('MANUAL_OVERRIDE','PRIOR_PERIOD_TRUEUP')");
                 PreparedStatement ins = c.prepareStatement(
                         "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                                 + "payee_id, transaction_id, component_name, credited_amount, "
                                 + "rate_applied, payout_amount, entry_type, calculation_trace, is_locked) "
                                 + "VALUES (?,?,?,?,CAST(? AS uuid),?,?,NULL,?,?,CAST(? AS jsonb),false)")) {
                ensure.setObject(1, payoutPeriodId);
                ensure.setString(2, p.periodKey());
                ensure.executeQuery();
                if (payeeFilter == null || payeeFilter.isEmpty()) {
                    wipe.setObject(1, payoutPeriodId);
                    wipe.executeUpdate(); // idempotent rerun of the same period
                } else {
                    // incremental: replace only the dirty payees' rows
                    try (java.sql.PreparedStatement wipePayees = c.prepareStatement(
                            "DELETE FROM commission_ledger_entry WHERE payout_period_id = ? "
                                    + "AND entry_type NOT IN ('MANUAL_OVERRIDE','PRIOR_PERIOD_TRUEUP') "
                                    + "AND payee_id = ANY(?::uuid[])")) {
                        wipePayees.setObject(1, payoutPeriodId);
                        wipePayees.setArray(2, c.createArrayOf("uuid",
                                payeeFilter.toArray(new UUID[0])));
                        wipePayees.executeUpdate();
                    }
                }
                int n = 0;
                for (Object[] row : rows) {
                    UUID payeeId = (UUID) row[0];
                    Engine.LedgerLine line = (Engine.LedgerLine) row[1];
                    ins.setObject(1, UUID.randomUUID());
                    ins.setObject(2, tenantId);
                    ins.setObject(3, payoutPeriodId);
                    ins.setObject(4, payeeId);
                    ins.setString(5, line.transactionId());
                    ins.setString(6, line.componentName());
                    ins.setBigDecimal(7, line.creditedAmount());
                    ins.setBigDecimal(8, line.amount());
                    ins.setString(9, line.entryType());
                    ins.setString(10, (String) row[2]);
                    ins.addBatch();
                    n++;
                }
                ins.executeBatch();
                c.commit();
                return n;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    EngineModel loadPlan(String planVersionHash) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT model_json::text FROM plan_artifact WHERE plan_version_hash = ?")) {
            ps.setString(1, planVersionHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("unknown plan version " + planVersionHash);
                }
                try {
                    return JSON.readValue(rs.getString(1), EngineModel.class);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    record Period(UUID payoutPeriodId, String periodKey, LocalDate startsOn,
                  LocalDate endsOn, String status) {
    }

    private Period loadPeriod(UUID tenantId, UUID payoutPeriodId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT period_key, starts_on, ends_on, status FROM payout_period "
                             + "WHERE payout_period_id = ? AND tenant_id = ?")) {
            ps.setObject(1, payoutPeriodId);
            ps.setObject(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("payout period not found");
                }
                return new Period(payoutPeriodId, rs.getString(1),
                        rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(), rs.getString(4));
            }
        }
    }

    /** wave level -> payees (leaves first: level 0). */
    private Map<Integer, List<UUID>> loadWavefronts() throws SQLException {
        Map<Integer, List<UUID>> waves = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payee_id, level FROM eim_ops.wavefront_levels() ORDER BY level, payee_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                waves.computeIfAbsent(rs.getInt(2), k -> new ArrayList<>())
                        .add(UUID.fromString(rs.getString(1)));
            }
        }
        return waves;
    }

    /** Cohort loads stay under this row count; beyond it, stream per payee. */
    private static final int COHORT_LOAD_LIMIT = 200_000;

    /** Deterministic order (transaction_date, source_ts, uuid) — FR-501. */
    private Map<UUID, List<Engine.Txn>> loadTransactions(UUID tenantId, Period p)
            throws SQLException {
        Map<UUID, List<Engine.Txn>> byPayee = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ca.payee_id, t.transaction_id, t.transaction_date, "
                             + "t.created_at AS ts, t.booking_amount * ca.split_pct "
                             + "AS credited "
                             + "FROM raw_transaction t JOIN credit_allocation ca "
                             + "ON ca.transaction_id = t.transaction_id "
                             + "WHERE t.tenant_id = ? AND t.transaction_date BETWEEN ? AND ? "
                             + "ORDER BY t.transaction_date, ts, t.transaction_id")) {
            ps.setObject(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(p.startsOn));
            ps.setDate(3, java.sql.Date.valueOf(p.endsOn()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byPayee.computeIfAbsent(UUID.fromString(rs.getString(1)),
                                    k -> new ArrayList<>())
                            .add(new Engine.Txn(rs.getString(2),
                                    rs.getDate(3).toLocalDate(),
                                    rs.getTimestamp(4).toInstant().toString(),
                                    rs.getBigDecimal(5)));
                }
            }
        }
        byPayee.values().forEach(l -> l.sort(Comparator
                .comparing(Engine.Txn::date)
                .thenComparing(Engine.Txn::sourceTsUtc)
                .thenComparing(Engine.Txn::transactionId)));
        return byPayee;
    }

    /**
     * One payee's credited transactions for the period — the per-payee slice
     * that keeps a 1M-row cohort resident-bounded (NFR-21). Deterministic
     * order (transaction_date, source_ts, uuid) — FR-501.
     */
    private List<Engine.Txn> loadPayeeTransactions(UUID tenantId, Period p, UUID payeeId)
            throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.transaction_id, t.transaction_date, "
                             + "t.created_at AS ts, t.booking_amount * ca.split_pct "
                             + "AS credited "
                             + "FROM raw_transaction t JOIN credit_allocation ca "
                             + "ON ca.transaction_id = t.transaction_id "
                             + "WHERE t.tenant_id = ? AND ca.payee_id = ? "
                             + "AND t.transaction_date BETWEEN ? AND ? "
                             + "ORDER BY t.transaction_date, ts, t.transaction_id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, payeeId);
            ps.setDate(3, java.sql.Date.valueOf(p.startsOn));
            ps.setDate(4, java.sql.Date.valueOf(p.endsOn));
            try (ResultSet rs = ps.executeQuery()) {
                List<Engine.Txn> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Engine.Txn(rs.getString(1),
                            rs.getDate(2).toLocalDate(),
                            rs.getTimestamp(3).toInstant().toString(),
                            rs.getBigDecimal(4)));
                }
                return out;
            }
        }
    }

    /** Aggregate row count for run stats without materializing the cohort. */
    private int countTransactions(UUID tenantId, Period p) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM raw_transaction t JOIN credit_allocation ca "
                             + "ON ca.transaction_id = t.transaction_id "
                             + "WHERE t.tenant_id = ? AND t.transaction_date BETWEEN ? AND ?")) {
            ps.setObject(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(p.startsOn));
            ps.setDate(3, java.sql.Date.valueOf(p.endsOn));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Map<UUID, String> loadPayeeNames() throws SQLException {
        Map<UUID, String> names = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payee_id, full_name FROM payee WHERE effective_end_date IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.put(UUID.fromString(rs.getString(1)), rs.getString(2));
            }
        }
        return names;
    }

    private void recordRun(UUID runId, UUID tenantId, UUID payoutPeriodId, String hash,
                           String status, Integer waves, Integer payees, Integer txns,
                           int entries, long durationMs, String error) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO calc_run (calc_run_id, tenant_id, payout_period_id, plan_version_hash, "
                             + "status, waves, payees, transactions, ledger_entries, duration_ms, "
                             + "error, finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,now())")) {
            ps.setObject(1, runId);
            ps.setObject(2, tenantId);
            ps.setObject(3, payoutPeriodId);
            ps.setString(4, hash);
            ps.setString(5, status);
            ps.setObject(6, waves);
            ps.setObject(7, payees);
            ps.setObject(8, txns);
            ps.setInt(9, entries);
            ps.setLong(10, durationMs);
            ps.setString(11, error);
            ps.executeUpdate();
        }
    }
}
