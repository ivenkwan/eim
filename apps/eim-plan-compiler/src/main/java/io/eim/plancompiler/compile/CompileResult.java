package io.eim.plancompiler.compile;

import io.eim.plancompiler.model.PlanModel;

import java.util.List;
import java.util.Map;

/** Full compile outcome: diagnostics plus the generated artifacts. */
public record CompileResult(List<TypeChecker.TypeError> typeErrors,
                            List<List<String>> cycles,
                            List<String> tierIssues,
                            List<String> topological,
                            Map<String, PlanModel.OutputType> inferredTypes,
                            String drl, String helperJava, String dmn) {

    public boolean ok() {
        return typeErrors.isEmpty() && cycles.isEmpty() && tierIssues.isEmpty();
    }

    public List<String> allProblems() {
        List<String> all = new java.util.ArrayList<>();
        typeErrors.forEach(e -> all.add("type: " + (e.component() == null ? "plan" : e.component())
                + ": " + e.message() + (e.token() == null ? "" : " (at '" + e.token() + "')")));
        cycles.forEach(c -> all.add("cycle: " + String.join(" -> ", c) + " -> " + c.get(0)));
        all.addAll(tierIssues);
        return all;
    }
}
