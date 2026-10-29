package io.eim.calcengine.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Protections (FR-405/504/505): draws state machine, caps with capped
 * residual, clawbacks with churn window + active-days proration and origin
 * linkage. All arithmetic via Precise (DECIMAL128, single rounding point).
 */
final class Protections {
    private Protections() {
    }
}

/** Draws (FR-504): advances create a carry-forward deficit for
 *  RECOVERABLE draws; later earnings recover it first; NON_RECOVERABLE
 *  advances never recover. Typed entries: DRAW_ADVANCE / DRAW_RECOVERY. */
final class DrawState {

    record Result(BigDecimal net, BigDecimal advance, BigDecimal recovery, BigDecimal deficit) {
    }

    static Result apply(String drawKind, String openingBalance, BigDecimal earnings) {
        BigDecimal deficit = Precise.v(openingBalance == null || openingBalance.isBlank()
                ? "0" : openingBalance);
        if (earnings == null || earnings.signum() <= 0) {
            return new Result(earnings, BigDecimal.ZERO, BigDecimal.ZERO, deficit);
        }
        if ("RECOVERABLE".equalsIgnoreCase(drawKind) && deficit.signum() > 0) {
            BigDecimal recovery = deficit.min(earnings);
            BigDecimal net = Precise.sub(earnings, recovery);
            return new Result(net, BigDecimal.ZERO, recovery, Precise.sub(deficit, recovery));
        }
        return new Result(earnings, BigDecimal.ZERO, BigDecimal.ZERO, deficit);
    }
}

/** Caps (FR-405.3): PERIOD/ANNUAL scope, AMOUNT or PERCENT basis; the
 *  capped residual is exposed for traces. */
final class Cap {

    record Result(BigDecimal capped, BigDecimal residual) {
    }

    static Result apply(String capValue, String capBasis, BigDecimal target, BigDecimal booked) {
        if (capValue == null || target == null) {
            return new Result(target, null);
        }
        BigDecimal cap;
        if ("PERCENT".equalsIgnoreCase(String.valueOf(capBasis))) {
            cap = Precise.mul(booked == null ? BigDecimal.ZERO : booked,
                    Precise.div(Precise.v(capValue), Precise.v("100")));
        } else {
            cap = Precise.v(capValue);
        }
        if (target.compareTo(cap) <= 0) {
            return new Result(target, null);
        }
        return new Result(cap, Precise.sub(target, cap));
    }
}

/**
 * Clawbacks (FR-505): a churn flag within the lookback window claws back
 * the credited amount of the churned transaction as a NEGATIVE entry in
 * the OPEN period, prorated by active days and linked to its origin
 * period. Window membership is resolved by the caller (the runner knows
 * the churn event date); the engine consumes the flagged transaction id.
 */
final class Clawback {

    record Result(BigDecimal amount, BigDecimal credited, String transactionId,
                  String originPeriodId, BigDecimal factor) {
    }

    static Result apply(String windowDays, String churnedTxnId, List<Engine.Txn> transactions,
                        String periodStart, String periodEnd,
                        String activeDays, String periodDays, String originPeriodId) {
        if (churnedTxnId == null || churnedTxnId.isBlank()) {
            return new Result(BigDecimal.ZERO, null, null, null, null);
        }
        Engine.Txn churned = transactions.stream()
                .filter(t -> churnedTxnId.equals(t.transactionId()))
                .findFirst().orElse(null);
        if (churned == null) {
            return new Result(BigDecimal.ZERO, null, churnedTxnId, originPeriodId, null);
        }
        long active = Long.parseLong(activeDays == null || activeDays.isBlank() ? "0" : activeDays);
        long period = Long.parseLong(periodDays == null || periodDays.isBlank() ? "0" : periodDays);
        BigDecimal factor = Precise.prorationFactor(active, period);
        BigDecimal amount = Precise.mul(churned.amount().negate(), factor);
        return new Result(amount, churned.amount(), churnedTxnId, originPeriodId, factor);
    }
}
