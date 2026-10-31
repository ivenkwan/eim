import { useMemo, useState } from 'react';

// Simulation explorer (TG8-UI-1, FR-1001 UI): scenario compare across two
// simulation runs and the liability distribution view — the percentile
// bands and per-payee windfall table. All numbers come from the
// simulation_run/simulation_payee_outcome payloads; the view computes
// nothing financial.

export interface SimulationSummary {
  readonly simulationId: string;
  readonly label: string;
  readonly status: string;
  readonly iterations: number;
  readonly p50: number;
  readonly p95: number;
  readonly p99: number;
  readonly mean: number;
  readonly windfallPayees: readonly string[];
}

export interface LiabilityBand {
  readonly label: string;
  readonly from: number;
  readonly to: number;
}

export function liabilityBands(p50: number, p95: number, p99: number): readonly LiabilityBand[] {
  const band = (label: string, from: number, to: number): LiabilityBand =>
    ({ label, from: pct(from, p99), to: pct(to, p99), });
  return [
    band('≤ p50', 0, p50),
    band('p50–p95', p50, p95),
    band('p95–p99', p95, p99),
  ];
  function pct(v: number, max: number): number {
    return Math.round((v / max) * 100);
  }
}

export function meanDelta(a: SimulationSummary, b: SimulationSummary): number {
  if (b.mean === 0) return 0;
  return (a.mean - b.mean) / b.mean;
}

export function SimulationExplorer({
  scenarios,
}: {
  readonly scenarios: readonly SimulationSummary[];
}) {
  const [selected, setSelected] = useState<[string, string]>([
    scenarios[0]?.simulationId ?? '',
    scenarios[1]?.simulationId ?? '',
  ]);
  const a = scenarios.find((s) => s.simulationId === selected[0]);
  const b = scenarios.find((s) => s.simulationId === selected[1]);
  const delta = useMemo(
    () => (a && b ? meanDelta(a, b) : 0),
    [a, b],
  );

  return (
    <section aria-label="Simulation explorer" className="sim-explorer">
      <h3>Monte Carlo scenarios</h3>
      {scenarios.length === 0 && <p role="status">No simulations yet — run one from the plan builder.</p>}
      {scenarios.length > 0 && (
        <>
          <div className="scenario-pickers">
            <label>
              Scenario A{' '}
              <select
                value={selected[0]}
                aria-label="Scenario A"
                onChange={(e) => setSelected([e.target.value, selected[1]])}
              >
                {scenarios.map((s) => (
                  <option key={s.simulationId} value={s.simulationId}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Scenario B{' '}
              <select
                value={selected[1]}
                aria-label="Scenario B"
                onChange={(e) => setSelected([selected[0], e.target.value])}
              >
                {scenarios.map((s) => (
                  <option key={s.simulationId} value={s.simulationId}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            {a && b && (
              <p role="status" className={delta > 0.1 ? 'delta-warn' : 'delta-ok'}>
                A vs B mean liability: {delta >= 0 ? '+' : ''}
                {(delta * 100).toFixed(1)}%{' '}
                {Math.abs(delta) > 0.1 ? '(material change)' : '(within noise)'}
              </p>
            )}
          </div>
          <table className="sim-table">
            <thead>
              <tr>
                <th scope="col">Scenario</th>
                <th scope="col">Iterations</th>
                <th scope="col">p50</th>
                <th scope="col">p95</th>
                <th scope="col">p99</th>
                <th scope="col">Mean</th>
                <th scope="col">Windfall payees</th>
              </tr>
            </thead>
            <tbody>
              {scenarios.map((s) => (
                <tr key={s.simulationId}>
                  <td>{s.label}</td>
                  <td className="num">{s.iterations.toLocaleString('en-US')}</td>
                  <td className="num">{s.p50.toFixed(0)}</td>
                  <td className="num">{s.p95.toFixed(0)}</td>
                  <td className="num">{s.p99.toFixed(0)}</td>
                  <td className="num">{s.mean.toFixed(0)}</td>
                  <td>
                    {s.windfallPayees.length === 0
                      ? 'none'
                      : `${s.windfallPayees.length} (${s.windfallPayees.slice(0, 3).join(', ')}${s.windfallPayees.length > 3 ? '…' : ''})`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {a && (
            <figure aria-label={`Liability distribution for ${a.label}`}>
              <figcaption>
                Liability distribution — {a.label} (percentile bands, % of p99)
              </figcaption>
              <div className="liability-bars">
                {liabilityBands(a.p50, a.p95, a.p99).map((band) => (
                  <div key={band.label} className="liability-band">
                    <span className="band-label">{band.label}</span>
                    <div
                      role="img"
                      aria-label={`${band.label}: ${band.to - band.from}% of p99`}
                      className="band-bar"
                      style={{ width: `${band.to - band.from}%` }}
                    />
                  </div>
                ))}
              </div>
            </figure>
          )}
        </>
      )}
    </section>
  );
}
