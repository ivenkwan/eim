import { useId, useState } from 'react';
import type { Bracket, TierBasis, TierMode, TierTable } from './planModel.ts';

// Rate-table grid (FR-403): marginal/cliff toggle with a visual threshold
// explanation, contiguity enforcement + boundary healing, % or absolute
// basis, CSV tier import. Keyboard-first: Tab moves cell-to-cell, Enter
// commits a row.

export interface RateTableGridProps {
  readonly tiers: TierTable;
  readonly onChange: (tiers: TierTable) => void;
  readonly disabled?: boolean | undefined;
}

/** Boundary healing: keep brackets strictly ascending and contiguous. */
export function healBrackets(brackets: readonly Bracket[]): readonly Bracket[] {
  const editable = [...brackets];
  let prev = 0;
  for (let i = 0; i < editable.length; i++) {
    const b = editable[i];
    if (b === undefined || b.upTo === null) {
      continue; // catch-all stays last
    }
    const up = Number(b.upTo);
    if (!Number.isFinite(up) || up <= prev) {
      // heal: previous bound + 1 keeps ascending contiguity
      editable[i] = { ...b, upTo: String(prev + 1) };
    }
    const healed = editable[i];
    if (healed !== undefined && healed.upTo !== null) {
      prev = Number(healed.upTo);
    }
  }
  return editable;
}

/** Parse pasted CSV tiers: `upTo,rate` rows (catch-all: `,0.20`). */
export function parseTierCsv(csv: string): readonly Bracket[] {
  return csv
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.toLowerCase().startsWith('upto'))
    .map((line) => {
      const [upTo, rate] = line.split(',').map((c) => c.trim());
      return { upTo: upTo === '' || upTo === undefined ? null : upTo, rate: rate ?? '0' };
    });
}

export function RateTableGrid({ tiers, onChange, disabled }: RateTableGridProps) {
  const gridId = useId();
  const [csvOpen, setCsvOpen] = useState(false);
  const [csv, setCsv] = useState('');

  const modeExplanation =
    tiers.mode === 'MARGINAL'
      ? 'Marginal: each threshold span earns its own rate — payouts slice across tiers.'
      : 'Cliff: the rate of the tier the FINAL amount lands in applies to everything.';

  const setBracket = (index: number, next: Bracket): void => {
    const updated = tiers.brackets.map((b, i) => (i === index ? next : b));
    onChange({ ...tiers, brackets: healBrackets(updated) });
  };

  return (
    <section className="rate-table" aria-labelledby={gridId}>
      <h4 id={gridId}>Rate table</h4>
      <div role="radiogroup" aria-label="tier mode" style={{ display: 'flex', gap: 8 }}>
        {(['MARGINAL', 'CLIFF'] as const satisfies readonly TierMode[]).map((mode) => (
          <label key={mode}>
            <input
              type="radio"
              name={`${gridId}-mode`}
              checked={tiers.mode === mode}
              onChange={() => onChange({ ...tiers, mode })}
              disabled={disabled}
            />
            {mode === 'MARGINAL' ? 'Marginal' : 'Cliff'}
          </label>
        ))}
      </div>
      <p data-testid="mode-explanation" style={{ color: '#555' }}>
        {modeExplanation}
      </p>
      <table style={{ fontVariantNumeric: 'tabular-nums' }}>
        <thead>
          <tr>
            <th>Up to ({tiers.basis === 'AMOUNT' ? 'amount' : '% of basis'})</th>
            <th>Rate</th>
          </tr>
        </thead>
        <tbody>
          {tiers.brackets.map((b, i) => (
            <tr key={i}>
              <td>
                {b.upTo === null ? (
                  <em>catch-all</em>
                ) : (
                  <input
                    aria-label={`bracket ${i + 1} upTo`}
                    value={b.upTo}
                    disabled={disabled}
                    onChange={(e) => setBracket(i, { ...b, upTo: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        setBracket(i, { ...b, upTo: e.currentTarget.value });
                      }
                    }}
                  />
                )}
              </td>
              <td>
                <input
                  aria-label={`bracket ${i + 1} rate`}
                  value={b.rate}
                  disabled={disabled}
                  onChange={(e) => setBracket(i, { ...b, rate: e.target.value })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      setBracket(i, { ...b, rate: e.currentTarget.value });
                    }
                  }}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
        <label>
          basis:{' '}
          <select
            aria-label="tier basis"
            value={tiers.basis}
            disabled={disabled}
            onChange={(e) => onChange({ ...tiers, basis: e.target.value as TierBasis })}
          >
            <option value="AMOUNT">absolute</option>
            <option value="PERCENT">percent</option>
          </select>
        </label>
        <button
          type="button"
          disabled={disabled}
          onClick={() =>
            onChange({
              ...tiers,
              brackets: healBrackets([
                ...tiers.brackets.filter((b) => b.upTo !== null),
                { upTo: '50000', rate: '0.05' },
                ...tiers.brackets.filter((b) => b.upTo === null),
              ]),
            })
          }
        >
          Add tier
        </button>
        <button type="button" disabled={disabled} onClick={() => setCsvOpen((v) => !v)}>
          Import CSV
        </button>
      </div>
      {csvOpen ? (
        <div>
          <textarea
            aria-label="tier csv"
            rows={4}
            value={csv}
            placeholder={'upTo,rate\n100000,0.10\n150000,0.15\n,0.20'}
            onChange={(e) => setCsv(e.target.value)}
          />
          <button
            type="button"
            onClick={() => {
              onChange({ ...tiers, brackets: healBrackets(parseTierCsv(csv)) });
              setCsvOpen(false);
            }}
          >
            Apply
          </button>
        </div>
      ) : null}
    </section>
  );
}
