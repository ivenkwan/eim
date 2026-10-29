package io.eim.plancompiler.compile;

import io.eim.plancompiler.model.PlanModel;

import java.util.List;

/**
 * Generates the self-contained Java helper bundle that pairs with the DRL:
 * fact types, DECIMAL128 arithmetic (single rounding policy, NFR-01/02) and
 * per-tier slicing methods with the bracket tables baked in. The bundle is
 * organized as sections separated by {@code //==FILE:<path>} markers — one
 * file per public class so any Java compiler (including the KIE build)
 * accepts it.
 */
final class HelperGenerator {

    public static String generate(PlanModel plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(file("eim/rules/Calc.java", """
                package eim.rules;

                import java.math.BigDecimal;
                import java.math.MathContext;
                import java.time.LocalDate;
                import java.time.temporal.ChronoUnit;

                public final class Calc {
                    public static final MathContext MC = new MathContext(34, java.math.RoundingMode.HALF_EVEN);
                    public static BigDecimal v(String s) { return new BigDecimal(s); }
                    public static BigDecimal add(BigDecimal a, BigDecimal b) { return a.add(b, MC); }
                    public static BigDecimal sub(BigDecimal a, BigDecimal b) { return a.subtract(b, MC); }
                    public static BigDecimal mul(BigDecimal a, BigDecimal b) { return a.multiply(b, MC); }
                    public static BigDecimal div(BigDecimal a, BigDecimal b) { return a.divide(b, MC); }
                    public static BigDecimal min(BigDecimal a, BigDecimal b) { return a.min(b); }
                    public static BigDecimal max(BigDecimal a, BigDecimal b) { return a.max(b); }
                    public static boolean lt(BigDecimal a, BigDecimal b) { return a.compareTo(b) < 0; }
                    public static boolean gt(BigDecimal a, BigDecimal b) { return a.compareTo(b) > 0; }
                    public static BigDecimal daysBetween(String a, String b) {
                        return BigDecimal.valueOf(ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)));
                    }
                }
                """));
        sb.append(file("eim/rules/Fact.java", """
                package eim.rules;

                import java.math.BigDecimal;

                public class Fact {
                    private final String component, payee, type;
                    private final BigDecimal value;
                    public Fact(String component, String payee, BigDecimal value, String type) {
                        this.component = component; this.payee = payee;
                        this.value = value; this.type = type;
                    }
                    public String getComponent() { return component; }
                    public String getPayee() { return payee; }
                    public BigDecimal getValue() { return value; }
                    public String getType() { return type; }
                    @Override public String toString() { return component + "[" + payee + "]=" + value; }
                }
                """));
        sb.append(file("eim/rules/Inputs.java", """
                package eim.rules;

                import java.math.BigDecimal;
                import java.util.Map;

                public class Inputs {
                    private final String payee;
                    private final Map<String, String> env;
                    public Inputs(String payee, Map<String, String> env) {
                        this.payee = payee; this.env = env;
                    }
                    public String getPayee() { return payee; }
                    public BigDecimal num(String k) {
                        String v = env.get(k);
                        return v == null ? null : Calc.v(v);
                    }
                    public String raw(String k) { return env.get(k); }
                    public BigDecimal fromOrZero(String k) {
                        BigDecimal v = num(k);
                        return v == null ? BigDecimal.ZERO : v;
                    }
                }
                """));
        sb.append(file("eim/rules/Flag.java", """
                package eim.rules;

                public class Flag {
                    private final String payee, kind;
                    private final boolean value;
                    public Flag(String payee, String kind, boolean value) {
                        this.payee = payee; this.kind = kind; this.value = value;
                    }
                    public String getPayee() { return payee; }
                    public String getKind() { return kind; }
                    public boolean isValue() { return value; }
                }
                """));
        sb.append(file("eim/rules/Dispute.java", """
                package eim.rules;

                public class Dispute {
                    private final String payee, symptom;
                    public Dispute(String payee, String symptom) {
                        this.payee = payee; this.symptom = symptom;
                    }
                    public String getPayee() { return payee; }
                    public String getSymptom() { return symptom; }
                }
                """));
        sb.append(file("eim/rules/Triage.java", """
                package eim.rules;

                public class Triage {
                    private final String payee, category;
                    public Triage(String payee, String category) {
                        this.payee = payee; this.category = category;
                    }
                    public String getPayee() { return payee; }
                    public String getCategory() { return category; }
                }
                """));

        StringBuilder tiers = new StringBuilder("//==FILE:eim/rules/Tiers.java\n"
                + "package eim.rules;\n\nimport java.math.BigDecimal;\n\n")
                .append("public final class Tiers {\n")
                .append("    private Tiers() { }\n");
        for (PlanModel.Component c : plan.components() == null ? List.<PlanModel.Component>of()
                : plan.components()) {
            if (c.kind() == PlanModel.Kind.TIER_TABLE && c.tiers() != null
                    && c.tiers().brackets() != null && !c.tiers().brackets().isEmpty()) {
                appendSliceMethod(tiers, c);
            }
        }
        tiers.append("}\n");
        sb.append(tiers);
        return sb.toString();
    }

