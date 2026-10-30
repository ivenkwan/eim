import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { axe } from 'vitest-axe';
import {
  estimatorReducer,
  termAdjusted,
  tierPayout,
  WhatIfEstimator,
  type PipelineDeal,
} from './WhatIfEstimator.tsx';
import {
  ManagerDashboard,
  TeamDisputeHealth,
  teamAttainment,
  totalPayout,
  type DownlineMember,
} from './ManagerDashboard.tsx';
import { CrmEmbed } from './CrmEmbed.tsx';
import {
  AccountingConsole,
  identityHolds,
  type RollforwardRow,
} from './AccountingConsole.tsx';

const deals: readonly PipelineDeal[] = [
  { id: 'd1', name: 'Acme expansion', amount: 60_000, stage: 'commit', selected: true, termMonths: 12 },
  { id: 'd2', name: 'Globco renewal', amount: 60_000, stage: 'open', selected: true, termMonths: 12 },
  { id: 'd3', name: 'Initech new logo', amount: 60_000, stage: 'best_case', selected: false, termMonths: 24 },
];

describe('TG7-UI-1 what-if estimator (FR-510)', () => {
  it('marginal tier math matches the engine slicing', () => {
    // canonical $160k → 100k×10% + 50k×15% + 10k×20% = 19,500
    expect(tierPayout(160_000)).toBeCloseTo(19_500, 6);
    expect(tierPayout(90_000)).toBeCloseTo(9_000, 6);
  });

  it('longer terms adjust the effective amount', () => {
    expect(termAdjusted({ ...deals[2]!, termMonths: 24 })).toBeCloseTo(67_200, 6);
  });

  it('toggling selections and term edits flow through the reducer', () => {
    render(<WhatIfEstimator deals={deals} />);
    // d1 + d2 selected: 120k → 100k×10% + 20k×15% = 13,000
    expect(screen.getByText('13000.00')).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Include Initech new logo' }));
    // + d3 adjusted 67.2k → 187.2k → 10k+7.5k+37.2k×.2 = 24,940
    expect(screen.getByText('24940.00')).toBeTruthy();
    const sandboxNote = screen.getByText(/Sandbox only/);
    expect(sandboxNote).toBeTruthy();
  });

  it('reducer toggles and updates terms immutably', () => {
    const s0 = { deals };
    const s1 = estimatorReducer(s0, { type: 'toggle', id: 'd3' });
    expect(s1.deals[2]!.selected).toBe(true);
    expect(s0.deals[2]!.selected).toBe(false);
    const s2 = estimatorReducer(s1, { type: 'term', id: 'd3', months: 18 });
    expect(s2.deals[2]!.termMonths).toBe(18);
  });
});

const members: readonly DownlineMember[] = [
  { payeeId: 'p1', name: 'Ray', quota: 100_000, attainment: 120, payout: 19_500, disputesOpen: 1, disputesBreached: 0 },
  { payeeId: 'p2', name: 'Sam', quota: 80_000, attainment: 60, payout: 6_000, disputesOpen: 0, disputesBreached: 0 },
  { payeeId: 'p3', name: 'Kim', quota: 120_000, attainment: 95, payout: 11_000, disputesOpen: 2, disputesBreached: 1 },
];

describe('TG7-UI-2 manager dashboard (FR-706)', () => {
  it('aggregates attainment and payout over the downline', () => {
    expect(teamAttainment(members)).toBeCloseTo((120 + 60 + 95) / 3, 6);
    expect(totalPayout(members)).toBeCloseTo(36_500, 6);
  });

  it('derives dispute health levels', () => {
    expect(TeamDisputeHealth(members)).toBe('BREACHED');
    expect(TeamDisputeHealth(members.slice(0, 2))).toBe('WATCH');
    expect(
      TeamDisputeHealth([{ ...members[1]!, disputesOpen: 0 }]),
    ).toBe('CLEAR');
  });

  it('renders the as-of view with progressbars and sorting', async () => {
    const { container } = render(<ManagerDashboard members={members} asOf="2026-06-30" />);
    expect(screen.getByText(/Effective-dated downline as of/)).toBeTruthy();
    expect(screen.getAllByRole('progressbar').length).toBe(3);
    fireEvent.change(screen.getByLabelText('Sort members'), { target: { value: 'payout' } });
    const firstRow = container.querySelector('.downline-table tbody tr td')?.textContent;
    expect(firstRow).toBe('Ray');
    const results = await axe(container);
    expect(results.violations).toHaveLength(0);
  });
});

