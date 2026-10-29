import { useState } from 'react';

// Publish gate (FR-406.2/406.3): validation results (cycles, tier gaps,
// decimal safety), cohort payout diff, and a >10% high-variance warning
// that REQUIRES explicit acknowledgement before Publish unlocks.

export interface PublishGateProps {
  readonly problems: readonly string[];
  readonly cohortDiffPct: number | null;
  readonly onPublish: () => void;
  readonly disabled?: boolean;
}

export function PublishGate({ problems, cohortDiffPct, onPublish, disabled }: PublishGateProps) {
  const [acknowledged, setAcknowledged] = useState(false);
  const highVariance = cohortDiffPct !== null && Math.abs(cohortDiffPct) > 10;
  const blocked = problems.length > 0 || (highVariance && !acknowledged);

  return (
    <section className="publish-gate" aria-labelledby="publish-heading">
      <h4 id="publish-heading">Publish gate</h4>
      {problems.length > 0 ? (
        <ul data-testid="gate-problems" style={{ color: '#b00020' }}>
          {problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      ) : (
        <p data-testid="gate-problems" style={{ color: '#0a7d32' }}>
          validation clean
        </p>
      )}
      {cohortDiffPct !== null ? (
        <p data-testid="cohort-diff">
          cohort payout diff: {cohortDiffPct >= 0 ? '+' : ''}
          {cohortDiffPct.toFixed(1)}%
        </p>
      ) : null}
      {highVariance ? (
        <label data-testid="variance-warning" style={{ display: 'block', color: '#b06000' }}>
          <input
            type="checkbox"
            checked={acknowledged}
            onChange={(e) => setAcknowledged(e.target.checked)}
          />{' '}
          payout moves &gt;10% for some cohort members — acknowledge to proceed
        </label>
      ) : null}
      <button
        type="button"
        data-testid="publish-button"
        disabled={disabled || blocked}
        onClick={onPublish}
      >
        Publish version
      </button>
    </section>
  );
}
