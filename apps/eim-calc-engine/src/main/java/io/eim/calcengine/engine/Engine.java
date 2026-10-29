package io.eim.calcengine.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic numeric core (FR-5xx): evaluates one payee's plan
 * components in dependency order over an ordered transaction stream.
 * Covers windows (FR-404), marginal/cliff tier slicing with interval
 * intersection (FR-502), draws with carry-forward deficit and recovery
 * (FR-504), caps with capped residual (FR-405.3), clawbacks with churn
 * window + active-days proration and origin linkage (FR-505), mid-period
 * proration (FR-506) and single-point rounding (NFR-01/02).
 */
public class Engine {

    /** One credited transaction routed into the engine (FR-501). */
    public record Txn(String transactionId, LocalDate date, String sourceTsUtc, BigDecimal amount) {
    }

    /** Everything the engine computes for one payee. */
    public record PayeeResult(Map<String, BigDecimal> components,
                              List<LedgerLine> lines, List<Map<String, Object>> trace) {
    }

    public record LedgerLine(String componentName, String entryType, BigDecimal amount,
                             BigDecimal creditedAmount, BigDecimal rateApplied,
                             String transactionId, String originPeriodId) {
    }

    private final EngineModel plan;
    private final RoundingMode roundingMode;

    public Engine(EngineModel plan, RoundingMode roundingMode) {
        this.plan = plan;
        this.roundingMode = roundingMode == null ? RoundingMode.HALF_EVEN : roundingMode;
    }

