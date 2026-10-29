package io.eim.calcengine.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Marginal tier slicing via interval intersection (FR-502): attainment
 * moves [from, to); each bracket contributes the intersection with
 * [floor, ceiling) at its rate. CLIFF applies the single bracket containing
 * {@code to}. Slices are NEVER rounded — the caller rounds once (NFR-02).
 * The canonical case ($90k -> $160k over 10/15/20% = $10,500.00) is a
 * permanent test (FR-502.1).
 */
public final class TierSlicer {

    public record Slice(String tierName, BigDecimal sliceAmount, BigDecimal rate,
                        BigDecimal slicePayout) {
    }

    private TierSlicer() {
    }

    public static BigDecimal slice(EngineModel.TierTable tiers, BigDecimal from, BigDecimal to,
                                   List<Map<String, Object>> traceOut) {
        List<EngineModel.Bracket> brackets = tiers == null || tiers.brackets() == null
                ? List.of() : tiers.brackets();
        boolean cliff = brackets.isEmpty() || "CLIFF".equalsIgnoreCase(String.valueOf(tiers.mode()));

        if (cliff) {
            for (EngineModel.Bracket b : brackets) {
                boolean inside = b.upTo() == null
                        || Precise.v(b.upTo()).compareTo(to) >= 0;
                if (inside) {
                    BigDecimal span = Precise.sub(to, from).max(BigDecimal.ZERO);
                    BigDecimal payout = Precise.mul(span, Precise.v(b.rate()));
                    if (traceOut != null && span.signum() > 0) {
                        traceOut.add(sliceTrace(cliffLabel(b, brackets), span, b.rate(), payout));
                    }
                    return payout;
                }
            }
            return BigDecimal.ZERO;
        }

        BigDecimal payout = BigDecimal.ZERO;
        BigDecimal floor = BigDecimal.ZERO;
        int i = 1;
        for (EngineModel.Bracket b : brackets) {
            BigDecimal ceiling = b.upTo() == null ? to : Precise.v(b.upTo());
            BigDecimal span = Precise.sub(to.min(ceiling), from.max(floor)).max(BigDecimal.ZERO);
            if (span.signum() > 0) {
                BigDecimal slicePayout = Precise.mul(span, Precise.v(b.rate()));
                payout = Precise.add(payout, slicePayout);
                if (traceOut != null) {
                    traceOut.add(sliceTrace("Tier " + i, span, b.rate(), slicePayout));
                }
            }
            if (b.upTo() != null) {
                floor = Precise.v(b.upTo());
            }
            i++;
        }
        return payout;
    }

    private static String cliffLabel(EngineModel.Bracket b, List<EngineModel.Bracket> all) {
        return "Cliff Tier " + (all.indexOf(b) + 1);
    }

    private static Map<String, Object> sliceTrace(String tier, BigDecimal span, String rate,
                                                  BigDecimal payout) {
        return Map.of(
                "tierName", tier,
                "sliceAmount", span.toPlainString(),
                "rateApplied", rate,
                "slicePayout", payout.toPlainString());
    }

    public static List<Slice> slices(EngineModel.TierTable tiers, BigDecimal from, BigDecimal to) {
        List<Map<String, Object>> trace = new ArrayList<>();
        slice(tiers, from, to, trace);
        return trace.stream()
                .map(t -> new Slice((String) t.get("tierName"),
                        Precise.v((String) t.get("sliceAmount")),
                        Precise.v((String) t.get("rateApplied")),
                        Precise.v((String) t.get("slicePayout"))))
                .toList();
    }
}
