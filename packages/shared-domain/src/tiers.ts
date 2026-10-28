/**
 * Canonical marginal tier-slicing for the FR-502.1 acceptance example,
 * implemented on exact Money math. Kept in shared-domain so the frontend
 * preview (FR-407) and the golden harness (TG0-TST-4) share one truth.
 */
import { Money } from './money.ts';
import type { RoundingMode } from './rounding.ts';

export interface TierDefinition {
  readonly name: string;
  /** Tier floor and ceiling expressed as attainment amounts (exclusive floor, inclusive-ish ceiling). */
  readonly floor: Money;
  readonly ceiling: Money | null; // null = unbounded top tier
  readonly rate: string; // exact decimal rate, e.g. '0.150000'
}

export interface TierSlice {
  readonly tierName: string;
  readonly sliceAmount: Money;
  readonly rateApplied: string;
  readonly slicePayout: Money; // rounded at the caller's final step only
}

/**
 * Split the attainment interval (attainmentBefore -> attainmentAfter) across
 * tiers via interval intersection — the exact math mandated by FR-502.
 */
export function sliceAcrossTiers(
  attainmentBefore: Money,
  attainmentAfter: Money,
  tiers: readonly TierDefinition[],
): TierSlice[] {
  const slices: TierSlice[] = [];
  for (const tier of tiers) {
    const lower = tier.floor.compareTo(attainmentBefore) > 0 ? tier.floor : attainmentBefore;
    const upper =
      tier.ceiling !== null && tier.ceiling.compareTo(attainmentAfter) < 0 ? tier.ceiling : attainmentAfter;
    if (upper.compareTo(lower) <= 0) continue;
    const sliceAmount = upper.sub(lower);
    slices.push({
      tierName: tier.name,
      sliceAmount,
      rateApplied: tier.rate,
      slicePayout: sliceAmount.multiplyRate(tier.rate),
    });
  }
  return slices;
}

/** Sum slice payouts and apply the single final rounding step (NFR-02). */
export function totalPayout(slices: readonly TierSlice[], mode: RoundingMode, finalScale: number): Money {
  let total = Money.zero(0);
  for (const slice of slices) {
    total = total.add(slice.slicePayout);
  }
  return total.round(mode, finalScale);
}
