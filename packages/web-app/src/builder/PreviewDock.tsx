import { useMemo, useState } from 'react';
import { createPreviewCache, hashParts } from './cache.ts';
import type { PlanModel } from './planModel.ts';

// Live sandbox preview dock (FR-407, NFR-23): payee/period selector,
// deal-by-deal outputs against the DRAFT plan, delta vs the PUBLISHED
// version, served from the dirty-flag cache. Synthetic test deals are
// LABELED and never persisted — they live only in this component.

export interface PreviewDeal {
  readonly id: string;
  readonly date: string;
  readonly amount: string;
  readonly synthetic: boolean;
}

export interface PreviewOutput {
  readonly components: Readonly<Record<string, string>>;
  readonly lines: readonly { component: string; entryType: string; amount: string }[];
  readonly elapsedMs: number;
}

export type PreviewFn = (plan: PlanModel, deals: readonly PreviewDeal[]) => Promise<PreviewOutput>;

export interface PreviewDockProps {
  readonly plan: PlanModel;
  readonly published?: PreviewOutput | undefined;
  readonly preview: PreviewFn;
  readonly payees: readonly string[];
  readonly periods: readonly string[];
  readonly disabled?: boolean;
}

export function PreviewDock({ plan, published, preview, payees, periods, disabled }: PreviewDockProps) {
  const [payee, setPayee] = useState(payees[0] ?? '—');
  const [period, setPeriod] = useState(periods[0] ?? '—');
  const [deals] = useState<readonly PreviewDeal[]>([
    { id: 'synthetic-1', date: '2026-04-10', amount: '70000', synthetic: true },
  ]);
  const [output, setOutput] = useState<PreviewOutput | null>(null);
  const [busy, setBusy] = useState(false);

  const cache = useMemo(() => createPreviewCache(), [plan]);
  const run = async (): Promise<void> => {
    setBusy(true);
    try {
      const [astHash, inputsHash] = hashParts(plan, { payee, period, deals });
      const hit = cache.get(astHash, inputsHash);
      if (hit) {
        setOutput(hit.value as PreviewOutput);
        return;
      }
      const out = await preview(plan, deals);
      cache.put(astHash, inputsHash, out);
      setOutput(out);
    } finally {
      setBusy(false);
    }
  };

  const delta =
    output && published
      ? Number(output.components.commission ?? '0') - Number(published.components.commission ?? '0')
      : null;

  return (
    <aside className="preview-dock" aria-labelledby="preview-heading">
      <h4 id="preview-heading">Sandbox preview</h4>
      <div style={{ display: 'flex', gap: 8 }}>
        <label>
          payee:{' '}
          <select aria-label="preview payee" value={payee} onChange={(e) => setPayee(e.target.value)}>
            {payees.map((p) => (
              <option key={p}>{p}</option>
            ))}
          </select>
        </label>
        <label>
          period:{' '}
          <select aria-label="preview period" value={period} onChange={(e) => setPeriod(e.target.value)}>
            {periods.map((p) => (
              <option key={p}>{p}</option>
            ))}
          </select>
        </label>
      </div>
      <table style={{ fontVariantNumeric: 'tabular-nums' }}>
        <thead>
          <tr>
            <th>Deal</th>
            <th>Date</th>
            <th>Amount</th>
          </tr>
        </thead>
        <tbody>
          {deals.map((d) => (
            <tr key={d.id}>
              <td>
                {d.synthetic ? <em data-testid="synthetic-label">synthetic</em> : d.id}
              </td>
              <td>{d.date}</td>
              <td>{d.amount}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <button
        type="button"
        disabled={disabled || busy}
        onClick={() => void run()}
        data-testid="preview-run"
      >
        {busy ? 'Running…' : 'Preview'}
      </button>
      {output ? (
        <div data-testid="preview-output">
          <ul>
            {Object.entries(output.components).map(([k, v]) => (
              <li key={k}>
                {k}: {v}
              </li>
            ))}
          </ul>
          <p data-testid="preview-elapsed">{output.elapsedMs} ms</p>
          {delta !== null ? (
            <p data-testid="preview-delta">
              Δ vs published: {delta >= 0 ? '+' : ''}
              {delta.toFixed(2)}
            </p>
          ) : null}
        </div>
      ) : null}
      <p style={{ color: '#777' }}>Synthetic deals are labeled and never persisted.</p>
    </aside>
  );
}
