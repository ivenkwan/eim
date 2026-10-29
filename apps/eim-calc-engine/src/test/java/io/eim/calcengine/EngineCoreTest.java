package io.eim.calcengine;

import io.eim.calcengine.engine.Engine;
import io.eim.calcengine.engine.EngineModel;
import io.eim.calcengine.engine.Precise;
import io.eim.calcengine.engine.TierSlicer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG3-CAL-1/2/3/4/6/7/8/9 golden suite (pure core, no DB): the canonical
 * marginal case is permanent (FR-502.1), rounding is single-point with
 * half-even/half-up boundary proofs (NFR-01/02), ordering is
 * deterministic, and every protection behaves per FR-404..506.
 */
class EngineCoreTest {

    static EngineModel.TierTable canonicalTiers() {
        return new EngineModel.TierTable("MARGINAL", "AMOUNT", List.of(
                new EngineModel.Bracket("100000", "0.10"),
                new EngineModel.Bracket("150000", "0.15"),
                new EngineModel.Bracket(null, "0.20")));
    }

    @Test
    void canonicalThreeSliceCase() {
        // FR-502.1: attainment $90k -> $160k over 10/15/20% tiers.
        BigDecimal payout = TierSlicer.slice(canonicalTiers(),
                Precise.v("90000"), Precise.v("160000"), new ArrayList<>());
        assertEquals(0, new BigDecimal("10500").compareTo(payout),
                "canonical marginal total");
        assertEquals(new BigDecimal("10500.00"),
                Precise.roundPayout(payout, RoundingMode.HALF_EVEN));
        List<TierSlicer.Slice> slices = TierSlicer.slices(canonicalTiers(),
                Precise.v("90000"), Precise.v("160000"));
        assertEquals(3, slices.size(), "three slices");
        assertEquals(0, new BigDecimal("1000").compareTo(slices.get(0).slicePayout()));
        assertEquals(0, new BigDecimal("7500").compareTo(slices.get(1).slicePayout()));
        assertEquals(0, new BigDecimal("2000").compareTo(slices.get(2).slicePayout()));
    }

    @Test
    void boundaryAmountsStayInsideLowerTier() {
        // exactly at a boundary: no double-count, no gap
        assertEquals(0, new BigDecimal("10000").compareTo(TierSlicer.slice(canonicalTiers(),
                        Precise.v("0"), Precise.v("100000"), new ArrayList<>())
                ));
        assertEquals(0, new BigDecimal("17500").compareTo(TierSlicer.slice(canonicalTiers(),
                        Precise.v("0"), Precise.v("150000"), new ArrayList<>())
                ));
        assertEquals(0, BigDecimal.ZERO.compareTo(TierSlicer.slice(canonicalTiers(),
                Precise.v("160000"), Precise.v("160000"), new ArrayList<>())));
    }

    @Test
    void cliffModeUsesBracketContainingTo() {
        EngineModel.TierTable cliff = new EngineModel.TierTable("CLIFF", "AMOUNT", List.of(
                new EngineModel.Bracket("100000", "0.10"),
                new EngineModel.Bracket(null, "0.20")));
        assertEquals(0, new BigDecimal("10000").compareTo(TierSlicer.slice(cliff,
                        Precise.v("0"), Precise.v("100000"), new ArrayList<>())
                ));
        assertEquals(0, new BigDecimal("32000").compareTo(TierSlicer.slice(cliff,
                        Precise.v("0"), Precise.v("160000"), new ArrayList<>())
                ));
    }

    @Test
    void singlePointRoundingHalfEvenThenHalfUp() {
        // 2.345 -> 2.34 (HALF_EVEN, to even), 2.35 (HALF_UP): the classic
        // boundary pair proving mode wiring, at the single payout point.
        assertEquals(new BigDecimal("2.34"), Precise.roundPayout(
                new BigDecimal("2.345"), RoundingMode.HALF_EVEN));
        assertEquals(new BigDecimal("2.35"), Precise.roundPayout(
                new BigDecimal("2.345"), RoundingMode.HALF_UP));
        assertEquals(new BigDecimal("2.35"), Precise.roundPayout(
                new BigDecimal("2.3451"), RoundingMode.HALF_EVEN));
    }

    @Test
    void deterministicOrderingIndependentlyOfInputOrder() {
        EngineModel plan = new EngineModel("t", List.of(
                new EngineModel.Component("commission", EngineModel.Kind.TIER_TABLE, null,
                        null, canonicalTiers(), null, null)));
        Engine a = new Engine(plan, RoundingMode.HALF_EVEN);
        Engine b = new Engine(plan, RoundingMode.HALF_EVEN);
        List<Engine.Txn> ordered = new ArrayList<>(List.of(
                new Engine.Txn("t1", LocalDate.parse("2026-04-01"), "2026-04-01T01:00:00Z",
                        new BigDecimal("90000")),
                new Engine.Txn("t2", LocalDate.parse("2026-04-15"), "2026-04-15T01:00:00Z",
                        new BigDecimal("70000"))));
        List<Engine.Txn> shuffled = new ArrayList<>(List.of(ordered.get(1), ordered.get(0)));
        // booking total is order-independent; the deterministic ORDERING is
        // exercised by WavefrontAndLedgerTest through the SQL ORDER BY.
        assertEquals(a.evaluate("p", ordered, Map.of()).components().get("window_to"),
                b.evaluate("p", shuffled, Map.of()).components().get("window_to"));
        // carry-forward window: a later engine call resumes from window_to
        Engine.PayeeResult first = a.evaluate("p", ordered, Map.of());
        Engine.PayeeResult second = a.evaluate("p", List.of(new Engine.Txn("t3",
                LocalDate.parse("2026-05-02"), "2026-05-02T00:00:00Z", new BigDecimal("1000"))),
                Map.of("window_from", first.components().get("window_to").toPlainString()));
        assertEquals(0, new BigDecimal("161000").compareTo(second.components().get("window_to")
                ));
        assertTrue(second.trace().stream().anyMatch(s -> "commission".equals(s.get("component"))),
                "window state exposed to traces (FR-404)");
    }

