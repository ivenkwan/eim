import { Money, sliceAcrossTiers, totalPayout, type TierDefinition, type TierSlice } from '@eim/shared-domain';
import { Button } from '@eim/ui-kit';

// FR-502.1 canonical example rendered with the exact shared-domain engine —
// the same math that powers the golden harness (TG0-TST-4).
const CANONICAL_TIERS: readonly TierDefinition[] = [
  { name: 'Tier 1 (0-100%)', floor: Money.zero(0), ceiling: Money.fromString('100000'), rate: '0.100000' },
  { name: 'Tier 2 (100-150%)', floor: Money.fromString('100000'), ceiling: Money.fromString('150000'), rate: '0.150000' },
  { name: 'Tier 3 (>150%)', floor: Money.fromString('150000'), ceiling: null, rate: '0.200000' },
];

export interface AppProps {
  readonly attainmentBefore?: string;
  readonly attainmentAfter?: string;
}

export function App({ attainmentBefore = '90000', attainmentAfter = '160000' }: AppProps) {
  const slices: readonly TierSlice[] = sliceAcrossTiers(
    Money.fromString(attainmentBefore),
    Money.fromString(attainmentAfter),
    CANONICAL_TIERS,
  );
  const total = totalPayout(slices, 'HALF_EVEN', 2);

  return (
    <main>
      <h1>EIM Platform — seller statement preview</h1>
      <table style={{ fontVariantNumeric: 'tabular-nums' }}>
        <thead>
          <tr>
            <th>Tier</th>
            <th>Slice</th>
            <th>Rate</th>
            <th>Payout</th>
          </tr>
        </thead>
        <tbody>
          {slices.map((slice) => (
            <tr key={slice.tierName}>
              <td>{slice.tierName}</td>
              <td>{slice.sliceAmount.toString()}</td>
              <td>{slice.rateApplied}</td>
              <td>{slice.slicePayout.round('HALF_EVEN', 2).toString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        <strong data-testid="total-payout">{total.toString()}</strong>
      </p>
      <Button variant="primary">Download statement</Button>
    </main>
  );
}
