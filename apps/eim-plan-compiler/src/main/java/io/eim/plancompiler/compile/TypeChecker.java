package io.eim.plancompiler.compile;

import io.eim.plancompiler.model.PlanModel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Real-time plan type checker (FR-402.2): a small recursive-descent parser
 * that evaluates the STATIC type of every formula against the declared
 * output type, the parameter table and the standard fact context.
 * Errors are structured (component + reason) so the builder UI can
 * underline the offending token (FR-401).
 */
public class TypeChecker {

    /** Standard per-payee facts every formula may reference (arch §9.3). */
    static final Map<String, PlanModel.InputType> STANDARD_FACTS = Map.of(
            "booked_amount", PlanModel.InputType.CURRENCY,
            "window_from", PlanModel.InputType.CURRENCY,
            "window_to", PlanModel.InputType.CURRENCY,
            "transaction_count", PlanModel.InputType.DECIMAL,
            "attainment_quota", PlanModel.InputType.PERCENT,
            "active_days", PlanModel.InputType.DECIMAL,
            "period_days", PlanModel.InputType.DECIMAL,
            "period_start", PlanModel.InputType.DATE,
            "period_end", PlanModel.InputType.DATE);

    public record TypeError(String component, String token, String message) {
    }

    public record CheckResult(List<TypeError> errors, Map<String, PlanModel.OutputType> inferred) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public CheckResult check(PlanModel plan) {
        List<TypeError> errors = new ArrayList<>();
        Map<String, PlanModel.OutputType> inferred = new LinkedHashMap<>();
        if (plan.components() == null || plan.components().isEmpty()) {
            errors.add(new TypeError(null, null, "plan has no components"));
            return new CheckResult(errors, inferred);
        }
        Set<String> names = new java.util.HashSet<>();
        for (PlanModel.Component c : plan.components()) {
            if (c.name() == null || !c.name().matches("[a-z][a-z0-9_]{0,63}")) {
                errors.add(new TypeError(c.name(), c.name(),
                        "component name must be lower_snake_case (max 64 chars)"));
            } else if (!names.add(c.name())) {
                errors.add(new TypeError(c.name(), c.name(), "duplicate component name"));
            }
        }
        for (PlanModel.Component c : plan.components()) {
            checkComponent(plan, c, errors, inferred);
        }
        return new CheckResult(errors, inferred);
    }

    private void checkComponent(PlanModel plan, PlanModel.Component c,
                                List<TypeError> errors,
                                Map<String, PlanModel.OutputType> inferred) {
        String cn = c.name() == null ? "?" : c.name();
        if (c.kind() == null) {
            errors.add(new TypeError(cn, null, "kind is required"));
        }
        if (c.outputType() == null) {
            errors.add(new TypeError(cn, null, "outputType is required"));
        }
        // Parameters: typed, no clashes with standard facts, values parse.
        Map<String, PlanModel.InputType> env = new LinkedHashMap<>(STANDARD_FACTS);
        if (c.parameters() != null) {
            for (PlanModel.Parameter p : c.parameters()) {
                if (p.name() == null || !p.name().matches("[a-z][a-z0-9_]{0,63}")) {
                    errors.add(new TypeError(cn, p.name(), "parameter name must be lower_snake_case"));
                    continue;
                }
                if (env.containsKey(p.name()) && !STANDARD_FACTS.containsKey(p.name())) {
                    errors.add(new TypeError(cn, "$" + p.name(), "duplicate parameter"));
                }
                if (p.type() == null) {
                    errors.add(new TypeError(cn, "$" + p.name(), "parameter type is required"));
                    continue;
                }
                env.put(p.name(), p.type());
                if (p.value() != null && !parsesAs(p.type(), p.value())) {
                    errors.add(new TypeError(cn, "$" + p.name(),
                            "value '" + p.value() + "' is not a valid " + p.type()));
                }
            }
        }
        // Explicit dependsOn must exist (used by the dependency graph too).
        if (c.dependsOn() != null) {
            for (String dep : c.dependsOn()) {
                if (plan.component(dep) == null) {
                    errors.add(new TypeError(cn, "@" + dep, "unknown dependency"));
                }
            }
        }
        // Formula typing (FORMULA + WINDOW components must have one).
        if (c.formula() != null && !c.formula().isBlank()) {
            try {
                PlanModel.OutputType t = new Parser(c.formula(), plan, env, cn, errors).parse();
                inferred.put(cn, t);
                if (c.outputType() != null && t != c.outputType()) {
                    errors.add(new TypeError(cn, c.formula(),
                            "formula type " + t + " does not match declared output " + c.outputType()));
                }
            } catch (LexError e) {
                errors.add(new TypeError(cn, e.token, e.getMessage()));
            }
        } else if (c.kind() == PlanModel.Kind.FORMULA || c.kind() == PlanModel.Kind.WINDOW) {
            errors.add(new TypeError(cn, null, c.kind() + " component requires a formula"));
        }
        // Tier tables: structure + contiguity is checked by the DMN gate,
        // here we validate presence and bracket types.
        if (c.kind() == PlanModel.Kind.TIER_TABLE) {
            if (c.tiers() == null || c.tiers().brackets() == null || c.tiers().brackets().isEmpty()) {
                errors.add(new TypeError(cn, null, "TIER_TABLE component requires brackets"));
            } else {
                for (PlanModel.Bracket b : c.tiers().brackets()) {
                    if (b.rate() != null && !parsesAs(PlanModel.InputType.PERCENT, b.rate())) {
                        errors.add(new TypeError(cn, b.rate(),
                                "bracket rate must be a percent like 0.10"));
                    }
                    if (b.upTo() != null && !parsesAs(PlanModel.InputType.CURRENCY, b.upTo())) {
                        errors.add(new TypeError(cn, b.upTo(),
                                "bracket upTo must be a decimal amount"));
                    }
                }
            }
        }
        // Protections: enum-shaped strings validated, never trusted (FR-405).
        PlanModel.Protections pr = c.protections();
        if (pr != null) {
            if (pr.drawKind() != null && !Set.of("RECOVERABLE", "NON_RECOVERABLE").contains(pr.drawKind())) {
                errors.add(new TypeError(cn, pr.drawKind(), "drawKind must be RECOVERABLE|NON_RECOVERABLE"));
            }
            if (pr.capScope() != null && !Set.of("PERIOD", "ANNUAL").contains(pr.capScope())) {
                errors.add(new TypeError(cn, pr.capScope(), "capScope must be PERIOD|ANNUAL"));
            }
            if (pr.capBasis() != null && !Set.of("AMOUNT", "PERCENT").contains(pr.capBasis())) {
                errors.add(new TypeError(cn, pr.capBasis(), "capBasis must be AMOUNT|PERCENT"));
            }
            if (pr.capValue() != null && !parsesAs(PlanModel.InputType.DECIMAL, pr.capValue())) {
                errors.add(new TypeError(cn, pr.capValue(), "capValue must be a decimal"));
            }
            if (pr.clawbackWindowDays() != null
                    && !parsesAs(PlanModel.InputType.DECIMAL, pr.clawbackWindowDays())) {
                errors.add(new TypeError(cn, pr.clawbackWindowDays(),
                        "clawbackWindowDays must be a decimal"));
            }
        }
    }

