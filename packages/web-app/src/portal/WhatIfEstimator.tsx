import { useMemo, useReducer } from 'react';

// What-if commission estimator (TG7-UI-1, FR-510): sellers pick open
// pipeline deals and adjust terms/bundles to see the marginal payout
// impact — payee-scoped sandbox only, nothing persists. Tier math mirrors
// the engine's marginal slicing (10/15/20% over 0–100k/100–150k/>150k).

export interface PipelineDeal {
  readonly id: string;
  readonly name: string;
  readonly amount: number;
  readonly stage: 'open' | 'best_case' | 'commit';
  readonly selected: boolean;
  readonly termMonths: number;
}

export interface EstimatorState {
  readonly deals: readonly PipelineDeal[];
}

type Action =
  | { readonly type: 'toggle'; readonly id: string }
  | { readonly type: 'term'; readonly id: string; readonly months: number };

const TIERS = [
  { upTo: 100_000, rate: 0.1 },
  { upTo: 150_000, rate: 0.15 },
  { upTo: Number.POSITIVE_INFINITY, rate: 0.2 },
] as const;

/** Marginal tier payout — same slicing as the engine (FR-502). */
export function tierPayout(amount: number): number {
  let remaining = amount;
  let prevCap = 0;
  let payout = 0;
  for (const t of TIERS) {
    if (remaining <= 0) break;
    const slice = Math.min(remaining, t.upTo - prevCap);
    payout += slice * t.rate;
    remaining -= slice;
    prevCap = t.upTo;
  }
  return payout;
}

/** Term-adjusted effective amount: longer commitments accelerate quotas. */
export function termAdjusted(deal: PipelineDeal): number {
  return deal.amount * (1 + Math.max(0, deal.termMonths - 12) * 0.01);
}

export function estimatorReducer(state: EstimatorState, action: Action): EstimatorState {
  switch (action.type) {
    case 'toggle':
      return {
        ...state,
        deals: state.deals.map((d) => (d.id === action.id ? { ...d, selected: !d.selected } : d)),
      };
    case 'term':
      return {
        ...state,
        deals: state.deals.map((d) =>
          d.id === action.id ? { ...d, termMonths: action.months } : d,
        ),
      };
  }
}

export function WhatIfEstimator({ deals }: { readonly deals: readonly PipelineDeal[] }) {
  const [state, dispatch] = useReducer(estimatorReducer, { deals });

  const { selectedPayout, baselinePayout, incremental } = useMemo(() => {
    const committed = state.deals
      .filter((d) => d.stage === 'commit')
      .reduce((s, d) => s + termAdjusted(d), 0);
    // committed deals are the baseline; selections add on top (a selected
    // commit deal is already in the baseline — no double count)
    const selected = state.deals
      .filter((d) => d.selected && d.stage !== 'commit')
      .reduce((s, d) => s + termAdjusted(d), 0);
    const base = tierPayout(committed);
    const withSel = tierPayout(committed + selected);
    return {
      baselinePayout: base,
      selectedPayout: withSel,
      incremental: withSel - base,
    };
  }, [state.deals]);

  return (
    <section aria-label="What-if estimator" className="estimator">
      <h3>What-if estimator</h3>
      <p className="estimator-note">
        Sandbox only — selections never persist or hit the ledger.
      </p>
      <table className="estimator-deals">
        <thead>
          <tr>
            <th scope="col">Select</th>
            <th scope="col">Deal</th>
            <th scope="col">Amount</th>
            <th scope="col">Stage</th>
            <th scope="col">Term (mo)</th>
          </tr>
        </thead>
        <tbody>
          {state.deals.map((d) => (
            <tr key={d.id}>
              <td>
                <input
                  type="checkbox"
                  aria-label={`Include ${d.name}`}
                  checked={d.selected}
                  onChange={() => dispatch({ type: 'toggle', id: d.id })}
                />
              </td>
              <td>{d.name}</td>
              <td className="num">{d.amount.toLocaleString('en-US')}</td>
              <td>{d.stage}</td>
              <td>
                <input
                  type="number"
                  min={6}
                  max={36}
                  value={d.termMonths}
                  aria-label={`Term months for ${d.name}`}
                  onChange={(e) =>
                    dispatch({ type: 'term', id: d.id, months: Number(e.target.value) || 12 })
                  }
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="estimator-results">
        <div>
          <dt>Committed baseline</dt>
          <dd className="num">{baselinePayout.toFixed(2)}</dd>
        </div>
        <div>
          <dt>With selections</dt>
          <dd className="num">{selectedPayout.toFixed(2)}</dd>
        </div>
        <div>
          <dt>Incremental payout</dt>
          <dd className="num">{incremental.toFixed(2)}</dd>
        </div>
      </dl>
    </section>
  );
}
