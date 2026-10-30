package io.eim.coreapi.accounting;

import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.KieServices;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASC 606 classification as DMN decision tables per book (TG7-ACC-2,
 * FR-901.2): the default matrix — practical expedient, clawback offsets,
 * SE-spike formulaic rule, manager-override toggle, renewal policy, fringe
 * components — is GENERATED from the book policy so Controllers edit policy,
 * not code; a Controller may upload a bespoke decision table instead, which
 * the resource layer versions and audits. Evaluation runs on the KIE DMN
 * runtime (same dialect as the plan-compiler artifacts); hit policy FIRST
 * — matrix rows are ordered overrides. Compiled models are cached per DMN
 * version so a period classification pass does not recompile per entry.
 */
@ApplicationScoped
public class DmnClassifier {

    public record Decision(String classification, String reasonCode, Integer months) {
    }

    private static final ConcurrentHashMap<String, DMNModel> MODEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, KieContainer> CONTAINERS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong GAV = new java.util.concurrent.atomic.AtomicLong();

    /** Render the default classification decision table from a book policy. */
    public static String defaultDmn(boolean capitalizeManagerOverrides, String seSpiffPolicy,
                                    String renewalPolicy, int expedientThresholdMonths) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!-- Default ASC 606 classification matrix generated from book policy — ")
                .append("Controller-editable; every change is versioned and audited (FR-901.2/FR-613). -->\n")
                .append("<definitions xmlns=\"http://www.omg.org/spec/DMN/20180521/MODEL/\" ")
                .append("id=\"eim-asc606-classification\" name=\"eim-asc606-classification\" ")
                .append("namespace=\"https://eim.io/accounting/classification\">\n")
                .append("  <inputData id=\"in_months\" name=\"amortization_months_hint\">")
                .append("<variable id=\"in_months_var\" name=\"amortization_months_hint\" typeRef=\"number\"/></inputData>\n")
                .append("  <inputData id=\"in_entry\" name=\"entry_type\">")
                .append("<variable id=\"in_entry_var\" name=\"entry_type\" typeRef=\"string\"/></inputData>\n")
                .append("  <inputData id=\"in_component\" name=\"component_family\">")
                .append("<variable id=\"in_component_var\" name=\"component_family\" typeRef=\"string\"/></inputData>\n")
                .append("  <inputData id=\"in_role\" name=\"payee_role_family\">")
                .append("<variable id=\"in_role_var\" name=\"payee_role_family\" typeRef=\"string\"/></inputData>\n")
                .append("  <decision id=\"classify\" name=\"classify\">\n")
                .append("    <variable id=\"classify_var\" name=\"classify\"/>\n")
                .append("    <informationRequirement id=\"ir1\"><requiredInput href=\"#in_months\"/></informationRequirement>\n")
                .append("    <informationRequirement id=\"ir2\"><requiredInput href=\"#in_entry\"/></informationRequirement>\n")
                .append("    <informationRequirement id=\"ir3\"><requiredInput href=\"#in_component\"/></informationRequirement>\n")
                .append("    <informationRequirement id=\"ir4\"><requiredInput href=\"#in_role\"/></informationRequirement>\n")
                .append("    <decisionTable id=\"matrix\" hitPolicy=\"FIRST\" ")
                .append("preferredOrientation=\"Rule-as-Row\">\n");
        x.append("        <input id=\"i_amort_months\" name=\"amortization_months_hint\">\n")
                .append("          <inputExpression id=\"ie_amort_months\" typeRef=\"number\">")
                .append("<text>amortization_months_hint</text></inputExpression>\n")
                .append("        </input>\n")
                .append("        <input id=\"i_entry_type\" name=\"entry_type\">\n")
                .append("          <inputExpression id=\"ie_entry_type\" typeRef=\"string\">")
                .append("<text>entry_type</text></inputExpression>\n")
                .append("        </input>\n")
                .append("        <input id=\"i_component\" name=\"component_family\">\n")
                .append("          <inputExpression id=\"ie_component\" typeRef=\"string\">")
                .append("<text>component_family</text></inputExpression>\n")
                .append("        </input>\n")
                .append("        <input id=\"i_payee_role\" name=\"payee_role_family\">\n")
                .append("          <inputExpression id=\"ie_payee_role\" typeRef=\"string\">")
                .append("<text>payee_role_family</text></inputExpression>\n")
                .append("        </input>\n")
                .append("        <output id=\"o_classification\" name=\"classification\" typeRef=\"string\"/>\n")
                .append("        <output id=\"o_reason\" name=\"reason_code\" typeRef=\"string\"/>\n")
                .append("        <output id=\"o_months\" name=\"amortization_months\" typeRef=\"number\"/>\n");
        int r = 0;
        // P1: practical expedient — amortization term at/below the threshold is
        // expensed immediately (ASC 340-40-25-4).
        row(x, r++, "&lt;= " + expedientThresholdMonths, "", "", "",
                "EXPENSE", "PRACTICAL_EXPEDIENT", "amortization_months_hint");
        // P2: clawback offsets are never capitalized.
        row(x, r++, "", "\"CLAWBACK\"", "", "",
                "EXPENSE", "CLAWBACK_OFFSET", "12");
        // P3: SE-spike policy — strictly formulaic only (a manual spiff is
        // expensed), or expensed/always per book policy.
        if ("FORMULAIC_ONLY".equals(seSpiffPolicy)) {
            row(x, r++, "", "\"MANUAL_OVERRIDE\"", "\"SE_SPIFF\"", "",
                    "EXPENSE", "SE_SPIFF_NON_FORMULAIC", "12");
        } else if ("NEVER".equals(seSpiffPolicy)) {
            row(x, r++, "", "", "\"SE_SPIFF\"", "",
                    "EXPENSE", "SE_SPIFF_EXPENSED_BY_POLICY", "12");
        }
        // P4: manager/director overrides — toggleable capitalization.
        row(x, r++, "", "", "", "\"MANAGER\"",
                capitalizeManagerOverrides ? "CAPITALIZE" : "EXPENSE",
                capitalizeManagerOverrides ? "MANAGER_OVERRIDE" : "MANAGER_OVERRIDE_EXPENSED",
                "amortization_months_hint");
        // P5: renewals follow the per-book renewal policy.
        if ("EXPENSE".equals(renewalPolicy)) {
            row(x, r++, "", "", "\"RENEWAL\"", "",
                    "EXPENSE", "RENEWAL_EXPENSED_BY_POLICY", "12");
        } else {
            row(x, r++, "", "", "\"RENEWAL\"", "",
                    "CAPITALIZE", "RENEWAL_NON_COMMENSURATE", "amortization_months_hint");
        }
        // P6: fringe / payroll-tax components capitalize (the fringe rate is
        // applied by the caller; the table only decides the treatment).
        row(x, r++, "", "", "\"FRINGE_PAYROLL\"", "",
                "CAPITALIZE", "FRINGE_RATE", "amortization_months_hint");
        // P7: default — direct AE commissions capitalize over the hint term.
        row(x, r++, "", "", "", "",
                "CAPITALIZE", "NEW_LOGO_COMMISSION", "amortization_months_hint");
        x.append("    </decisionTable>\n")
                .append("  </decision>\n")
                .append("</definitions>\n");
        return x.toString();
    }

    private static void row(StringBuilder x, int i, String monthsCell, String entryCell,
                            String componentCell, String roleCell,
                            String classification, String reason, String monthsOut) {
        x.append("      <rule id=\"matrix_r").append(i).append("\">\n");
        cell(x, i, 1, monthsCell);
        cell(x, i, 2, entryCell);
        cell(x, i, 3, componentCell);
        cell(x, i, 4, roleCell);
        x.append("        <outputEntry id=\"matrix_r").append(i).append("o1\">")
                .append("<text>\"").append(classification).append("\"</text></outputEntry>\n")
                .append("        <outputEntry id=\"matrix_r").append(i).append("o2\">")
                .append("<text>\"").append(reason).append("\"</text></outputEntry>\n")
                .append("        <outputEntry id=\"matrix_r").append(i).append("o3\">")
                .append("<text>").append(monthsOut).append("</text></outputEntry>\n")
                .append("      </rule>\n");
    }

    private static void cell(StringBuilder x, int r, int c, String text) {
        x.append("        <inputEntry id=\"matrix_r").append(r).append("i").append(c).append("\">")
                .append(text == null || text.isBlank() ? "<text>-</text>" : "<text>" + text + "</text>")
                .append("</inputEntry>\n");
    }

    /**
     * Input normalization for the decision tables: kie-dmn unary tests
     * reliably support equality and interval cells, so the raw component
     * name / role title are mapped to a family vocabulary in Java and the
     * classification logic itself stays entirely in the versioned,
     * Controller-editable decision table (FR-901.2).
     */
    static String componentFamily(String componentName) {
        String n = componentName == null ? "" : componentName.toLowerCase();
        if (n.contains("renewal")) {
            return "RENEWAL";
        }
        if (n.contains("spiff") || n.contains("bonus")) {
            return "SE_SPIFF";
        }
        if (n.contains("fringe") || n.contains("payroll tax") || n.contains("payroll_tax")) {
            return "FRINGE_PAYROLL";
        }
        return "NEW_LOGO_COMMISSION";
    }

    static String roleFamily(String payeeRole) {
        String r = payeeRole == null ? "" : payeeRole.toLowerCase();
        return (r.contains("mgr") || r.contains("manager") || r.contains("director"))
                ? "MANAGER" : "INDIVIDUAL_CONTRIBUTOR";
    }

    /** Evaluate the book's decision table for one ledger entry. */
    public Decision classify(String dmnXml, String componentName, String entryType,
                             String payeeRole, int monthsHint) {
        DMNModel model = MODEL_CACHE.computeIfAbsent(Integer.toHexString(dmnXml.hashCode()) + dmnXml.length(),
                k -> compile(dmnXml));
        DMNRuntime runtime = runtimeFor(dmnXml);
        DMNContext ctx = runtime.newContext();
        ctx.set("component_family", componentFamily(componentName));
        ctx.set("entry_type", entryType);
        ctx.set("payee_role_family", roleFamily(payeeRole));
        ctx.set("amortization_months_hint", BigDecimal.valueOf(monthsHint));
        DMNResult result = runtime.evaluateAll(model, ctx);
        if (result.hasErrors()) {
            throw new IllegalStateException("classification DMN evaluation failed: "
                    + result.getMessages());
        }
        Object out = result.getDecisionResultByName("classify").getResult();
        if (!(out instanceof java.util.Map<?, ?> m)) {
            throw new IllegalStateException("classification DMN returned no row for "
                    + componentName + "/" + entryType);
        }
        Number mn = (Number) m.get("amortization_months");
        return new Decision(String.valueOf(m.get("classification")),
                String.valueOf(m.get("reason_code")),
                mn == null ? null : mn.intValue());
    }

    private static DMNModel compile(String dmnXml) {
        return runtimeFor(dmnXml).getModel(
                "https://eim.io/accounting/classification", "eim-asc606-classification");
    }

    private static DMNRuntime runtimeFor(String dmnXml) {
        String key = Integer.toUnsignedString(dmnXml.hashCode()) + ":" + dmnXml.length();
        KieContainer kc = CONTAINERS.computeIfAbsent(key, k -> {
            KieServices ks = KieServices.Factory.get();
            org.kie.api.builder.KieFileSystem kfs = ks.newKieFileSystem();
            ReleaseId rid = ks.newReleaseId("io.eim.accounting", "classification",
                    "1.0." + GAV.incrementAndGet());
            kfs.generateAndWritePomXML(rid);
            kfs.write("src/main/resources/accounting/classification.dmn", dmnXml);
            org.kie.api.builder.KieBuilder builder = ks.newKieBuilder(kfs);
            builder.buildAll();
            if (!builder.getResults().getMessages(Message.Level.ERROR).isEmpty()) {
                throw new IllegalStateException("classification DMN failed to parse: "
                        + builder.getResults().getMessages(Message.Level.ERROR));
            }
            return ks.newKieContainer(rid);
        });
        KieSession session = kc.newKieSession();
        try {
            return session.getKieRuntime(DMNRuntime.class);
        } finally {
            session.dispose();
        }
    }

    /** Test hook: validate a candidate decision table before it is stored. */
    public List<String> validate(String dmnXml) {
        try {
            DMNModel model = compile(dmnXml);
            return model == null ? List.of("model resolved to null") : List.of();
        } catch (RuntimeException e) {
            return List.of(e.getMessage());
        }
    }
}
