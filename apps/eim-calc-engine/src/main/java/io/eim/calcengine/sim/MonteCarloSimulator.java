package io.eim.calcengine.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Monte Carlo plan simulation (TG8-CAL-1, FR-1001): stress-tests a
 * candidate plan against per-payee historical deal distributions, forecasts
 * total payout liability with percentile bands, and flags windfall payees
 * (p95 far above their historical mean). Deals are resampled per iteration
 * from each payee's empirical history (bootstrap), so no distributional
 * assumptions are imposed; a fixed seed makes runs reproducible. The payout
 * function is injected — production binds the real Engine on the published
 * plan model; tests bind the marginal tier function directly.
 */
public final class MonteCarloSimulator {

    public record PayeeHistory(String payeeId, List<Double> dealAmounts, double historicalMeanPayout) {
    }

    public interface PayoutFunction {
        /** Payout for the sampled deal set — binds the real Engine or a tier fn. */
        double payout(List<Double> sampledDeals);
    }

    public record IterationOutcome(int iteration, double totalPayout, double maxPayeePayout) {
    }

    public record PayeeOutcome(String payeeId, double p50, double p95, double windfallRatio) {
    }

    public record SimulationResult(int iterations, double meanLiability,
                                   double p50, double p95, double p99,
                                   List<PayeeOutcome> payees,
                                   List<IterationOutcome> sample) {
        public List<String> windfallPayees(double ratioThreshold) {
            List<String> out = new ArrayList<>();
            for (PayeeOutcome p : payees) {
                if (p.windfallRatio() >= ratioThreshold) {
                    out.add(p.payeeId());
                }
            }
            return out;
        }
    }

    private final SplittableRandom rng;

    public MonteCarloSimulator(long seed) {
        this.rng = new SplittableRandom(seed);
    }

    public SimulationResult simulate(List<PayeeHistory> histories,
                                     PayoutFunction payoutFn,
                                     int iterations,
                                     double windfallRatioThreshold) {
        if (histories == null || histories.isEmpty() || iterations <= 0) {
            throw new IllegalArgumentException("histories and iterations are required");
        }
        double[] totals = new double[iterations];
        double[][] perPayee = new double[histories.size()][iterations];
        double overallMax = 0;
        List<IterationOutcome> sample = new ArrayList<>();
        for (int it = 0; it < iterations; it++) {
            double total = 0;
            double maxPayee = 0;
            for (int p = 0; p < histories.size(); p++) {
                PayeeHistory h = histories.get(p);
                List<Double> sampled = bootstrap(h.dealAmounts());
                double payout = payoutFn.payout(sampled);
                if (payout < 0) {
                    payout = 0; // draws/clawbacks never produce negative liability for forecasting
                }
                perPayee[p][it] = payout;
                total += payout;
                if (payout > maxPayee) {
                    maxPayee = payout;
                }
            }
            totals[it] = total;
            if (maxPayee > overallMax) {
                overallMax = maxPayee;
            }
            if (it < 10 || it == iterations / 2 || it == iterations - 1) {
                sample.add(new IterationOutcome(it, total, maxPayee));
            }
        }
        java.util.Arrays.sort(totals);
        double mean = 0;
        for (double t : totals) {
            mean += t;
        }
        mean /= iterations;

        List<PayeeOutcome> outcomes = new ArrayList<>();
        for (int p = 0; p < histories.size(); p++) {
            double[] series = perPayee[p];
            double[] copy = java.util.Arrays.copyOf(series, series.length);
            java.util.Arrays.sort(copy);
            double p50 = percentile(copy, 0.50);
            double p95 = percentile(copy, 0.95);
            double hist = histories.get(p).historicalMeanPayout();
            double ratio = hist > 0 ? p95 / hist : (p95 > 0 ? Double.POSITIVE_INFINITY : 0);
            outcomes.add(new PayeeOutcome(histories.get(p).payeeId(), p50, p95, round(ratio)));
        }
        return new SimulationResult(iterations, round(mean),
                percentile(totals, 0.50), percentile(totals, 0.95),
                percentile(totals, 0.99), outcomes, sample);
    }

    private List<Double> bootstrap(List<Double> deals) {
        if (deals.isEmpty()) {
            return List.of();
        }
        // resample with replacement (empirical bootstrap); ~75% sample size
        // keeps iteration variance honest for small histories
        int n = Math.max(1, (int) Math.round(deals.size() * 0.75));
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(deals.get(rng.nextInt(deals.size())));
        }
        return out;
    }

    /** Linear interpolation on the sorted series (type 7, R default). */
    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 1) {
            return round(sorted[0]);
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = pos - lo;
        return round(sorted[lo] + frac * (sorted[hi] - sorted[lo]));
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }
}
