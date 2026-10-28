import { describe, expect, it } from 'vitest';
import { Money } from './money.ts';
import { sliceAcrossTiers, totalPayout, type TierDefinition } from './tiers.ts';

// FR-502.1 canonical acceptance case, verbatim from the requirements.
const quota = Money.fromString('100000');
const tiers: readonly TierDefinition[] = [
  { name: 'Tier 1 (0-100%)', floor: Money.zero(0), ceiling: quota, rate: '0.100000' },
  { name: 'Tier 2 (100-150%)', floor: quota, ceiling: Money.fromString('150000'), rate: '0.150000' },
  { name: 'Tier 3 (>150%)', floor: Money.fromString('150000'), ceiling: null, rate: '0.200000' },
];

describe('sliceAcrossTiers (FR-502 marginal interval intersection)', () => {
  it('splits a single $70k deal at $90k attainment across 3 tiers', () => {
    const before = Money.fromString('90000');
    const after = Money.fromString('160000');
    const slices = sliceAcrossTiers(before, after, tiers);
    expect(slices).toHaveLength(3);

    expect(slices[0]?.tierName).toBe('Tier 1 (0-100%)');
    expect(slices[0]?.sliceAmount.toString()).toBe('10000');
    expect(slices[0]?.slicePayout.toString()).toBe('1000.000000');

    expect(slices[1]?.sliceAmount.toString()).toBe('50000');
    expect(slices[1]?.slicePayout.toString()).toBe('7500.000000');

    expect(slices[2]?.sliceAmount.toString()).toBe('10000');
    expect(slices[2]?.slicePayout.toString()).toBe('2000.000000');
  });

  it('returns one slice when the deal stays inside a tier', () => {
    const slices = sliceAcrossTiers(Money.fromString('10000'), Money.fromString('20000'), tiers);
    expect(slices).toHaveLength(1);
    expect(slices[0]?.rateApplied).toBe('0.100000');
    expect(slices[0]?.sliceAmount.toString()).toBe('10000');
  });

  it('returns no slices for zero-width or reversed intervals', () => {
    expect(sliceAcrossTiers(Money.fromString('5000'), Money.fromString('5000'), tiers)).toHaveLength(0);
    expect(sliceAcrossTiers(Money.fromString('6000'), Money.fromString('5000'), tiers)).toHaveLength(0);
  });

  it('emits the canonical total payout $10,500.00 after single HALF_EVEN rounding', () => {
    const slices = sliceAcrossTiers(Money.fromString('90000'), Money.fromString('160000'), tiers);
    expect(totalPayout(slices, 'HALF_EVEN', 2).toString()).toBe('10500.00');
  });

  it('keeps slices unrounded until the caller rounds (NFR-02)', () => {
    const slices = sliceAcrossTiers(Money.fromString('90000'), Money.fromString('160000'), tiers);
    for (const slice of slices) {
      expect(slice.slicePayout.scale).toBeGreaterThan(2);
    }
  });
});
