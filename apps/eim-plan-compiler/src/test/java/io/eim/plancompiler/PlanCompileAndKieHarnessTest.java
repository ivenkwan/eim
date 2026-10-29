package io.eim.plancompiler;

import io.eim.plancompiler.compile.CompileResult;
import io.eim.plancompiler.compile.PlanCompiler;
import io.eim.plancompiler.model.PlanModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.core.base.RuleNameStartsWithAgendaFilter;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG3-RUL-1..7 harness: the canonical AE plan compiles clean, the generated
 * DRL + helper build on a REAL Drools engine, the canonical marginal case
 * (attainment $90k -> $160k across 10/15/20% tiers = $10,500.00, FR-502.1)
 * comes out of rule firing, backward-chaining queries triage disputes, and
 * the generated DMN returns bracket rates.
 */
@QuarkusTest
class PlanCompileAndKieHarnessTest {

    @Inject
    PlanCompiler compiler;

    /** FR-502.1 canonical AE plan (mirrors packages/shared-domain goldens). */
    static PlanModel canonicalPlan() {
        PlanModel.Protections cap = new PlanModel.Protections(
                null, "PERIOD", "AMOUNT", "25000", null);
        return new PlanModel("Enterprise AE 2026", List.of(
                new PlanModel.Component("attainment", PlanModel.Kind.FORMULA,
                        PlanModel.OutputType.CURRENCY, "$window_to",
                        List.of(), null, null, List.of()),
                new PlanModel.Component("commission", PlanModel.Kind.TIER_TABLE,
                        PlanModel.OutputType.CURRENCY, null,
                        List.of(),
                        new PlanModel.TierTable(PlanModel.TierMode.MARGINAL,
                                PlanModel.TierBasis.AMOUNT,
                                List.of(new PlanModel.Bracket("100000", "0.10"),
                                        new PlanModel.Bracket("150000", "0.15"),
                                        new PlanModel.Bracket(null, "0.20"))),
                        cap, List.of("attainment"))));
    }

    @Test
    void typeCheckerCatchesMismatchedTypesAndUnknownTokens() {
        PlanModel bad = new PlanModel("bad", List.of(
                new PlanModel.Component("c1", PlanModel.Kind.FORMULA,
                        PlanModel.OutputType.CURRENCY, "$booked_amount + $period_start",
                        List.of(), null, null, List.of()),
                new PlanModel.Component("c2", PlanModel.Kind.FORMULA,
                        PlanModel.OutputType.CURRENCY, "@missing_ref * $nope",
                        List.of(), null, null, List.of())));
        CompileResult r = compiler.compile(bad);
        assertTrue(!r.ok(), "compile must fail");
        assertTrue(r.allProblems().stream().anyMatch(
                        p -> p.contains("cannot apply") || p.contains("matching types")),
                "currency + date mismatch reported: " + r.allProblems());
        assertTrue(r.allProblems().stream().anyMatch(p -> p.contains("unknown component reference")));
        assertTrue(r.allProblems().stream().anyMatch(p -> p.contains("unknown parameter or fact")));
    }

    @Test
    void tarjanRejectsDependencyCycleAtPublish() {
        PlanModel cyclic = new PlanModel("cyclic", List.of(
                new PlanModel.Component("a", PlanModel.Kind.FORMULA,
                        PlanModel.OutputType.DECIMAL, "@b + 1", List.of(), null, null, List.of()),
                new PlanModel.Component("b", PlanModel.Kind.FORMULA,
                        PlanModel.OutputType.DECIMAL, "@a + 1", List.of(), null, null, List.of())));
        CompileResult r = compiler.compile(cyclic);
        assertTrue(!r.ok() && !r.cycles().isEmpty(), "cycle detected: " + r.allProblems());
    }

    @Test
    void tierCompletenessRejectsGapsAndOverlaps() {
        PlanModel overlap = new PlanModel("overlap", List.of(
                new PlanModel.Component("t", PlanModel.Kind.TIER_TABLE,
                        PlanModel.OutputType.CURRENCY, null, List.of(),
                        new PlanModel.TierTable(PlanModel.TierMode.CLIFF,
                                PlanModel.TierBasis.AMOUNT,
                                List.of(new PlanModel.Bracket("50000", "0.05"),
                                        new PlanModel.Bracket("40000", "0.04"))),
                        null, List.of())));
        CompileResult r = compiler.compile(overlap);
        assertTrue(r.allProblems().stream().anyMatch(p -> p.contains("catch-all")),
                "missing catch-all reported: " + r.allProblems());
        assertTrue(r.allProblems().stream().anyMatch(p -> p.contains("overlap")),
                "bracket overlap reported: " + r.allProblems());
    }