describe('TG7-UI-3 CRM embed (FR-707)', () => {
  it('waits for a CRM context message before showing data', () => {
    const { container } = render(
      <CrmEmbed
        allowedOrigins={['https://crm.example.test']}
        summary={{
          periodLabel: '2026-09',
          totalPayout: '19500.00',
          components: [{ name: 'commission', amount: '19500.00' }],
        }}
      />,
    );
    expect(screen.getByRole('status').textContent).toContain('Connecting');
    // no statement content leaks before the handshake
    expect(container.querySelector('.embed-summary')).toBeNull();
  });

  it('accepts the context from an allowed origin and acks', async () => {
    const { container } = render(
      <CrmEmbed
        allowedOrigins={['https://crm.example.test']}
        summary={{
          periodLabel: '2026-09',
          totalPayout: '19500.00',
          components: [{ name: 'commission', amount: '19500.00' }],
        }}
      />,
    );
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://crm.example.test',
        data: { type: 'eim:context', tenantId: 't1', payeeId: 'payee-12345678', token: 'x' },
        source: window,
      }),
    );
    await waitFor(() =>
      expect(
        screen.getAllByText((_, el) =>
          (el?.textContent ?? '').includes('Connected · payee payee-12'),
        ).length,
      ).toBeGreaterThan(0),
    );
    await waitFor(() => expect(container.querySelector('.embed-summary')).not.toBeNull());
    expect(screen.getAllByText('19500.00').length).toBeGreaterThan(0);
  });

  it('drops messages from foreign origins', () => {
    render(
      <CrmEmbed
        allowedOrigins={['https://crm.example.test']}
        summary={{
          periodLabel: '2026-09',
          totalPayout: '19500.00',
          components: [],
        }}
      />,
    );
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://evil.example',
        data: { type: 'eim:context', tenantId: 't1', payeeId: 'p', token: 'x' },
      }),
    );
    expect(screen.getByRole('status').textContent).toContain('Connecting');
  });
});

const goodRow: RollforwardRow = {
  periodKey: '2026-08',
  beginning: 0,
  capitalized: 26_040,
  trueUps: 0,
  amortization: 1_085,
  impairments: 0,
  ending: 24_955,
  current: 13_020,
  nonCurrent: 11_935,
};

describe('TG7-UI-4 accounting console (FR-906/907 UI)', () => {
  it('validates the rollforward identity per row', () => {
    expect(identityHolds(goodRow)).toBe(true);
    expect(
      identityHolds({ ...goodRow, ending: goodRow.ending + 1 }),
    ).toBe(false);
  });

  it('flags identity failures and failed batches for retry', async () => {
    let retried = '';
    const badRow = { ...goodRow, ending: goodRow.ending + 5 };
    const { container } = render(
      <AccountingConsole
        rollforward={[goodRow, badRow]}
        batches={[
          { batchId: 'b1', jeType: 'CAPITALIZATION', status: 'FAILED', totalDr: '26040.00', totalCr: '26040.00', erpTarget: 'NETSUITE' },
          { batchId: 'b2', jeType: 'AMORTIZATION', status: 'SYNCED', totalDr: '1085.00', totalCr: '1085.00', erpTarget: 'NETSUITE' },
        ]}
        books={[
          { bookCode: 'US_GAAP', functionalCurrency: 'USD', amortizationConvention: 'FULL_MONTH', expedientThresholdMonths: 12, fringeRate: '0.08500', dmnVersion: 3 },
        ]}
        onPushRetry={(id) => (retried = id)}
      />,
    );
    expect(screen.getByText(/IDENTITY FAILURE/)).toBeTruthy();
    expect(screen.getByRole('alert').textContent).toContain('1 batch(es) FAILED');
    fireEvent.click(screen.getByRole('button', { name: 'Retry push' }));
    expect(retried).toBe('b1');
    const results = await axe(container);
    expect(results.violations).toHaveLength(0);
  });
});
