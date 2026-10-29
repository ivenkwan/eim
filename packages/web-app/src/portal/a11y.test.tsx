import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { axe } from 'vitest-axe';
import { DisputeForm } from './DisputeForm.tsx';
import { StatementView } from './StatementView.tsx';
import type { StatementOverview } from './statementModel.ts';

// Accessibility (TG4-SEL-6, NFR-73): axe-core CI passes on the seller
// surfaces — WCAG 2.1 AA automated checks. Screen-reader passes (labels,
// roles, announcements) are asserted alongside; tabular numerals and
// sign-based negative semantics are enforced by the components + tests in
// portal.test.tsx (NFR-121/122).

const OVERVIEW: StatementOverview = {
  period: '2026-04',
  periodStatus: 'OPEN',
  totalPayout: '10500.00',
  attainment: '160000',
  nextTierHurdle: '150000',
  components: [
    { component: 'commission', amount: '10500.00', entries: 1 },
    { component: 'clawback', amount: '-800.00', entries: 1 },
  ],
};

describe('axe-core WCAG 2.1 AA (NFR-73)', () => {
  it('statement view has no automatic violations', async () => {
    const { container } = render(
      <StatementView overview={OVERVIEW} traceFor={() => undefined} />,
    );
    const results = await axe(container);
    expect(results.violations).toHaveLength(0);
  });

  it('dispute form has no automatic violations', async () => {
    const { container } = render(<DisputeForm onSubmit={() => {}} />);
    const results = await axe(container);
    expect(results.violations).toHaveLength(0);
  });

  it('trace drawer passes after opening', async () => {
    const trace = {
      ledgerId: 'l1',
      component: 'commission',
      amount: '10500.00',
      entryType: 'CALCULATED',
      transactionId: 't1',
      trace: JSON.stringify({
        components: { window_from: '0', window_to: '160000' },
        steps: [
          {
            component: 'commission',
            kind: 'TIER_TABLE',
            slices: [
              { tierName: 'Tier 1', sliceAmount: '100000', rateApplied: '0.10', slicePayout: '10000' },
            ],
          },
        ],
      }),
    };
    const { container, getAllByRole } = render(
      <StatementView overview={OVERVIEW} traceFor={() => trace} />,
    );
    getAllByRole('button', { name: 'View trace' })[0]?.dispatchEvent(new Event('click', { bubbles: true }));
    const results = await axe(container);
    expect(results.violations).toHaveLength(0);
  });
});