    /**
     * @param transactions credited transactions for the payee, ALREADY
     *                     deterministically ordered by the caller
     *                     (TransactionOrdering) — FR-501.
     * @param ctx          payee context: period_start, period_end,
     *                     active_days, period_days, churned_txn (id of a
     *                     churn-flagged transaction inside the clawback
     *                     window, if any), draw_balance (opening deficit).
     */
    public PayeeResult evaluate(String payeeId, List<Txn> transactions, Map<String, String> ctx) {
        Map<String, BigDecimal> comps = new LinkedHashMap<>();
        List<LedgerLine> lines = new ArrayList<>();
        List<Map<String, Object>> trace = new ArrayList<>();

        BigDecimal booked = transactions.stream()
                .map(Txn::amount).reduce(BigDecimal.ZERO, Precise::add);
        comps.put("booked_amount", booked);

        // Accumulation window state (FR-404): cumulative ordering exposed to
        // traces; window_from = cumulative before this period's first txn.
        BigDecimal windowFrom = Precise.v(or(ctx, "window_from", "0"));
        BigDecimal windowTo = Precise.add(windowFrom, booked);
        comps.put("window_from", windowFrom);
        comps.put("window_to", windowTo);

        for (EngineModel.Component c : ordered()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("component", c.name());
            step.put("kind", String.valueOf(c.kind()));
            try {
                switch (c.kind() == null ? EngineModel.Kind.FORMULA : c.kind()) {
                    case FORMULA, WINDOW -> {
                        // intermediate metrics feed components + traces;
                        // only payout-producing kinds write ledger lines
                        BigDecimal v = evalFormula(c, comps, ctx);
                        comps.put(c.name(), v);
                        step.put("value", v.toPlainString());
                    }
                    case TIER_TABLE -> {
                        List<Map<String, Object>> slices = new ArrayList<>();
                        BigDecimal payout = TierSlicer.slice(c.tiers(), windowFrom, windowTo, slices);
                        comps.put(c.name(), payout);
                        lines.add(new LedgerLine(c.name(), "CALCULATED",
                                Precise.roundPayout(payout, roundingMode), booked, null, null, null));
                        step.put("slices", slices);
                        step.put("value", payout.toPlainString());
                    }
                    case CAP -> {
                        Cap.Result r = Cap.apply(
                                val(c.protections(), "capValue"),
                                val(c.protections(), "capBasis"),
                                comps.get(dep(c)), booked);
                        comps.put(c.name(), r.capped());
                        if (r.capped() != null) {
                            lines.add(new LedgerLine(c.name(), "CALCULATED",
                                    Precise.roundPayout(r.capped(), roundingMode), null, null, null, null));
                        }
                        step.put("cappedResidual", r.residual() == null ? null : r.residual().toPlainString());
                        step.put("value", r.capped() == null ? null : r.capped().toPlainString());
                    }
                    case DRAW -> {
                        DrawState.Result r = DrawState.apply(
                                val(c.protections(), "drawKind"),
                                or(ctx, "draw_balance", "0"),
                                comps.get(dep(c)));
                        comps.put(c.name(), r.net());
                        if (r.advance().signum() != 0) {
                            lines.add(new LedgerLine(c.name(), "DRAW_ADVANCE",
                                    Precise.roundPayout(r.advance(), roundingMode), null, null, null, null));
                        }
                        if (r.recovery().signum() != 0) {
                            lines.add(new LedgerLine(c.name(), "DRAW_RECOVERY",
                                    Precise.roundPayout(r.recovery(), roundingMode), null, null, null, null));
                        }
                        step.put("carryForwardDeficit", r.deficit().toPlainString());
                        step.put("value", r.net() == null ? null : r.net().toPlainString());
                    }
                    case CLAWBACK -> {
                        Clawback.Result r = Clawback.apply(
                                val(c.protections(), "clawbackWindowDays"),
                                ctx.get("churned_txn"),
                                transactions,
                                or(ctx, "period_start", ""),
                                or(ctx, "period_end", ""),
                                or(ctx, "active_days", "0"),
                                or(ctx, "period_days", "0"),
                                or(ctx, "origin_period_id", ""));
                        comps.put(c.name(), r.amount());
                        if (r.amount().signum() != 0) {
                            lines.add(new LedgerLine(c.name(), "CLAWBACK",
                                    Precise.roundPayout(r.amount(), roundingMode), r.credited(),
                                    null, r.transactionId(), r.originPeriodId()));
                        }
                        step.put("prorationFactor", r.factor() == null ? null : r.factor().toPlainString());
                        step.put("value", r.amount().toPlainString());
                    }
                    case ROLLUP -> {
                        // resolved by the wavefront runner after reports
                        comps.put(c.name(), BigDecimal.ZERO);
                        step.put("deferred", true);
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                step.put("error", String.valueOf(e.getMessage()));
                throw e;
            }
            trace.add(step);
        }
        return new PayeeResult(comps, lines, trace);
    }

    // ---------------------------------------------------------------- helpers

    /** Dependency-ordered components (deterministic: deps first, then name). */
    List<EngineModel.Component> ordered() {
        List<EngineModel.Component> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        List<EngineModel.Component> remaining = new ArrayList<>(plan.components());
        int guard = 0;
        while (!remaining.isEmpty() && guard++ < 10_000) {
            boolean progressed = false;
            remaining.sort(java.util.Comparator.comparing(EngineModel.Component::name));
            for (var it = remaining.iterator(); it.hasNext();) {
                EngineModel.Component c = it.next();
                if (deps(c).stream().allMatch(done::contains)) {
                    out.add(c);
                    done.add(c.name());
                    it.remove();
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalArgumentException("plan dependency cycle at tollgate runtime");
            }
        }
        return out;
    }

    Set<String> deps(EngineModel.Component c) {
        Set<String> deps = new LinkedHashSet<>();
        if (c.dependsOn() != null) {
            deps.addAll(c.dependsOn());
        }
        if (c.formula() != null) {
            for (String token : c.formula().split("[^A-Za-z0-9_@]+")) {
                if (token.startsWith("@") && plan.component(token.substring(1)) != null) {
                    deps.add(token.substring(1));
                }
            }
        }
        deps.removeIf(d -> plan.component(d) == null);
        return deps;
    }

    String dep(EngineModel.Component c) {
        return deps(c).isEmpty() ? "booked_amount" : deps(c).iterator().next();
    }

    /** Tiny arithmetic evaluator over $facts/@components — DECIMAL128. */
    BigDecimal evalFormula(EngineModel.Component c, Map<String, BigDecimal> comps,
                           Map<String, String> ctx) {
        if (c.parameters() != null) {
            for (EngineModel.Parameter p : c.parameters()) {
                if (p.value() != null) {
                    comps.putIfAbsent(p.name(), Precise.v(p.value()));
                }
            }
        }
        String expr = c.formula() == null ? "$booked_amount" : c.formula();
        return new ExprParser(expr, comps, ctx, this).parse();
    }

    static String val(EngineModel.Protections p, String key) {
        return p == null ? null : switch (key) {
            case "drawKind" -> p.drawKind();
            case "capValue" -> p.capValue();
            case "capBasis" -> p.capBasis();
            case "clawbackWindowDays" -> p.clawbackWindowDays();
            default -> null;
        };
    }

    static String or(Map<String, String> ctx, String key, String dflt) {
        String v = ctx.get(key);
        return v == null || v.isBlank() ? dflt : v;
    }

    /** Recursive-descent arithmetic over $/@ tokens and numbers. */
    static final class ExprParser {
        private final String src;
        private final Map<String, BigDecimal> comps;
        private final Map<String, String> ctx;
        private final Engine engine;
        private int pos;

        ExprParser(String src, Map<String, BigDecimal> comps, Map<String, String> ctx, Engine e) {
            this.src = src;
            this.comps = comps;
            this.ctx = ctx;
            this.engine = e;
        }

        BigDecimal parse() {
            BigDecimal v = expr();
            if (pos < src.length()) {
                throw new IllegalArgumentException("trailing formula input at " + pos);
            }
            return v;
        }

        BigDecimal expr() {
            BigDecimal left = term();
            while (true) {
                skip();
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    char op = src.charAt(pos++);
                    BigDecimal right = term();
                    left = op == '+' ? Precise.add(left, right) : Precise.sub(left, right);
                } else {
                    return left;
                }
            }
        }

        BigDecimal term() {
            BigDecimal left = unary();
            while (true) {
                skip();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                    char op = src.charAt(pos++);
                    BigDecimal right = unary();
                    left = op == '*' ? Precise.mul(left, right) : Precise.div(left, right);
                } else {
                    return left;
                }
            }
        }

        BigDecimal unary() {
            skip();
            if (pos < src.length() && src.charAt(pos) == '-') {
                pos++;
                return Precise.sub(BigDecimal.ZERO, unary());
            }
            return primary();
        }

        BigDecimal primary() {
            skip();
            char ch = pos < src.length() ? src.charAt(pos) : ' ';
            if (ch == '(') {
                pos++;
                BigDecimal v = expr();
                skip();
                expect(')');
                return v;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = pos;
                while (pos < src.length()
                        && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                return Precise.v(src.substring(start, pos));
            }
            if (ch == '@' || ch == '$') {
                pos++;
                String name = ident();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("empty identifier in formula");
                }
                if (ch == '@') {
                    BigDecimal v = comps.get(name);
                    if (v == null) {
                        throw new IllegalArgumentException("unknown component @" + name);
                    }
                    return v;
                }
                BigDecimal cached = comps.get(name);
                if (cached != null) {
                    return cached;
                }
                String raw = ctx.get(name);
                if (raw == null) {
                    throw new IllegalArgumentException("unknown fact $" + name);
                }
                return Precise.v(raw);
            }
            if (Character.isLetter(ch)) {
                String fn = ident().toLowerCase(java.util.Locale.ROOT);
                skip();
                expect('(');
                BigDecimal a = expr();
                skip();
                expect(',');
                BigDecimal b = expr();
                skip();
                expect(')');
                return switch (fn) {
                    case "min" -> a.min(b);
                    case "max" -> a.max(b);
                    case "days_between" -> BigDecimal.valueOf(
                            java.time.temporal.ChronoUnit.DAYS.between(
                                    LocalDate.parse(ctx.get(a.toPlainString()) == null
                                            ? or(ctx, "period_start", "1970-01-01")
                                            : ctx.get(a.toPlainString())), LocalDate.parse(
                                            ctx.get(b.toPlainString()) == null
                                                    ? or(ctx, "period_end", "1970-01-01")
                                                    : ctx.get(b.toPlainString()))));
                    default -> throw new IllegalArgumentException("unknown function " + fn);
                };
            }
            throw new IllegalArgumentException("unexpected character in formula: " + ch);
        }

        private void expect(char c) {
            skip();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' in formula");
            }
            pos++;
        }

        private String ident() {
            skip();
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private void skip() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
