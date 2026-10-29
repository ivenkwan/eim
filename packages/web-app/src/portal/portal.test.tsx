import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DisputeForm } from './DisputeForm.tsx';
import { StatementView } from './StatementView.tsx';
import {
  attainmentPercent,
  parseTrace,
  preTag,
  signedAmount,
  type LedgerTrace,
  type StatementOverview,
} from './statementModel.ts';

const OVERVIEW: StatementOverview = {
  period: '2026-04',
  periodStatus: 'OPEN',
  totalPayout: '10500.00',
  attainment: '160000',
  nextTierHurdle: null,
  components: [
    { component: 'commission', amount: '10500.00', entries: 1 },
    { component: 'clawback', amount: '-800.00', entries: 1 },
  ],
};

const TRACE: LedgerTrace = {
  ledgerId: 'ledger-1',
  component: 'commission',
  amount: '10500.00',
  entryType: 'CALCULATED',
  transactionId: 'txn-12345678',
  trace: JSON.stringify({
    components: { window_from: '90000', window_to: '160000' },
    steps: [
      { component: 'attainment', kind: 'FORMULA', value: '160000' },
      {
        component: 'commission',
        kind: 'TIER_TABLE',
        slices: [
          { tierName: 'Tier 1', sliceAmount: '10000', rateApplied: '0.10', slicePayout: '1000' },
          { tierName: 'Tier 2', sliceAmount: '50000', rateApplied: '0.15', slicePayout: '7500' },
          { tierName: 'Tier 3', sliceAmount: '10000', rateApplied: '0.20', slicePayout: '2000' },
        ],
      },
    ],
  }),
};

const TRUEUP_TRACE: LedgerTrace = {
  ...TRACE,
  entryType: 'PRIOR_PERIOD_TRUEUP',
  amount: '8000.00',
};

const traces = new Map<string, LedgerTrace>([
  ['commission', TRACE],
  ['clawback', TRUEUP_TRACE],
]);

describe('statement overview (FR-701)', () => {
  it('renders total, open/locked state, thermometer and hurdle', () => {
    render(
      <StatementView
        overview={OVERVIEW}
        traceFor={(c) => traces.get(c)}
      />,
    );
    expect(screen.getByTestId('total-payout').textContent).toBe('10500.00');
    expect(screen.getByTestId('period-status').textContent).toContain('Open');
    expect(screen.getByTestId('attainment-thermometer')).toBeTruthy();
    expect(screen.getByTestId('next-hurdle').textContent).toContain('Top tier');
  });

  it('flags negative amounts with sign + marker, not color alone (NFR-122)', () => {
    render(
      <StatementView overview={OVERVIEW} traceFor={(c) => traces.get(c)} />,
    );
    const cell = screen.getByTestId('amount-clawback');
    expect(cell.textContent).toContain('−');
    expect(cell.textContent).toContain('▼');
  });
});

describe('calculation trace drawer (FR-702)', () => {
  it('binds to the ledger JSONB: 3 steps, slices, window, origin badge', () => {
    render(
      <StatementView overview={OVERVIEW} traceFor={(c) => traces.get(c)} />,
    );
    fireEvent.click(screen.getAllByRole('button', { name: 'View trace' })[0] as HTMLElement);
    const drawer = screen.getByTestId('trace-drawer');
    expect(drawer.textContent).toContain('90000 → 160000');
    expect(screen.getByTestId('slice-Tier 1').textContent)
      .toContain('10000 × 0.10 = 1000');
    expect(screen.getByTestId('slice-Tier 3').textContent)
      .toContain('10000 × 0.20 = 2000');
    fireEvent.click(screen.getAllByRole('button', { name: 'Hide trace' })[0] as HTMLElement);

    // the true-up entry shows the origin badge
    const rows = screen.getAllByRole('button', { name: 'View trace' });
    fireEvent.click(rows[1] as HTMLElement);
    expect(screen.getByTestId('trueup-origin').textContent).toContain('true-up');
  });

  it('hovering a slice card highlights the matching bar segment', () => {
    render(
      <StatementView overview={OVERVIEW} traceFor={(c) => traces.get(c)} />,
    );
    fireEvent.click(screen.getAllByRole('button', { name: 'View trace' })[0] as HTMLElement);
    const slice = screen.getByTestId('slice-Tier 2');
    fireEvent.mouseEnter(slice);
    expect(slice.style.fontWeight).toBe('bold');
  });
});

describe('dispute form (FR-704)', () => {
  it('pre-tags the category from the symptom (backward chaining)', () => {
    render(<DisputeForm onSubmit={() => {}} />);
    expect(screen.getByTestId('pretag').textContent).toContain('SOURCE_DATA');
    fireEvent.change(screen.getByLabelText('symptom'), {
      target: { value: 'split_sum_off' },
    });
    expect(screen.getByTestId('pretag').textContent).toContain('CREDIT_SPLIT');
    fireEvent.change(screen.getByLabelText('symptom'), {
      target: { value: 'window_boundary' },
    });
    expect(screen.getByTestId('pretag').textContent).toContain('WINDOW_STATE');
  });

  it('submits the structured payload with attachments + reference', () => {
    const onSubmit = vi.fn();
    render(
      <DisputeForm
        ledgerId="ledger-1"
        transactionId="txn-12345678"
        onSubmit={onSubmit}
      />,
    );
    fireEvent.change(screen.getByLabelText('notes'), {
      target: { value: 'split missing a co-payee' },
    });
    fireEvent.change(screen.getByLabelText('attachments'), {
      target: { value: 'crm.png, deal.pdf' },
    });
    fireEvent.click(screen.getByTestId('dispute-submit'));
    expect(onSubmit).toHaveBeenCalledWith({
      category: 'SOURCE_DATA',
      notes: 'split missing a co-payee',
      ledgerId: 'ledger-1',
      transactionId: 'txn-12345678',
      symptom: 'late_arrival',
      attachments: ['crm.png', 'deal.pdf'],
    });
  });
});

describe('statement math helpers', () => {
  it('attainment percent clamps to [0, 100]', () => {
    expect(attainmentPercent('0', '160000', '150000')).toBe(100);
    expect(attainmentPercent('90000', '160000', '150000')).toBe(100);
    expect(attainmentPercent('0', '0', '100000')).toBe(0);
    expect(attainmentPercent('0', '50000', '100000')).toBeCloseTo(50);
  });

  it('signedAmount marks negatives explicitly', () => {
    expect(signedAmount('-800.00')).toEqual({ text: '−800.00', negative: true });
    expect(signedAmount('10500.00')).toEqual({ text: '10500.00', negative: false });
  });

  it('parseTrace reads window + slices from the raw JSONB text', () => {
    const parsed = parseTrace(TRACE.trace);
    expect(parsed.windowFrom).toBe('90000');
    expect(parsed.slices).toHaveLength(3);
  });

  it('preTag mirrors the DRL triage table', () => {
    expect(preTag('missing_source_row')).toBe('SOURCE_DATA');
    expect(preTag('reset_cadence')).toBe('WINDOW_STATE');
    expect(preTag('weird_thing')).toBe('PLAN_LOGIC');
  });
});