    @Test
    void kieEngineRunsGeneratedDrlOnCanonicalCase() throws Exception {
        CompileResult compiled = compiler.compile(canonicalPlan());
        assertTrue(compiled.ok(), "canonical plan compiles: " + compiled.allProblems());

        KieServices ks = KieServices.Factory.get();
        org.kie.api.builder.KieFileSystem kfs = ks.newKieFileSystem();
        kfs.generateAndWritePomXML(ks.newReleaseId("io.eim", "gen-artifacts", "1.0.0"));
        kfs.write("src/main/resources/eim/rules/gen.drl", compiled.drl());
        writeHelperBundle(kfs, compiled.helperJava());
        org.kie.api.builder.KieBuilder builder = ks.newKieBuilder(kfs);
        builder.buildAll();
        Results results = builder.getResults();
        assertTrue(results.getMessages(Message.Level.ERROR).isEmpty(),
                () -> "generated artifacts must build on Drools: "
                        + results.getMessages(Message.Level.ERROR));

        KieContainer kc = ks.newKieContainer(builder.getKieModule().getReleaseId());
        KieSession session = kc.newKieSession();
        try {
            Object inputs = kc.getClassLoader()
                    .loadClass("eim.rules.Inputs")
                    .getConstructor(String.class, Map.class)
                    .newInstance("sarah", Map.of(
                            "window_from", "90000",
                            "window_to", "160000",
                            "period_start", "2026-04-01",
                            "period_end", "2026-04-30"));
            session.insert(inputs);
            session.fireAllRules();

            // Canonical marginal case (FR-502.1): 10k@10% + 50k@15% + 10k@20%
            // = 1000 + 7500 + 2000 = 10,500 (unrounded until payout, NFR-02).
            BigDecimal commission = session.getObjects(obj ->
                            "eim.rules.Fact".equals(obj.getClass().getName())
                                    && "commission".equals(componentOf(obj))).stream()
                    .map(PlanCompileAndKieHarnessTest::valueOf)
                    .findFirst().orElse(null);
            assertEquals(0, commission.compareTo(new BigDecimal("10500")),
                    "canonical 3-slice case from rule firing, got " + commission);

            // Backward-chaining triage (FR-704.1)
            Class<?> dispute = kc.getClassLoader().loadClass("eim.rules.Dispute");
            session.insert(dispute.getConstructor(String.class, String.class)
                    .newInstance("p1", "late_arrival"));
            session.insert(dispute.getConstructor(String.class, String.class)
                    .newInstance("p2", "weird_payout"));
            session.fireAllRules(new RuleNameStartsWithAgendaFilter("triage_"));
            assertEquals(1, session.getQueryResults("triageCategory", "p1", "SOURCE_DATA").size(),
                    "p1 triaged SOURCE_DATA");
            assertEquals(1, session.getQueryResults("triageCategory", "p2", "PLAN_LOGIC").size(),
                    "p2 triaged PLAN_LOGIC (default)");
        } finally {
            session.dispose();
        }
    }

    @Test
    void kieDmnRuntimeEvaluatesGeneratedTierDecisions() {
        CompileResult compiled = compiler.compile(canonicalPlan());
        KieServices ks = KieServices.Factory.get();
        org.kie.api.builder.KieFileSystem kfs = ks.newKieFileSystem();
        kfs.generateAndWritePomXML(ks.newReleaseId("io.eim", "gen-dmn", "1.0.0"));
        kfs.write("src/main/resources/eim/rules/tiers.dmn", compiled.dmn());
        org.kie.api.builder.KieBuilder builder = ks.newKieBuilder(kfs);
        builder.buildAll();
        assertTrue(builder.getResults().getMessages(Message.Level.ERROR).isEmpty(),
                () -> "generated DMN must parse: "
                        + builder.getResults().getMessages(Message.Level.ERROR));
        KieContainer kc = ks.newKieContainer(builder.getKieModule().getReleaseId());
        KieSession session = kc.newKieSession();
        try {
            DMNRuntime dmn = session.getKieRuntime(DMNRuntime.class);
            org.kie.dmn.api.core.DMNModel model = dmn.getModel(
                    "https://eim.io/plan/tiers", "eim-plan-tiers");
            assertEquals(0, evaluate(dmn, model, "60000").compareTo(new BigDecimal("0.10")));
            assertEquals(0, evaluate(dmn, model, "100000").compareTo(new BigDecimal("0.10")));
            assertEquals(0, evaluate(dmn, model, "100001").compareTo(new BigDecimal("0.15")));
            assertEquals(0, evaluate(dmn, model, "160000").compareTo(new BigDecimal("0.20")));
        } finally {
            session.dispose();
        }
    }

    /** The helper bundle carries one //==FILE: section per class. */
    private static void writeHelperBundle(org.kie.api.builder.KieFileSystem kfs, String bundle) {
        String path = null;
        StringBuilder body = new StringBuilder();
        for (String line : bundle.split("\\n", -1)) {
            if (line.startsWith("//==FILE:")) {
                if (path != null) {
                    kfs.write("src/main/java/" + path, body.toString());
                }
                path = line.substring("//==FILE:".length()).trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        if (path != null) {
            kfs.write("src/main/java/" + path, body.toString());
        }
    }

    private static BigDecimal evaluate(DMNRuntime dmn, org.kie.dmn.api.core.DMNModel model,
                                       String amount) {
        DMNContext ctx = dmn.newContext();
        ctx.set("amount", new BigDecimal(amount));
        DMNResult result = dmn.evaluateAll(model, ctx);
        assertTrue(!result.hasErrors(), () -> "DMN errors: " + result.getMessages());
        Object out = result.getDecisionResultByName("commission_rate").getResult();
        return out instanceof BigDecimal bd ? bd
                : BigDecimal.valueOf(Double.parseDouble(String.valueOf(out)));
    }

    private static String componentOf(Object fact) {
        try {
            return (String) fact.getClass().getMethod("getComponent").invoke(fact);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal valueOf(Object fact) {
        try {
            return (BigDecimal) fact.getClass().getMethod("getValue").invoke(fact);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
