package io.eim.coreapi;

import io.eim.coreapi.accounting.AmortizationEngine;
import io.eim.coreapi.accounting.DmnClassifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG7-ACC-2/3 (FR-901/902): the amortization conventions and the generated
 * ASC 606 classification matrix as pure units — every convention's schedule
 * sums exactly to the capitalized amount (FR-902.4 residual rule), the
 * ST/LT split partitions the remaining balance (FR-906.2), and the DMN
 * decision table produces the FR-901.1 default matrix incl. the practical
 * expedient and the toggleable overrides.
 */
class AmortizationEngineTest {

    private static BigDecimal sum(List<AmortizationEngine.ScheduleLine> lines) {
        return lines.stream().map(AmortizationEngine.ScheduleLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void exactDailySumsExactlyWithLeapYearAwareness() {
        // 2026-01-01 + 24 months, $120,000.0000
        List<AmortizationEngine.ScheduleLine> lines = AmortizationEngine.schedule(
                new BigDecimal("120000.0000"), LocalDate.of(2026, 1, 1), 24,
                AmortizationEngine.Convention.EXACT_DAILY, null);
        assertEquals(24, lines.size());
        assertEquals(0, sum(lines).compareTo(new BigDecimal("120000.0000")),
                "Σ schedule ≡ asset (residual absorbed in final period)");
        // Feb-2026 has 28 days → smaller than Jan; leap Feb-2028 (366) larger than Feb-2027
        BigDecimal feb26 = lines.get(1).amount();
        BigDecimal jan26 = lines.get(0).amount();
        assertTrue(feb26.compareTo(jan26) < 0, "28-day February < 31-day January");
        assertEquals("2026-01", lines.get(0).periodKey());
        assertEquals("2027-12", lines.get(23).periodKey());
    }

    @Test
    void fullMonthEqualMonthlyAndMidMonthConvention() {
        List<AmortizationEngine.ScheduleLine> full = AmortizationEngine.schedule(
                new BigDecimal("12000.0000"), LocalDate.of(2026, 1, 5), 12,
                AmortizationEngine.Convention.FULL_MONTH, null);
        assertEquals(12, full.size());
        assertEquals(0, sum(full).compareTo(new BigDecimal("12000.0000")));
        assertEquals(0, full.get(0).amount().compareTo(new BigDecimal("1000.0000")),
                "equal monthly straight line");

        // mid-month: a start after the 15th amortizes from the NEXT month
        List<AmortizationEngine.ScheduleLine> mid = AmortizationEngine.schedule(
                new BigDecimal("12000.0000"), LocalDate.of(2026, 1, 20), 12,
                AmortizationEngine.Convention.MID_MONTH, null);
        assertEquals("2026-02", mid.get(0).periodKey(), "mid-month convention starts next month");
        assertEquals(0, sum(mid).compareTo(new BigDecimal("12000.0000")));
    }

    @Test
    void poWeightedFollowsErpRevenueEventsWithFallback() {
        List<AmortizationEngine.POWeight> weights = List.of(
                new AmortizationEngine.POWeight("2026-01", new BigDecimal("60")),
                new AmortizationEngine.POWeight("2026-02", new BigDecimal("40")));
        List<AmortizationEngine.ScheduleLine> po = AmortizationEngine.schedule(
                new BigDecimal("10000.0000"), LocalDate.of(2026, 1, 1), 3,
                AmortizationEngine.Convention.PO_WEIGHTED, weights);
        assertEquals(0, po.get(0).amount().compareTo(new BigDecimal("6000.0000")),
                "60% of revenue recognized in Jan");
        assertEquals(0, po.get(1).amount().compareTo(new BigDecimal("4000.0000")));
        assertEquals(0, po.get(2).amount().compareTo(BigDecimal.ZERO),
                "no PO events in Mar → zero");
        assertEquals(0, sum(po).compareTo(new BigDecimal("10000.0000")));

        // no ERP events at all → straight-line fallback keeps the identity
        List<AmortizationEngine.ScheduleLine> fallback = AmortizationEngine.schedule(
                new BigDecimal("9000.0000"), LocalDate.of(2026, 1, 1), 3,
                AmortizationEngine.Convention.PO_WEIGHTED, List.of());
        assertEquals(0, fallback.get(0).amount().compareTo(new BigDecimal("3000.0000")));
        assertEquals(0, sum(fallback).compareTo(new BigDecimal("9000.0000")));
    }

    @Test
    void stLtSplitPartitionsNextTwelveMonths() {
        List<AmortizationEngine.ScheduleLine> lines = AmortizationEngine.schedule(
                new BigDecimal("24000.0000"), LocalDate.of(2026, 1, 1), 24,
                AmortizationEngine.Convention.FULL_MONTH, null);
        BigDecimal[] split = AmortizationEngine.stLtSplit(lines, "2026-01");
        assertEquals(0, split[0].compareTo(new BigDecimal("12000.0000")),
                "first 12 months are current");
        assertEquals(0, split[1].compareTo(new BigDecimal("12000.0000")),
                "months 13-24 are non-current");
        assertEquals(0, split[0].add(split[1]).compareTo(new BigDecimal("24000.0000")));
    }

    @Test
    void defaultMatrixImplementsFr901Rules() {
        String dmn = DmnClassifier.defaultDmn(true, "FORMULAIC_ONLY",
                "CAP_NON_COMMENSURATE", 12);
        DmnClassifier c = new DmnClassifier();

        // practical expedient: 12-month term at a 12-month threshold → expense
        var expedient = c.classify(dmn, "commission", "CALCULATED", "AE", 12);
        assertEquals("EXPENSE", expedient.classification());
        assertEquals("PRACTICAL_EXPEDIENT", expedient.reasonCode());

        // direct AE commission, 24-month term → capitalize
        var commission = c.classify(dmn, "commission", "CALCULATED", "AE", 24);
        assertEquals("CAPITALIZE", commission.classification());
        assertEquals("NEW_LOGO_COMMISSION", commission.reasonCode());
        assertEquals(24, commission.months());

        // clawback offsets are never capitalized
        var clawback = c.classify(dmn, "commission", "CLAWBACK", "AE", 24);
        assertEquals("EXPENSE", clawback.classification());
        assertEquals("CLAWBACK_OFFSET", clawback.reasonCode());

        // manager override capitalized when the toggle is on...
        var mgr = c.classify(dmn, "commission", "CALCULATED", "Mgr", 24);
        assertEquals("CAPITALIZE", mgr.classification());
        assertEquals("MANAGER_OVERRIDE", mgr.reasonCode());
        // ...expensed when off
        String off = DmnClassifier.defaultDmn(false, "FORMULAIC_ONLY",
                "CAP_NON_COMMENSURATE", 12);
        var mgrOff = c.classify(off, "commission", "CALCULATED", "Mgr", 24);
        assertEquals("EXPENSE", mgrOff.classification());
        assertEquals("MANAGER_OVERRIDE_EXPENSED", mgrOff.reasonCode());

        // SE spiffs: strictly formulaic only — a manual spiff is expensed
        assertEquals("SE_SPIFF_NON_FORMULAIC",
                c.classify(dmn, "Q1_Spiff", "MANUAL_OVERRIDE", "AE", 24).reasonCode());
        assertEquals("SE_SPIFF_EXPENSED_BY_POLICY",
                c.classify(DmnClassifier.defaultDmn(true, "NEVER", "CAP_NON_COMMENSURATE", 12),
                        "Q1_Spiff", "CALCULATED", "AE", 24).reasonCode());

        // renewals: capitalized when non-commensurate, expensed by policy
        assertEquals("RENEWAL_NON_COMMENSURATE",
                c.classify(dmn, "renewal_commission", "CALCULATED", "AE", 24).reasonCode());
        assertEquals("RENEWAL_EXPENSED_BY_POLICY",
                c.classify(DmnClassifier.defaultDmn(true, "FORMULAIC_ONLY", "EXPENSE", 12),
                        "renewal_commission", "CALCULATED", "AE", 24).reasonCode());

        // fringe / payroll tax components capitalize at the fringe rate
        var fringe = c.classify(dmn, "payroll tax", "CALCULATED", "AE", 24);
        assertEquals("CAPITALIZE", fringe.classification());
        assertEquals("FRINGE_RATE", fringe.reasonCode());
    }

    @Test
    void bespokeControllerTableValidatesAndEvaluates() {
        // Controller override: expense everything in the statutory book
        String bespoke = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/DMN/20180521/MODEL/\" "
                + "id=\"eim-asc606-classification\" name=\"eim-asc606-classification\" "
                + "namespace=\"https://eim.io/accounting/classification\">\n"
                + "  <inputData id=\"in_months\" name=\"amortization_months_hint\">"
                + "<variable id=\"bv1\" name=\"amortization_months_hint\" typeRef=\"number\"/></inputData>\n"
                + "  <inputData id=\"in_entry\" name=\"entry_type\">"
                + "<variable id=\"bv2\" name=\"entry_type\" typeRef=\"string\"/></inputData>\n"
                + "  <inputData id=\"in_component\" name=\"component_family\">"
                + "<variable id=\"bv3\" name=\"component_family\" typeRef=\"string\"/></inputData>\n"
                + "  <inputData id=\"in_role\" name=\"payee_role_family\">"
                + "<variable id=\"bv4\" name=\"payee_role_family\" typeRef=\"string\"/></inputData>\n"
                + "  <decision id=\"classify\" name=\"classify\">\n"
                + "    <variable id=\"classify_var\" name=\"classify\"/>\n"
                + "    <informationRequirement id=\"b1\"><requiredInput href=\"#in_months\"/></informationRequirement>"
                + "<informationRequirement id=\"b2\"><requiredInput href=\"#in_entry\"/></informationRequirement>"
                + "<informationRequirement id=\"b3\"><requiredInput href=\"#in_component\"/></informationRequirement>"
                + "<informationRequirement id=\"b4\"><requiredInput href=\"#in_role\"/></informationRequirement>\n"
                + "    <decisionTable id=\"matrix\" hitPolicy=\"FIRST\" "
                + "preferredOrientation=\"Rule-as-Row\">\n"
                + "      <input id=\"i_amort_months\" name=\"amortization_months_hint\">"
                + "<inputExpression id=\"ie1\" typeRef=\"number\">"
                + "<text>amortization_months_hint</text></inputExpression></input>\n"
                + "      <input id=\"i_entry_type\" name=\"entry_type\">"
                + "<inputExpression id=\"ie2\" typeRef=\"string\">"
                + "<text>entry_type</text></inputExpression></input>\n"
                + "      <input id=\"i_component\" name=\"component_family\">"
                + "<inputExpression id=\"ie3\" typeRef=\"string\">"
                + "<text>component_family</text></inputExpression></input>\n"
                + "      <input id=\"i_payee_role\" name=\"payee_role_family\">"
                + "<inputExpression id=\"ie4\" typeRef=\"string\">"
                + "<text>payee_role_family</text></inputExpression></input>\n"
                + "      <output id=\"o1\" name=\"classification\" typeRef=\"string\"/>\n"
                + "      <output id=\"o2\" name=\"reason_code\" typeRef=\"string\"/>\n"
                + "      <output id=\"o3\" name=\"amortization_months\" typeRef=\"number\"/>\n"
                + "      <rule id=\"r0\">"
                + "<inputEntry id=\"r0i1\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"r0i2\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"r0i3\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"r0i4\"><text>-</text></inputEntry>"
                + "<outputEntry id=\"r0o1\"><text>\"EXPENSE\"</text></outputEntry>"
                + "<outputEntry id=\"r0o2\"><text>\"STATUTORY_EXPENSE_ALL\"</text></outputEntry>"
                + "<outputEntry id=\"r0o3\"><text>12</text></outputEntry>"
                + "</rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
        DmnClassifier c = new DmnClassifier();
        assertTrue(c.validate(bespoke).isEmpty(), "bespoke table must compile");
        var d = c.classify(bespoke, "commission", "CALCULATED", "AE", 24);
        assertEquals("EXPENSE", d.classification());
        assertEquals("STATUTORY_EXPENSE_ALL", d.reasonCode());
    }
}