    @Test
    void capsRecordCappedResidual() {
        EngineModel plan = new EngineModel("t", List.of(
                new EngineModel.Component("commission", EngineModel.Kind.TIER_TABLE, null,
                        null, canonicalTiers(), null, null),
                new EngineModel.Component("capped", EngineModel.Kind.CAP, null, null, null,
                        new EngineModel.Protections(null, "PERIOD", "AMOUNT", "5000", null),
                        List.of("commission"))));
        Engine.PayeeResult r = new Engine(plan, RoundingMode.HALF_EVEN).evaluate("p",
                List.of(new Engine.Txn("t1", LocalDate.parse("2026-04-01"), "ts",
                        new BigDecimal("160000"))), Map.of());
        assertEquals(0, new BigDecimal("5000").compareTo(r.components().get("capped")));
        assertTrue(r.trace().stream().filter(s -> "capped".equals(s.get("component")))
                .anyMatch(s -> String.valueOf(s.get("cappedResidual")).startsWith("14500")),
                "capped residual in trace (FR-405.3): " + r.trace());
    }

    @Test
    void drawsRecoverCarryForwardDeficit() {
        EngineModel plan = new EngineModel("t", List.of(
                new EngineModel.Component("earnings", EngineModel.Kind.FORMULA,
                        "$booked_amount", null, null, null, null),
                new EngineModel.Component("net_pay", EngineModel.Kind.DRAW, null, null, null,
                        new EngineModel.Protections("RECOVERABLE", null, null, null, null),
                        List.of("earnings"))));
        Engine e = new Engine(plan, RoundingMode.HALF_EVEN);
        // opening deficit 4000 recovered from 6000 earnings -> 2000 net
        Engine.PayeeResult r = e.evaluate("p",
                List.of(new Engine.Txn("t1", LocalDate.parse("2026-04-01"), "ts",
                        new BigDecimal("6000"))),
                Map.of("draw_balance", "4000"));
        assertEquals(0, new BigDecimal("2000").compareTo(r.components().get("net_pay")));
        assertTrue(r.lines().stream().anyMatch(l -> "DRAW_RECOVERY".equals(l.entryType())));
        // NON_RECOVERABLE: full earnings pass through, deficit carries
        EngineModel plan2 = new EngineModel("t", List.of(plan.components().get(0),
                new EngineModel.Component("net_pay", EngineModel.Kind.DRAW, null, null, null,
                        new EngineModel.Protections("NON_RECOVERABLE", null, null, null, null),
                        List.of("earnings"))));
        Engine.PayeeResult r2 = new Engine(plan2, RoundingMode.HALF_EVEN).evaluate("p",
                List.of(new Engine.Txn("t1", LocalDate.parse("2026-04-01"), "ts",
                        new BigDecimal("6000"))),
                Map.of("draw_balance", "4000"));
        assertEquals(0, new BigDecimal("6000").compareTo(r2.components().get("net_pay")
                ));
    }

    @Test
    void clawbackProratesByActiveDaysAndLinksOrigin() {
        EngineModel plan = new EngineModel("t", List.of(
                new EngineModel.Component("clawback", EngineModel.Kind.CLAWBACK, null, null,
                        null, new EngineModel.Protections(null, null, null, null, "90"),
                        null)));
        Engine.PayeeResult r = new Engine(plan, RoundingMode.HALF_EVEN).evaluate("p",
                List.of(new Engine.Txn("churned-1", LocalDate.parse("2026-03-10"), "ts",
                        new BigDecimal("8000"))),
                Map.of("churned_txn", "churned-1",
                        "active_days", "15", "period_days", "30",
                        "origin_period_id", "11111111-0000-0000-0000-00000000eeee"));
        // half the period active -> half the credited amount clawed back
        assertEquals(0, new BigDecimal("-4000").compareTo(r.components().get("clawback")
                ));
        Engine.LedgerLine line = r.lines().stream()
                .filter(l -> "CLAWBACK".equals(l.entryType())).findFirst().orElseThrow();
        assertEquals("churned-1", line.transactionId(), "origin linkage (FR-505)");
        assertEquals("11111111-0000-0000-0000-00000000eeee", line.originPeriodId());
    }

    @Test
    void prorationFactorClampsToPeriod() {
        assertEquals(0, new BigDecimal("0.5").compareTo(Precise.prorationFactor(15, 30)
                ));
        assertEquals(0, BigDecimal.ONE.compareTo(Precise.prorationFactor(40, 30)));
        assertEquals(0, BigDecimal.ZERO.compareTo(Precise.prorationFactor(0, 30)));
    }
}