    private static boolean parsesAs(PlanModel.InputType type, String value) {
        try {
            switch (type) {
                case CURRENCY, DECIMAL, PERCENT -> new BigDecimal(value);
                case BOOLEAN -> Boolean.parseBoolean(value);
                case DATE -> java.time.LocalDate.parse(value);
                default -> {
                    return false;
                }
            }
            return type != PlanModel.InputType.BOOLEAN || "true".equals(value) || "false".equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static final class LexError extends RuntimeException {
        final String token;

        LexError(String token, String message) {
            super(message);
            this.token = token;
        }
    }

    /** Pratt-style arithmetic parser computing the static result type. */
    static final class Parser {
        private final String src;
        private final PlanModel plan;
        private final Map<String, PlanModel.InputType> env;
        private final String component;
        private final List<TypeError> errors;
        private int pos;

        Parser(String src, PlanModel plan, Map<String, PlanModel.InputType> env,
               String component, List<TypeError> errors) {
            this.src = src;
            this.plan = plan;
            this.env = env;
            this.component = component;
            this.errors = errors;
        }

        PlanModel.OutputType parse() {
            PlanModel.OutputType t = comparison();
            skipWs();
            if (pos < src.length()) {
                throw new LexError(peekToken(), "unexpected trailing input");
            }
            return t;
        }

        private PlanModel.OutputType comparison() {
            PlanModel.OutputType left = additive();
            skipWs();
            while (pos < src.length() && "<>=".indexOf(src.charAt(pos)) >= 0) {
                String op = op();
                PlanModel.OutputType right = additive();
                if (!numeric(left) || !numeric(right)) {
                    errors.add(new TypeError(component, op,
                            "comparison requires numeric operands"));
                }
                left = PlanModel.OutputType.BOOLEAN;
            }
            return left;
        }

        private PlanModel.OutputType additive() {
            PlanModel.OutputType left = multiplicative();
            skipWs();
            while (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                String op = op();
                PlanModel.OutputType right = multiplicative();
                left = arith(op, left, right);
            }
            return left;
        }

        private PlanModel.OutputType multiplicative() {
            PlanModel.OutputType left = unary();
            skipWs();
            while (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                String op = op();
                PlanModel.OutputType right = unary();
                left = arith(op, left, right);
            }
            return left;
        }

        private PlanModel.OutputType unary() {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '-') {
                pos++;
                return unary();
            }
            return primary();
        }

        private PlanModel.OutputType primary() {
            skipWs();
            if (pos >= src.length()) {
                throw new LexError("", "unexpected end of formula");
            }
            char ch = src.charAt(pos);
            if (ch == '(') {
                pos++;
                PlanModel.OutputType t = comparison();
                skipWs();
                expect(')');
                return t;
            }
            if (Character.isDigit(ch) || ch == '.') {
                String num = number();
                return PlanModel.OutputType.DECIMAL;
            }
            if (ch == '@' || ch == '$') {
                char kind = ch;
                pos++;
                String name = ident();
                if (name.isEmpty()) {
                    throw new LexError(String.valueOf(kind), "expected identifier after " + kind);
                }
                String token = kind + name;
                if (kind == '@') {
                    PlanModel.Component ref = plan.component(name);
                    if (ref == null) {
                        errors.add(new TypeError(component, token, "unknown component reference"));
                        return PlanModel.OutputType.DECIMAL;
                    }
                    return ref.outputType() == null ? PlanModel.OutputType.DECIMAL
                            : ref.outputType();
                }
                PlanModel.InputType it = env.get(name);
                if (it == null) {
                    errors.add(new TypeError(component, token,
                            "unknown parameter or fact '" + name + "'"));
                    return PlanModel.OutputType.DECIMAL;
                }
                return toOutput(it);
            }
            if (Character.isLetter(ch)) {
                String fn = ident().toLowerCase(Locale.ROOT);
                skipWs();
                expect('(');
                PlanModel.OutputType a = comparison();
                skipWs();
                expect(',');
                PlanModel.OutputType b = comparison();
                skipWs();
                expect(')');
                return switch (fn) {
                    case "min", "max" -> {
                        if (!numeric(a) || !numeric(b)) {
                            errors.add(new TypeError(component, fn, fn + " requires numeric operands"));
                        }
                        yield wider(a, b);
                    }
                    case "days_between" -> {
                        if (a != PlanModel.OutputType.DATE || b != PlanModel.OutputType.DATE) {
                            errors.add(new TypeError(component, fn,
                                    "days_between requires DATE operands"));
                        }
                        yield PlanModel.OutputType.DECIMAL;
                    }
                    default -> throw new LexError(fn, "unknown function '" + fn + "'");
                };
            }
            throw new LexError(peekToken(), "unexpected character");
        }

        private PlanModel.OutputType arith(String op, PlanModel.OutputType l, PlanModel.OutputType r) {
            if (!numeric(l) || !numeric(r)) {
                errors.add(new TypeError(component, op,
                        "operator '" + op + "' cannot apply " + l + " to " + r));
                return PlanModel.OutputType.DECIMAL;
            }
            if (op.equals("+") || op.equals("-")) {
                if (l != r) {
                    errors.add(new TypeError(component, op,
                            "'" + op + "' requires matching types, got " + l + " and " + r));
                }
                return l;
            }
            if (op.equals("*")) {
                if (l == PlanModel.OutputType.CURRENCY && r == PlanModel.OutputType.CURRENCY) {
                    errors.add(new TypeError(component, op, "currency * currency is invalid"));
                }
                return l == PlanModel.OutputType.CURRENCY || r == PlanModel.OutputType.CURRENCY
                        ? PlanModel.OutputType.CURRENCY
                        : (l == PlanModel.OutputType.PERCENT || r == PlanModel.OutputType.PERCENT
                        ? PlanModel.OutputType.PERCENT : PlanModel.OutputType.DECIMAL);
            }
            // division
            if (l == PlanModel.OutputType.CURRENCY && r == PlanModel.OutputType.CURRENCY) {
                return PlanModel.OutputType.DECIMAL;
            }
            if (l == PlanModel.OutputType.CURRENCY || r == PlanModel.OutputType.CURRENCY) {
                return PlanModel.OutputType.CURRENCY;
            }
            return PlanModel.OutputType.DECIMAL;
        }

        private static boolean numeric(PlanModel.OutputType t) {
            return t == PlanModel.OutputType.CURRENCY || t == PlanModel.OutputType.PERCENT
                    || t == PlanModel.OutputType.DECIMAL;
        }

        private static PlanModel.OutputType wider(PlanModel.OutputType a, PlanModel.OutputType b) {
            return a == PlanModel.OutputType.CURRENCY || b == PlanModel.OutputType.CURRENCY
                    ? PlanModel.OutputType.CURRENCY : PlanModel.OutputType.DECIMAL;
        }

        private static PlanModel.OutputType toOutput(PlanModel.InputType t) {
            return PlanModel.OutputType.valueOf(t.name());
        }

        private void expect(char c) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new LexError(peekToken(), "expected '" + c + "'");
            }
            pos++;
        }

        private String op() {
            skipWs();
            int start = pos;
            while (pos < src.length() && "<>=".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            if (pos == start && (src.charAt(pos) == '+' || src.charAt(pos) == '-'
                    || src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private String number() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private String ident() {
            skipWs();
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private String peekToken() {
            return pos >= src.length() ? "<eof>" : src.substring(pos, Math.min(src.length(), pos + 8));
        }
    }
}
