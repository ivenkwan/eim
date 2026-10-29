package io.eim.calcengine.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Precision policy (NFR-01/02): DECIMAL128 semantics everywhere (34
 * significant digits), no floats, and a SINGLE rounding point at final
 * payout — half-even by default, half-up configurable. Everything upstream
 * carries full precision.
 */
public final class Precise {

    public static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    public static final MathContext MC_UP = new MathContext(34, RoundingMode.HALF_UP);

    private Precise() {
    }

    public static BigDecimal v(String s) {
        return new BigDecimal(s);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b, MC);
    }

    public static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return a.subtract(b, MC);
    }

    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return a.multiply(b, MC);
    }

    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, MC);
    }

    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    /** The single rounding point (FR-507): payout scale, default HALF_EVEN. */
    public static BigDecimal roundPayout(BigDecimal value, RoundingMode mode) {
        return value.setScale(2, mode == null ? RoundingMode.HALF_EVEN : mode);
    }

    /** Ratio of active days in the period (mid-period proration, FR-506). */
    public static BigDecimal prorationFactor(long activeDays, long periodDays) {
        if (periodDays <= 0) {
            return BigDecimal.ZERO;
        }
        return div(BigDecimal.valueOf(Math.min(activeDays, periodDays)),
                BigDecimal.valueOf(periodDays));
    }
}
