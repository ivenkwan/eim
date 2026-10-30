package io.eim.calcengine.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Multi-currency conversion (TG7-CAL-1, NFR-110): rates carry 6 decimals;
 * conversions round HALF_EVEN to the ledger scale (4) exactly once, at the
 * final payout — intermediates keep full precision so a chain of
 * conversions is deterministic and replay-identical. Cross rates are
 * derived through the functional currency with a single rounding at the
 * end, never by multiplying two rounded rates.
 */
public final class Fx {

    public static final int RATE_SCALE = 6;
    public static final int MONEY_SCALE = 4;

    private Fx() {
    }

    /** Convert at a 6-decimal rate, rounding once at ledger scale. */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ONE) == 0) {
            return amount.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        }
        return amount.multiply(rate)
                .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Derive a cross rate through the functional currency, unrounded. */
    public static BigDecimal crossRate(BigDecimal sourceToFunctional,
                                       BigDecimal functionalToTarget) {
        return sourceToFunctional.multiply(functionalToTarget);
    }

    /** Convert via a derived cross rate — single rounding at the end. */
    public static BigDecimal convertCross(BigDecimal amount, BigDecimal sourceToFunctional,
                                          BigDecimal functionalToTarget) {
        return convert(amount, crossRate(sourceToFunctional, functionalToTarget));
    }
}
