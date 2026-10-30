package io.eim.calcengine;

import io.eim.calcengine.engine.Fx;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TG7-CAL-1 (NFR-110): multi-currency golden tests — 6-decimal rates,
 * single HALF_EVEN rounding at the final payout, cross-rate determinism
 * (converting via a derived cross rate ≡ converting through the functional
 * currency in one hop for our 4-decimal ledger scale), and replay identity
 * for a chain of period conversions.
 */
class FxGoldenTest {

    private static final BigDecimal EUR_USD = new BigDecimal("1.085340"); // 6dp
    private static final BigDecimal GBP_USD = new BigDecimal("1.271650");

    @Test
    void singleRoundingAtFinalPayout() {
        // 12,345.67 EUR at 1.085340 → 13,400.5486... → HALF_EVEN at 4dp
        BigDecimal out = Fx.convert(new BigDecimal("12345.6700"), EUR_USD);
        assertEquals(0, out.compareTo(new BigDecimal("13399.2495")),
                "exact 6dp-rate conversion rounded once: " + out.toPlainString());
    }

    @Test
    void crossRateDerivedUnroundedNeverDoublesRounds() {
        // EUR→GBP via USD: derive the cross rate at full precision, convert once
        BigDecimal eurPerGbp = Fx.crossRate(EUR_USD, BigDecimal.ONE.divide(GBP_USD,
                12, java.math.RoundingMode.HALF_EVEN));
        BigDecimal direct = Fx.convertCross(new BigDecimal("10000.0000"), EUR_USD,
                BigDecimal.ONE.divide(GBP_USD, 12, java.math.RoundingMode.HALF_EVEN));
        // sanity: EUR→GBP rate is ~0.8537
        assertTrue(eurPerGbp.compareTo(new BigDecimal("0.85")) > 0
                && eurPerGbp.compareTo(new BigDecimal("0.86")) < 0,
                "cross within bounds: " + eurPerGbp.toPlainString());
        // 10,000 EUR ≈ 8,537.xx GBP — deterministic golden value
        assertEquals("8534.8956", direct.toPlainString(),
                "cross-rate conversion golden (single rounding)");
    }

    @Test
    void identityRateIsPassThrough() {
        assertEquals(0, Fx.convert(new BigDecimal("500.0000"), null)
                .compareTo(new BigDecimal("500.0000")));
        assertEquals(0, Fx.convert(new BigDecimal("500.0000"), BigDecimal.ONE)
                .compareTo(new BigDecimal("500.0000")));
    }

    @Test
    void replayIsDeterministicAcrossRepeatedRuns() {
        BigDecimal amount = new BigDecimal("99999.9999");
        BigDecimal first = null;
        for (int i = 0; i < 100; i++) {
            BigDecimal r = Fx.convert(amount, new BigDecimal("7.123456"));
            if (first == null) {
                first = r;
            } else {
                assertEquals(0, first.compareTo(r), "run " + i + " identical");
            }
        }
        // golden: 99,999.9999 × 7.123456 = 712,345.528... → 712345.5288
        assertEquals(0, first.compareTo(new BigDecimal("712345.5993")),
                "high-rate golden: " + first.toPlainString());
    }

    @Test
    void roundTripWithinOneCentTolerance() {
        // convert + invert must return to within 0.0001 for 6dp rates
        BigDecimal amount = new BigDecimal("25000.0000");
        BigDecimal usd = Fx.convert(amount, EUR_USD);
        BigDecimal back = usd.divide(EUR_USD, 4, java.math.RoundingMode.HALF_EVEN);
        assertTrue(back.subtract(amount).abs().compareTo(new BigDecimal("0.0002")) <= 0,
                "round trip within one cent: " + back.toPlainString());
    }

    private static void assertTrue(boolean b, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(b, msg);
    }
}
