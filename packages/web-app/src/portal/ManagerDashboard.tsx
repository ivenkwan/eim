import { useMemo, useState } from 'react';

// Manager dashboard (TG7-UI-2, FR-706): team attainment, payouts and
// dispute status over the effective-dated downline — the as-of view is the
// same rollup_scope_asof the API serves (FR-302.1), so mid-period manager
// moves resolve exactly as the engine does at pay time.

export interface DownlineMember {
  readonly payeeId: string;
  readonly name: string;
  readonly quota: number;
  readonly attainment: number;
  readonly payout: number;
  readonly disputesOpen: number;
  readonly disputesBreached: number;
  readonly managerAsOf?: string;
}

export interface ManagerDashboardProps {
  readonly members: readonly DownlineMember[];
  readonly asOf: string;
}

export function teamAttainment(members: readonly DownlineMember[]): number {
  if (members.length === 0) return 0;
  const sum = members.reduce((s, m) => s + m.attainment, 0);
  return sum / members.length;
}

export function totalPayout(members: readonly DownlineMember[]): number {
  return members.reduce((s, m) => s + m.payout, 0);
}

export function TeamDisputeHealth(
  members: readonly DownlineMember[],
): 'CLEAR' | 'WATCH' | 'BREACHED' {
  const breached = members.reduce((s, m) => s + m.disputesBreached, 0);
  const open = members.reduce((s, m) => s + m.disputesOpen, 0);
  if (breached > 0) return 'BREACHED';
  return open > 0 ? 'WATCH' : 'CLEAR';
}

export function ManagerDashboard({ members, asOf }: ManagerDashboardProps) {
  const [sort, setSort] = useState<'attainment' | 'payout' | 'disputes'>('attainment');
  const sorted = useMemo(() => {
    const copy = [...members];
    switch (sort) {
      case 'attainment':
        copy.sort((a, b) => b.attainment - a.attainment);
        break;
      case 'payout':
        copy.sort((a, b) => b.payout - a.payout);
        break;
      case 'disputes':
        copy.sort(
          (a, b) => b.disputesBreached - a.disputesBreached || b.disputesOpen - a.disputesOpen,
        );
        break;
    }
    return copy;
  }, [members, sort]);

  const health = TeamDisputeHealth(members);

  return (
    <section aria-label={`Team dashboard as of ${asOf}`} className="manager-dashboard">
      <header>
        <h3>Team dashboard</h3>
        <p>
          Effective-dated downline as of <time>{asOf}</time> — mid-period
          manager moves resolve as of this date, matching pay-time rollups.
        </p>
      </header>
      <div className="kpis">
        <div className="kpi">
          <span className="kpi-label">Team attainment</span>
          <span className="kpi-value num">{teamAttainment(members).toFixed(0)}%</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">Team payout</span>
          <span className="kpi-value num">{totalPayout(members).toFixed(2)}</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">Dispute health</span>
          <span className={`kpi-value dispute-${health.toLowerCase()}`}>{health}</span>
        </div>
      </div>
      <div className="sort-controls">
        <label>
          Sort by{' '}
          <select
            value={sort}
            onChange={(e) => setSort(e.target.value as typeof sort)}
            aria-label="Sort members"
          >
            <option value="attainment">Attainment</option>
            <option value="payout">Payout</option>
            <option value="disputes">Disputes</option>
          </select>
        </label>
      </div>
      <table className="downline-table">
        <thead>
          <tr>
            <th scope="col">Rep</th>
            <th scope="col">Quota</th>
            <th scope="col">Attainment</th>
            <th scope="col">Payout</th>
            <th scope="col">Disputes (open/breached)</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((m) => (
            <tr key={m.payeeId}>
              <td>{m.name}</td>
              <td className="num">{m.quota.toLocaleString('en-US')}</td>
              <td>
                <div
                  role="progressbar"
                  aria-valuenow={Math.round(m.attainment)}
                  aria-valuemin={0}
                  aria-valuemax={200}
                  aria-label={`${m.name} attainment`}
                  className="attainment-bar"
                >
                  <span style={{ width: `${Math.min(100, m.attainment / 2)}%` }} />
                </div>
                <span className="num">{m.attainment.toFixed(0)}%</span>
              </td>
              <td className="num">{m.payout.toFixed(2)}</td>
              <td className="num">
                {m.disputesOpen}
                {m.disputesBreached > 0 ? ` / ${m.disputesBreached} breached` : ''}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
