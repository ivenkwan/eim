import { useMemo, useState } from 'react';
import {
  attainmentPercent,
  parseTrace,
  signedAmount,
  type LedgerTrace,
  type StatementOverview,
} from './statementModel.ts';

// Seller statement (TG4-SEL-1/2/3): overview with attainment thermometer +
// component breakdown, the calculation-trace drawer (3 steps: split →
// window position with tier ticks → marginal slice cards) bound to the
// ledger JSONB, hover slice↔bar linkage, true-up origin badge, and a CSV
// download matching the on-screen values. tabular-nums everywhere; the
// negative semantics use sign markers, not color alone (NFR-121/122).

export interface StatementViewProps {
  readonly overview: StatementOverview;
  readonly traceFor: (component: string) => LedgerTrace | undefined;
  readonly downloadUrl?: string;
}

const TIERS = [
  { label: '0–100k', rate: '10%' },
  { label: '100–150k', rate: '15%' },
  { label: '>150k', rate: '20%' },
] as const;

export function StatementView({ overview, traceFor, downloadUrl }: StatementViewProps) {
  const [openComponent, setOpenComponent] = useState<string | null>(null);
  const [hoverSlice, setHoverSlice] = useState<string | null>(null);

  const trace = useMemo(
    () => (openComponent === null ? undefined : traceFor(openComponent)),
    [openComponent, traceFor],
  );
  const parsed = useMemo(
    () => (trace ? parseTrace(trace.trace) : undefined),
    [trace],
  );
  const hurdle = overview.nextTierHurdle ?? '150000';
  const pct = attainmentPercent('0', overview.attainment, hurdle);
  const total = signedAmount(overview.totalPayout);

  return (
    <section aria-labelledby="statement-heading" style={{ fontVariantNumeric: 'tabular-nums' }}>
      <h3 id="statement-heading">Statement — {overview.period}</h3>
      <p>
        <span data-testid="period-status">
          {overview.periodStatus === 'LOCKED' ? '🔒 Locked' : 'Open'}
        </span>
      </p>
      <p>
        Total payout:{' '}
        <strong data-testid="total-payout" aria-label={total.negative ? 'negative amount' : undefined}>
          {total.text}
          {total.negative ? ' ▼' : ''}
        </strong>
      </p>

      {/* attainment thermometer with tier ticks (FR-701) */}
      <div
        role="img"
        aria-label={`attainment ${pct.toFixed(0)}% toward the ${hurdle} hurdle`}
        data-testid="attainment-thermometer"
        style={{ border: '1px solid #888', height: 12, position: 'relative', maxWidth: 420 }}
      >
        <div style={{ width: `${pct}%`, height: '100%', background: '#345' }} />
        {TIERS.map((t, i) => (
          <span
            key={t.label}
            title={`${t.label} @ ${t.rate}`}
            style={{ position: 'absolute', left: `${[0, 66, 100][i]}%`, top: -4, fontSize: 10 }}
          >
            |
          </span>
        ))}
      </div>
      <p data-testid="next-hurdle">
        {overview.nextTierHurdle === null
          ? 'Top tier reached'
          : `Next tier hurdle: ${overview.nextTierHurdle}`}
      </p>

      <table>
        <thead>
          <tr>
            <th>Component</th>
            <th>Amount</th>
            <th>Entries</th>
            <th>Trace</th>
          </tr>
        </thead>
        <tbody>
          {overview.components.map((c) => {
            const amt = signedAmount(c.amount);
            return (
              <tr key={c.component}>
                <td>{c.component}</td>
                <td data-testid={`amount-${c.component}`}>
                  {amt.text}
                  {amt.negative ? ' ▼' : ''}
                </td>
                <td>{c.entries}</td>
                <td>
                  <button
                    type="button"
                    aria-expanded={openComponent === c.component}
                    onClick={() => setOpenComponent(openComponent === c.component ? null : c.component)}
                  >
                    {openComponent === c.component ? 'Hide trace' : 'View trace'}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {trace && parsed ? (
        <div data-testid="trace-drawer" role="region" aria-label="calculation trace">
          <h4>How this was calculated</h4>
          <ol>
            <li>
              <strong>Split:</strong> {parsed.steps.length} component steps ·{' '}
              {trace.transactionId ? `deal ${trace.transactionId.slice(0, 8)}` : 'period aggregate'}
              {trace.entryType === 'PRIOR_PERIOD_TRUEUP' ? (
                <span data-testid="trueup-origin" role="status">⎌ true-up from a prior period</span>
              ) : null}
            </li>
            <li>
              <strong>Window position:</strong> {parsed.windowFrom} → {parsed.windowTo}{' '}
              <span aria-hidden style={{ display: 'inline-block', minWidth: 120 }}>
                {parsed.slices.map((s) => (
                  <span
                    key={s.tierName}
                    title={`${s.tierName} @ ${s.rateApplied}`}
                    onMouseEnter={() => setHoverSlice(s.tierName)}
                    onMouseLeave={() => setHoverSlice(null)}
                    style={{
                      display: 'inline-block',
                      height: 8,
                      width: Math.max(2, Number(s.sliceAmount) / 400),
                      background: hoverSlice === s.tierName ? '#000' : '#567',
                    }}
                  />
                ))}
              </span>
            </li>
            <li>
              <strong>Marginal slices:</strong>
              <ul>
                {parsed.slices.map((s) => (
                  <li
                    key={s.tierName}
                    data-testid={`slice-${s.tierName}`}
                    onMouseEnter={() => setHoverSlice(s.tierName)}
                    onMouseLeave={() => setHoverSlice(null)}
                    style={{ fontWeight: hoverSlice === s.tierName ? 'bold' : 'normal' }}
                  >
                    {s.tierName}: {s.sliceAmount} × {s.rateApplied} = {s.slicePayout}
                  </li>
                ))}
              </ul>
            </li>
          </ol>
        </div>
      ) : null}

      {downloadUrl ? (
        <a href={downloadUrl} data-testid="statement-download">Download CSV</a>
      ) : null}
    </section>
  );
}
