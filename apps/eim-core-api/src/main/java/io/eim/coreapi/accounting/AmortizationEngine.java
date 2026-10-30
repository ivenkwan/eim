package io.eim.coreapi.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amortization conventions (FR-902): exact-daily straight line on a 365/366
 * basis, full-month straight line (full-month or mid-month start convention),
 * and performance-obligation-weighted from ERP revenue events. All amounts
 * are BigDecimal at scale 4 with HALF_EVEN rounding; the sub-cent residual
 * is absorbed in the final schedule period so Σ amortization ≡ capitalized
 * asset exactly (FR-902.4). Pure functions — unit-tested in isolation and
 * reused for both individual contracts and portfolio cohorts (FR-902.5).
 */
public final class AmortizationEngine {

    public enum Convention {
        EXACT_DAILY, FULL_MONTH, MID_MONTH, PO_WEIGHTED
    }

    public record ScheduleLine(String periodKey, LocalDate start, LocalDate end, BigDecimal amount) {
    }

    public record POWeight(String periodKey, BigDecimal weight) {
    }

    private AmortizationEngine() {
    }

    /**
     * Build the monthly schedule for one capitalized asset.
     *
     * @param amount    capitalized amount (functional currency, scale 4)
     * @param start     amortization start (contract start / capitalization date)
     * @param months    amortization term in months (ECL or contract term)
     * @param convention one of EXACT_DAILY / FULL_MONTH / MID_MONTH / PO_WEIGHTED
     * @param poWeights ERP revenue-event weights per period (PO_WEIGHTED only);
     *                  missing weights fall back to equal weighting
     */
    public static List<ScheduleLine> schedule(BigDecimal amount, LocalDate start, int months,
                                              Convention convention, List<POWeight> poWeights) {
        if (amount.signum() <= 0 || months <= 0) {
            return List.of();
        }
        List<YearMonth> periods = periods(start, months, convention);
        List<ScheduleLine> raw = switch (convention) {
            case EXACT_DAILY -> exactDaily(amount, start, periods);
            case FULL_MONTH, MID_MONTH -> equalMonthly(amount, start, periods, convention);
            case PO_WEIGHTED -> poWeighted(amount, periods, poWeights);
        };
        return absorbResidual(raw, amount);
    }

    /** Next-12-months ST vs LT split of the remaining balance (FR-906.2). */
    public static BigDecimal[] stLtSplit(List<ScheduleLine> lines, String asOfPeriodKey) {
        YearMonth asOf = YearMonth.parse(asOfPeriodKey);
        BigDecimal st = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (ScheduleLine l : lines) {
            if (l.amount().signum() <= 0) {
                continue;
            }
            total = total.add(l.amount());
            YearMonth p = YearMonth.parse(l.periodKey());
            if (p.compareTo(asOf) >= 0 && p.compareTo(asOf.plusMonths(11)) <= 0) {
                st = st.add(l.amount());
            }
        }
        return new BigDecimal[]{st, total.subtract(st)};
    }

    // ------------------------------------------------------------- conventions

    private static List<YearMonth> periods(LocalDate start, int months, Convention c) {
        YearMonth first = YearMonth.from(start);
        if (c == Convention.MID_MONTH && start.getDayOfMonth() > 15) {
            first = first.plusMonths(1); // mid-month convention: starts the month after
        }
        List<YearMonth> out = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            out.add(first.plusMonths(i));
        }
        return out;
    }

    /** FR-902.1: per-month share = days-in-month ÷ total days (365/366 aware). */
    private static List<ScheduleLine> exactDaily(BigDecimal amount, LocalDate start, List<YearMonth> periods) {
        LocalDate windowStart = start;
        LocalDate windowEnd = periods.get(periods.size() - 1).atEndOfMonth();
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(windowStart, windowEnd) + 1;
        List<ScheduleLine> out = new ArrayList<>();
        for (YearMonth m : periods) {
            LocalDate from = m == YearMonth.from(windowStart) ? windowStart : m.atDay(1);
            LocalDate to = m.atEndOfMonth().isAfter(windowEnd) ? windowEnd : m.atEndOfMonth();
            long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
            BigDecimal share = BigDecimal.valueOf(days)
                    .divide(BigDecimal.valueOf(totalDays), 12, RoundingMode.HALF_EVEN)
                    .multiply(amount)
                    .setScale(4, RoundingMode.HALF_EVEN);
            out.add(new ScheduleLine(m.toString(), from, to, share));
        }
        return out;
    }

    /** FR-902.2: equal monthly straight line (full-month / mid-month start). */
    private static List<ScheduleLine> equalMonthly(BigDecimal amount, LocalDate start,
                                                   List<YearMonth> periods, Convention c) {
        BigDecimal per = amount.divide(BigDecimal.valueOf(periods.size()), 12, RoundingMode.HALF_EVEN)
                .setScale(4, RoundingMode.HALF_EVEN);
        List<ScheduleLine> out = new ArrayList<>();
        for (YearMonth m : periods) {
            LocalDate from = c == Convention.MID_MONTH && m == periods.get(0)
                    ? m.atDay(1) : (m == YearMonth.from(start) ? start : m.atDay(1));
            out.add(new ScheduleLine(m.toString(), from, m.atEndOfMonth(), per));
        }
        return out;
    }

    /** FR-902.3: schedule proportional to ERP-recognized revenue per period. */
    private static List<ScheduleLine> poWeighted(BigDecimal amount, List<YearMonth> periods,
                                                 List<POWeight> weights) {
        Map<String, BigDecimal> byPeriod = new LinkedHashMap<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        if (weights != null) {
            for (POWeight w : weights) {
                byPeriod.put(w.periodKey(), w.weight());
                totalWeight = totalWeight.add(w.weight());
            }
        }
        List<ScheduleLine> out = new ArrayList<>();
        for (YearMonth m : periods) {
            BigDecimal w = byPeriod.getOrDefault(m.toString(), BigDecimal.ZERO);
            BigDecimal share = totalWeight.signum() > 0
                    ? w.divide(totalWeight, 12, RoundingMode.HALF_EVEN)
                            .multiply(amount).setScale(4, RoundingMode.HALF_EVEN)
                    // no ERP events (yet) for the window: straight-line fallback
                    : amount.divide(BigDecimal.valueOf(periods.size()), 4, RoundingMode.HALF_EVEN);
            out.add(new ScheduleLine(m.toString(), m.atDay(1), m.atEndOfMonth(), share));
        }
        return out;
    }

    /** FR-902.4: absorb the accumulated rounding residual into the final period. */
    private static List<ScheduleLine> absorbResidual(List<ScheduleLine> raw, BigDecimal amount) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ScheduleLine l : raw) {
            sum = sum.add(l.amount());
        }
        BigDecimal residual = amount.subtract(sum);
        if (residual.signum() == 0 || raw.isEmpty()) {
            return raw;
        }
        ScheduleLine last = raw.get(raw.size() - 1);
        List<ScheduleLine> out = new ArrayList<>(raw.subList(0, raw.size() - 1));
        out.add(new ScheduleLine(last.periodKey(), last.start(), last.end(),
                last.amount().add(residual)));
        return out;
    }

    /** Business days between two dates (inclusive start, exclusive end) — close-cycle math. */
    public static long businessDaysBetween(LocalDate from, LocalDate to) {
        long days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days == 0 ? 0 : days - 1; // count elapsed business days, not both endpoints
    }
}
