package io.eim.plancompiler.compile;

import io.eim.plancompiler.model.PlanModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plan compiler (arch §5.1/5.2): type-checks the plan, rejects dependency
 * cycles (Tarjan), verifies tier tables, then emits the immutable artifact
 * set — forward-chaining DRL with deterministic salience, backward-chaining
 * triage queries, the DECIMAL128 arithmetic helper and DMN tier decisions.
 */
@ApplicationScoped
public class PlanCompiler {

    private final TypeChecker typeChecker = new TypeChecker();
    private final DependencyGraph graph = new DependencyGraph();

    public CompileResult compile(PlanModel plan) {
        TypeChecker.CheckResult types = typeChecker.check(plan);
        DependencyGraph.GraphResult g = graph.analyze(plan);

        List<String> tierIssues = new ArrayList<>();
        for (PlanModel.Component c : plan.components() == null ? List.<PlanModel.Component>of()
                : plan.components()) {
            if (c.kind() == PlanModel.Kind.TIER_TABLE && c.tiers() != null) {
                tierIssues.addAll(TierTableChecks.verify(c.name(), c.tiers()));
            }
        }

        // Generation proceeds even with diagnostics so the UI can preview
        // partial artifacts; the publish gate blocks on !ok().
        String drl = DrlGenerator.generate(plan, g.topological());
        String helper = HelperGenerator.generate(plan);
        String dmn = DmnGenerator.generate(plan);
        return new CompileResult(types.errors(), g.cycles(), tierIssues,
                g.topological(), types.inferred(), drl, helper, dmn);
    }

    /** Tier completeness/consistency verification (NFR-104). */
    static final class TierTableChecks {
        static List<String> verify(String component, PlanModel.TierTable t) {
            List<String> issues = new ArrayList<>();
            List<PlanModel.Bracket> bs = t.brackets();
            if (t.mode() == null) {
                issues.add(component + ": tier mode must be MARGINAL or CLIFF");
            }
            if (t.basis() == null) {
                issues.add(component + ": tier basis must be AMOUNT or PERCENT");
            }
            long catchAll = bs.stream().filter(b -> b.upTo() == null).count();
            if (catchAll != 1) {
                issues.add(component + ": exactly one catch-all bracket required, found " + catchAll);
            }
            if (bs.size() >= 2 && bs.get(bs.size() - 1).upTo() != null) {
                issues.add(component + ": catch-all bracket must be last");
            }
            java.math.BigDecimal prev = null;
            for (int i = 0; i < bs.size(); i++) {
                PlanModel.Bracket b = bs.get(i);
                if (b.rate() == null) {
                    issues.add(component + ": bracket " + i + " missing rate");
                    continue;
                }
                if (b.upTo() == null) {
                    break;
                }
                java.math.BigDecimal up;
                try {
                    up = new java.math.BigDecimal(b.upTo());
                } catch (NumberFormatException e) {
                    issues.add(component + ": bracket " + i + " upTo is not a decimal");
                    break;
                }
                if (up.signum() <= 0) {
                    issues.add(component + ": bracket " + i + " upTo must be positive");
                }
                if (prev != null) {
                    // Contiguity: no gaps and no overlaps between brackets.
                    if (up.compareTo(prev) <= 0) {
                        issues.add(component + ": bracket " + i + " upTo " + up
                                + " must exceed previous " + prev + " (overlap)");
                    }
                } else if (i == 0 && up.signum() <= 0) {
                    issues.add(component + ": first bracket upTo must be positive");
                }
                prev = up;
            }
            return issues;
        }
    }
}
