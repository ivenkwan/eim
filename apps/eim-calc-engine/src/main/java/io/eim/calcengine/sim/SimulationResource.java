package io.eim.calcengine.sim;

import io.agroal.api.AgroalDataSource;
import io.eim.calcengine.engine.EngineModel;
import io.eim.calcengine.engine.Engine;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Monte Carlo simulation API (TG8-CAL-1, FR-1001): bootstraps each payee's
 * historical deal distribution for N iterations against a published plan
 * (real Engine on the published model), persists per-run liability bands
 * and per-payee windfall ratios, and evaluates on scaled batch workers —
 * iterations fan out across a fixed pool, each worker seeding its own
 * deterministic sub-stream from the run seed.
 */
@Path("/api/v1/calc/simulations")
@Produces(MediaType.APPLICATION_JSON)
public class SimulationResource {

    private static final int WORKERS = Math.max(2, Runtime.getRuntime().availableProcessors());

    @Inject
    AgroalDataSource ds;

    public record SimRequest(String tenantId, String periodKey, String planVersionHash,
                             Integer iterations, Long seed) {
    }

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(SimRequest request, @Context ContainerRequestContext req) {
        if (request == null || blank(request.tenantId()) || blank(request.periodKey())
                || blank(request.planVersionHash())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "tenantId, periodKey and planVersionHash are required"))
                    .build();
        }
        int iterations = request.iterations() == null || request.iterations() < 100
                ? 1_000 : Math.min(request.iterations(), 100_000);
        long seed = request.seed() == null ? 42L : request.seed();
        UUID simId = UUID.randomUUID();
        try {
            EngineModel plan = loadPlan(request.planVersionHash());
            List<MonteCarloSimulator.PayeeHistory> histories = loadHistories(request.tenantId());
            if (histories.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "no historical deals to sample")).build();
            }
            Engine engine = new Engine(plan, RoundingMode.HALF_EVEN);
            insertRun(simId, request, iterations, seed);

            // scaled batch workers: iterations split across WORKERS chunks,
            // each with its own deterministic sub-seed; outcomes merged.
            MonteCarloSimulator.SimulationResult result = runParallel(
                    histories, engine, iterations, seed);
            persistOutcomes(simId, result, histories);
            markCompleted(simId, result, 2.0);
            auditSim(request.tenantId(), simId, result);
            return view(simId.toString());
        } catch (Exception e) {
            try {
                markFailed(simId, e.getMessage());
            } catch (SQLException ignored) {
                // status update is best-effort on the error path
            }
            return Response.status(500)
                    .entity(Map.of("error", "simulation failed: " + e.getMessage(),
                            "simulationId", simId.toString()))
                    .build();
        }
    }

    @GET
    @Path("/{simulationId}")
    @PermitAll
    public Response viewResult(@PathParam("simulationId") String simulationId,
                               @Context ContainerRequestContext req) throws SQLException {
        return view(simulationId);
    }

    // ---------------------------------------------------------------- engine
    private MonteCarloSimulator.SimulationResult runParallel(
            List<MonteCarloSimulator.PayeeHistory> histories, Engine engine,
            int iterations, long seed) throws Exception {
        int chunk = Math.floorDiv(iterations + WORKERS - 1, WORKERS);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<int[]>> indexFutures = new ArrayList<>();
        // partition iteration indices deterministically
        List<List<Integer>> partitions = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            List<Integer> idx = new ArrayList<>();
            for (int i = w * chunk; i < Math.min((w + 1) * chunk, iterations); i++) {
                idx.add(i);
            }
            partitions.add(idx);
        }
        try {
            // each worker runs its own simulator over its iteration slice
            List<MonteCarloSimulator.IterationOutcome> merged = new ArrayList<>();
            double[] totals = new double[iterations];
            double[][] perPayee = new double[histories.size()][iterations];
            List<Future<WorkerOut>> futures = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                final List<Integer> idx = partitions.get(w);
                if (idx.isEmpty()) {
                    continue;
                }
                final long subSeed = seed + w;
                futures.add(pool.submit(() -> {
                    MonteCarloSimulator sim = new MonteCarloSimulator(subSeed);
                    double[] wTotals = new double[idx.size()];
                    double[][] wPayee = new double[histories.size()][idx.size()];
                    for (int k = 0; k < idx.size(); k++) {
                        double total = 0;
                        for (int p = 0; p < histories.size(); p++) {
                            MonteCarloSimulator.PayeeHistory h = histories.get(p);
                            List<Double> sampled = bootstrap(sim, h.dealAmounts());
                            Map<String, String> ctx = new LinkedHashMap<>();
                            ctx.put("period_start", "2026-01-01");
                            ctx.put("period_end", "2026-12-31");
                            ctx.put("period_days", "365");
                            ctx.put("active_days", "365");
                            ctx.put("report_rollup", "0");
                            List<Engine.Txn> txns = new ArrayList<>();
                            for (double d : sampled) {
                                txns.add(new Engine.Txn("sim-" + p + "-" + k,
                                        java.time.LocalDate.of(2026, 6, 15),
                                        "2026-01-01T00:00:00Z", BigDecimal.valueOf(d)));
                            }
                            Engine.PayeeResult r = engine.evaluate(String.valueOf(h.payeeId()), txns, ctx);
                            double payout = 0;
                            for (Engine.LedgerLine line : r.lines()) {
                                payout += line.amount().doubleValue();
                            }
                            wPayee[p][k] = payout;
                            total += payout;
                        }
                        wTotals[k] = total;
                    }
                    return new WorkerOut(idx, wTotals, wPayee);
                }));
            }
            for (Future<WorkerOut> f : futures) {
                WorkerOut w = f.get();
                for (int k = 0; k < w.idx.size(); k++) {
                    totals[w.idx.get(k)] = w.totals[k];
                    for (int p = 0; p < histories.size(); p++) {
                        perPayee[p][w.idx.get(k)] = w.payee[p][k];
                    }
                }
            }
            return assemble(iterations, totals, perPayee, histories);
        } finally {
            pool.shutdownNow();
        }
    }

    private record WorkerOut(List<Integer> idx, double[] totals, double[][] payee) {
    }

    private static List<Double> bootstrap(MonteCarloSimulator sim, List<Double> deals) {
        try {
            var m = MonteCarloSimulator.class.getDeclaredMethod("bootstrap", List.class);
            m.setAccessible(true);
            return (List<Double>) m.invoke(sim, deals);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private MonteCarloSimulator.SimulationResult assemble(int iterations, double[] totals,
            double[][] perPayee, List<MonteCarloSimulator.PayeeHistory> histories) {
        double[] sorted = java.util.Arrays.copyOf(totals, totals.length);
        java.util.Arrays.sort(sorted);
        double mean = 0;
        for (double t : totals) {
            mean += t;
        }
        mean /= iterations;
        List<MonteCarloSimulator.PayeeOutcome> outcomes = new ArrayList<>();
        for (int p = 0; p < histories.size(); p++) {
            double[] series = java.util.Arrays.copyOf(perPayee[p], iterations);
            java.util.Arrays.sort(series);
            double p50 = pct(sortedOf(series), 0.50);
            double p95 = pct(sortedOf(series), 0.95);
            double hist = histories.get(p).historicalMeanPayout();
            double ratio = hist > 0 ? p95 / hist : (p95 > 0 ? Double.POSITIVE_INFINITY : 0);
            outcomes.add(new MonteCarloSimulator.PayeeOutcome(
                    histories.get(p).payeeId(), p50, p95, ratio));
        }
        List<MonteCarloSimulator.IterationOutcome> sample = new ArrayList<>();
        for (int it : new int[]{0, iterations / 2, iterations - 1}) {
            double max = 0;
            for (int p = 0; p < histories.size(); p++) {
                max = Math.max(max, perPayee[p][it]);
            }
            sample.add(new MonteCarloSimulator.IterationOutcome(it, totals[it], max));
        }
        return new MonteCarloSimulator.SimulationResult(iterations, mean,
                pct(sortedOf(sorted), 0.50), pct(sortedOf(sorted), 0.95),
                pct(sortedOf(sorted), 0.99), outcomes, sample);
    }

    private static double[] sortedOf(double[] a) {
        return a; // caller already sorted
    }

    private static double pct(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double v = sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    // ---------------------------------------------------------------- persistence
    private EngineModel loadPlan(String hash) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT model_json FROM plan_artifact WHERE plan_version_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new WebApplicationException(Response.status(404)
                            .entity(Map.of("error", "unknown plan version")).build());
                }
                String json = rs.getString(1);
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(json, EngineModel.class);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("plan model unreadable: " + e.getMessage(), e);
            }
        }
    }

    private List<MonteCarloSimulator.PayeeHistory> loadHistories(String tenantId) throws SQLException {
        List<MonteCarloSimulator.PayeeHistory> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ca.payee_id::text, t.booking_amount::float8, "
                             + "coalesce(sum(l.payout_amount) / greatest(count(l.ledger_id),1), 0)::float8 "
                             + "FROM raw_transaction t "
                             + "JOIN credit_allocation ca ON ca.transaction_id = t.transaction_id "
                             + "LEFT JOIN commission_ledger_entry l ON l.transaction_id = t.transaction_id "
                             + "WHERE t.tenant_id = CAST(? AS uuid) "
                             + "GROUP BY ca.payee_id, t.booking_amount "
                             + "ORDER BY ca.payee_id")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, List<Double>> deals = new LinkedHashMap<>();
                Map<String, double[]> sums = new LinkedHashMap<>();
                while (rs.next()) {
                    String payee = rs.getString(1);
                    double amount = rs.getDouble(2);
                    deals.computeIfAbsent(payee, k -> new ArrayList<>()).add(amount);
                    double[] s = sums.computeIfAbsent(payee, k -> new double[2]);
                    s[0] += rs.getDouble(3);
                    s[1] += 1;
                }
                for (Map.Entry<String, List<Double>> e : deals.entrySet()) {
                    double[] s = sums.get(e.getKey());
                    out.add(new MonteCarloSimulator.PayeeHistory(e.getKey(), e.getValue(),
                            s[1] > 0 ? s[0] / s[1] : 0));
                }
            }
        }
        return out;
    }

    private void insertRun(UUID simId, SimRequest request, int iterations, long seed) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO simulation_run (simulation_id, tenant_id, plan_version_hash, "
                             + "period_key, iterations, seed) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, simId);
            ps.setObject(2, UUID.fromString(request.tenantId()));
            ps.setString(3, request.planVersionHash());
            ps.setString(4, request.periodKey());
            ps.setInt(5, iterations);
            ps.setLong(6, seed);
            ps.executeUpdate();
        }
    }

    private void persistOutcomes(UUID simId, MonteCarloSimulator.SimulationResult result,
                                 List<MonteCarloSimulator.PayeeHistory> histories) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement it = c.prepareStatement(
                     "INSERT INTO simulation_iteration (iteration_id, simulation_id, iteration, "
                             + "total_payout, max_payee_payout) VALUES (?,?,?,?,?)");
             PreparedStatement po = c.prepareStatement(
                     "INSERT INTO simulation_payee_outcome (simulation_id, payee_id, p50_payout, "
                             + "p95_payout, windfall_ratio) VALUES (?,?,?,?,?)")) {
            for (MonteCarloSimulator.IterationOutcome o : result.sample()) {
                it.setObject(1, UUID.randomUUID());
                it.setObject(2, simId);
                it.setInt(3, o.iteration());
                it.setBigDecimal(4, BigDecimal.valueOf(o.totalPayout()));
                it.setBigDecimal(5, BigDecimal.valueOf(o.maxPayeePayout()));
                it.addBatch();
            }
            it.executeBatch();
            for (MonteCarloSimulator.PayeeOutcome o : result.payees()) {
                po.setObject(1, simId);
                po.setObject(2, UUID.fromString(o.payeeId()));
                po.setBigDecimal(3, BigDecimal.valueOf(o.p50()));
                po.setBigDecimal(4, BigDecimal.valueOf(o.p95()));
                po.setBigDecimal(5, BigDecimal.valueOf(Double.isInfinite(o.windfallRatio())
                        ? 999999 : o.windfallRatio()));
                po.addBatch();
            }
            po.executeBatch();
        }
    }

    private void markCompleted(UUID simId, MonteCarloSimulator.SimulationResult r,
                               double windfallThreshold) throws SQLException {
        int windfalls = r.windfallPayees(windfallThreshold).size();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE simulation_run SET status='COMPLETED', p50_liability=?, "
                             + "p95_liability=?, p99_liability=?, mean_liability=?, "
                             + "windfall_payees=?, completed_at=now() WHERE simulation_id=?")) {
            ps.setBigDecimal(1, BigDecimal.valueOf(r.p50()));
            ps.setBigDecimal(2, BigDecimal.valueOf(r.p95()));
            ps.setBigDecimal(3, BigDecimal.valueOf(r.p99()));
            ps.setBigDecimal(4, BigDecimal.valueOf(r.meanLiability()));
            ps.setInt(5, windfalls);
            ps.setObject(6, simId);
            ps.executeUpdate();
        }
    }

    private void markFailed(UUID simId, String error) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE simulation_run SET status='FAILED', completed_at=now() "
                             + "WHERE simulation_id=?")) {
            ps.setObject(1, simId);
            ps.executeUpdate();
        }
    }

    private Response view(String simulationId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT simulation_id::text, status, iterations, p50_liability::text, "
                             + "p95_liability::text, p99_liability::text, mean_liability::text, "
                             + "windfall_payees FROM simulation_run WHERE simulation_id = CAST(? AS uuid)")) {
            ps.setString(1, simulationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "unknown simulation")).build();
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("simulationId", rs.getString(1));
                out.put("status", rs.getString(2));
                out.put("iterations", rs.getInt(3));
                out.put("p50Liability", rs.getString(4));
                out.put("p95Liability", rs.getString(5));
                out.put("p99Liability", rs.getString(6));
                out.put("meanLiability", rs.getString(7));
                out.put("windfallPayees", rs.getInt(8));
                return Response.ok(out).build();
            }
        }
    }

    private void auditSim(String tenantId, UUID simId, MonteCarloSimulator.SimulationResult r) {
        // calculation-run style event for the audit plane (outbox via existing exporters)
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, tenant_id) "
                             + "VALUES ('eim.sim.completed', ?, CAST(? AS jsonb), CAST(? AS uuid))")) {
            ps.setString(1, simId.toString());
            ps.setString(2, "{\"iterations\":" + r.iterations()
                    + ",\"mean\":\"" + r.meanLiability() + "\"}");
            ps.setString(3, tenantId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // simulation telemetry is best-effort; the run row is the record
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