    private static String file(String path, String body) {
        return "//==FILE:" + path + "\n" + body;
    }

    /**
     * Interval-intersection slicing (FR-502): attainment moves from
     * {@code from} to {@code to}; every tier's span is the intersection of
     * [from, to) with [tierFloor, tierCeiling). MARGINAL sums the slices;
     * CLIFF applies only the bracket containing {@code to}. Slices stay
     * unrounded - the caller rounds once at the payout point (NFR-02).
     */
    private static void appendSliceMethod(StringBuilder sb, PlanModel.Component c) {
        PlanModel.TierTable t = c.tiers();
        sb.append("    public static BigDecimal slice_").append(c.name())
                .append("(BigDecimal from, BigDecimal to) {\n");
        if (t.mode() == PlanModel.TierMode.CLIFF) {
            sb.append("        // CLIFF: the bracket containing `to` takes the whole span.\n");
            for (PlanModel.Bracket b : t.brackets()) {
                String rate = "Calc.v(\"" + b.rate() + "\")";
                if (b.upTo() != null) {
                    sb.append("        if (to.compareTo(Calc.v(\"").append(b.upTo())
                            .append("\")) <= 0) {\n")
                            .append("            return Calc.mul(to.subtract(from, Calc.MC)")
                            .append(".max(BigDecimal.ZERO), ").append(rate).append(");\n")
                            .append("        }\n");
                } else {
                    sb.append("        return Calc.mul(to.subtract(from, Calc.MC)")
                            .append(".max(BigDecimal.ZERO), ").append(rate).append(");\n");
                }
            }
            sb.append("        return BigDecimal.ZERO;\n    }\n");
            return;
        }
        sb.append("        BigDecimal payout = BigDecimal.ZERO;\n");
        String floor = "BigDecimal.ZERO";
        for (PlanModel.Bracket b : t.brackets()) {
            String rate = "Calc.v(\"" + b.rate() + "\")";
            String ceiling = b.upTo() == null ? "to" : "Calc.v(\"" + b.upTo() + "\")";
            sb.append("        {\n")
                    .append("            BigDecimal lo = ").append(floor).append(";\n")
                    .append("            BigDecimal hi = ").append(ceiling).append(";\n")
                    .append("            BigDecimal span = to.min(hi).subtract(from.max(lo), Calc.MC)")
                    .append(".max(BigDecimal.ZERO);\n")
                    .append("            payout = Calc.add(payout, Calc.mul(span, ").append(rate)
                    .append("));\n")
                    .append("        }\n");
            if (b.upTo() != null) {
                floor = "Calc.v(\"" + b.upTo() + "\")";
            }
        }
        sb.append("        return payout;\n    }\n");
    }

    private HelperGenerator() {
    }
}
