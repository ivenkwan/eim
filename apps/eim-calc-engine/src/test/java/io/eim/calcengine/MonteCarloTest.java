package io.eim.calcengine;

import io.eim.calcengine.sim.MonteCarloSimulator;
import io.eim.calcengine.sim.MonteCarloSimulator.PayeeHistory;
import io.eim.calcengine.sim.MonteCarloSimulator.SimulationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG8-CAL-1 (FR-1001): Monte Carlo bootstrap — reproducibility under a
 * fixed seed, percentile bands that bracket the deterministic tier payout
 * of the sampled distribution, windfall detection (a plan that doubles the
 * payout of one payee is flagged; the unchanged payee is not), and
 * monotone liability response to an aggressive plan.
 */
class MonteCarloTest {

    /** Marginal tier payout used by the canonical case: 10/15/20%. */
    private static double tierPayout(List<Double> deals) {
        double amount = 0;
        for (double d : deals) {
            amount += d;
        }
        double remaining = amount;
        double prevCap = 0;
        double payout = 0;
        double[] rates = {0.10, 0.15, 0.20};
        double[] caps = {100_000, 150_000, Double.MAX_VALUE};
        for (int i = 0; i < rates.length && remaining > 0; i++) {
            double slice = Math.min(remaining, caps[i] - prevCap);
            payout += slice * rates[i];
            remaining -= slice;
            prevCap = caps[i];
        }
        return payout;
    }

    private static List<PayeeHistory> twoPayees() {
        return List.of(
                // historical means are historical PAYOUTS (tier outputs), so the
                // windfall ratio compares like-for-like
                new PayeeHistory("p1", List.of(60000.0, 70000.0, 80000.0, 90000.0), 47000.0),
                new PayeeHistory("p2", List.of(20000.0, 25000.0, 30000.0), 7500.0));
    }

    @Test
    void fixedSeedIsReproducible() {
        MonteCarloSimulator a = new MonteCarloSimulator(42L);
        MonteCarloSimulator b = new MonteCarloSimulator(42L);
        SimulationResult ra = a.simulate(twoPayees(), MonteCarloTest::tierPayout, 500, 2.0);
        SimulationResult rb = b.simulate(twoPayees(), MonteCarloTest::tierPayout, 500, 2.0);
        assertEquals(ra.meanLiability(), rb.meanLiability(), "same seed → identical mean");
        assertEquals(ra.p99(), rb.p99(), "identical p99");
    }

    @Test
    void percentileBandsBracketExpectedPayout() {
        // two payees × ~75% of 3-4 deals ≈ 60-150k total → tier payout
        // ≈ 2 × (7-11k). p50 should sit near that; p95 above p50; p99 above p95.
        SimulationResult r = new MonteCarloSimulator(7L)
                .simulate(twoPayees(), MonteCarloTest::tierPayout, 2_000, 2.0);
        // p1 ~28k + p2 ~6.5k tier payouts per iteration
        assertTrue(r.p50() > 28_000 && r.p50() < 42_000, "p50 near deterministic tier: " + r.p50());
        assertTrue(r.p95() > r.p50(), "p95 > p50");
        assertTrue(r.p99() >= r.p95(), "p99 >= p95");
        assertTrue(r.meanLiability() >= r.p50() * 0.8 && r.meanLiability() <= r.p95(),
                "mean between p50 band and p95: " + r.meanLiability());
    }

    @Test
    void windfallDetectionFlagsOnlyTheAggressivePayee() {
        // plan A: baseline tiers. plan B: doubles only the LARGE-deal payee
        // (p1's deals are 60-90k; p2's are 20-30k) — exactly the shape of a
        // windfall-inducing accelerator change on the top segment.
        java.util.function.Function<List<Double>, Double> aggressiveFn = deals -> {
            double avg = deals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return tierPayout(deals) * (avg > 50_000 ? 2 : 1);
        };
        SimulationResult base = new MonteCarloSimulator(11L)
                .simulate(twoPayees(), MonteCarloTest::tierPayout, 1_000, 1.5);
        SimulationResult aggressive = new MonteCarloSimulator(11L)
                .simulate(twoPayees(), aggressiveFn::apply, 1_000, 1.5);
        assertTrue(base.windfallPayees(1.5).isEmpty(),
                "baseline flags no windfalls: " + base.windfallPayees(1.5));
        assertTrue(aggressive.windfallPayees(1.5).equals(List.of("p1")),
                "aggressive plan flags exactly p1: " + aggressive.windfallPayees(1.5));
        assertTrue(aggressive.meanLiability() > base.meanLiability() * 1.5,
                "liability responds to the plan: " + aggressive.meanLiability());
    }

    @Test
    void emptyHistoryAndBadArgsAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MonteCarloSimulator(1L).simulate(List.of(), MonteCarloTest::tierPayout, 100, 2.0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MonteCarloSimulator(1L).simulate(twoPayees(), MonteCarloTest::tierPayout, 0, 2.0));
    }
}
