import { useMemo, useState } from 'react';

// Accounting console (TG7-UI-4, FR-906/907 UI): the deferred cost
// rollforward with its identity check, JE preview per template, and the
// book policy editor (Controller-facing). The identity row turns red via
// text markers (not color alone) when Beginning + Capitalized + TrueUps −
// Amortization − Impairments ≠ Ending (FR-906.1).

export interface RollforwardRow {
  readonly periodKey: string;
  readonly beginning: number;
  readonly capitalized: number;
  readonly trueUps: number;
  readonly amortization: number;
  readonly impairments: number;
  readonly ending: number;
  readonly current: number;
  readonly nonCurrent: number;
}

export function identityHolds(row: RollforwardRow): boolean {
  const computed =
    row.beginning + row.capitalized + row.trueUps - row.amortization - row.impairments;
  return Math.abs(computed - row.ending) < 0.00005;
}

export interface JeBatch {
  readonly batchId: string;
  readonly jeType: string;
  readonly status: 'PENDING' | 'EXPORTED' | 'SYNCED' | 'FAILED';
  readonly totalDr: string;
  readonly totalCr: string;
  readonly erpTarget: string;
}

export interface BookPolicy {
  readonly bookCode: string;
  readonly functionalCurrency: string;
  readonly amortizationConvention: 'EXACT_DAILY' | 'FULL_MONTH' | 'MID_MONTH' | 'PO_WEIGHTED';
  readonly expedientThresholdMonths: number;
  readonly fringeRate: string;
  readonly dmnVersion: number;
}

export function AccountingConsole({
  rollforward,
  batches,
  books,
  onPushRetry,
}: {
  readonly rollforward: readonly RollforwardRow[];
  readonly batches: readonly JeBatch[];
  readonly books: readonly BookPolicy[];
  readonly onPushRetry?: (batchId: string) => void;
}) {
  const [selectedBook, setSelectedBook] = useState(books[0]?.bookCode ?? '');
  const [editedPolicy, setEditedPolicy] = useState<BookPolicy | null>(null);

  const identityOk = useMemo(() => rollforward.every(identityHolds), [rollforward]);
  const failedBatches = useMemo(
    () => batches.filter((b) => b.status === 'FAILED'),
    [batches],
  );
  const book = books.find((b) => b.bookCode === selectedBook);

  return (
    <section aria-label="Accounting console" className="accounting-console">
      <header>
        <h3>Accounting console</h3>
        <label>
          Book{' '}
          <select
            value={selectedBook}
            onChange={(e) => setSelectedBook(e.target.value)}
            aria-label="Select accounting book"
          >
            {books.map((b) => (
              <option key={b.bookCode} value={b.bookCode}>
                {b.bookCode} ({b.functionalCurrency})
              </option>
            ))}
          </select>
        </label>
      </header>

      <h4>Deferred cost rollforward</h4>
      <p
        role="status"
        className={identityOk ? 'identity-ok' : 'identity-broken'}
      >
        {identityOk
          ? 'Accounting identity holds for every period (FR-906.1).'
          : 'IDENTITY FAILURE — one or more periods do not reconcile. Investigate before close.'}
      </p>
      <table className="rollforward-table">
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">Beginning</th>
            <th scope="col">Capitalized</th>
            <th scope="col">True-ups</th>
            <th scope="col">Amortization</th>
            <th scope="col">Impairments</th>
            <th scope="col">Ending</th>
            <th scope="col">Current / Non-current</th>
          </tr>
        </thead>
        <tbody>
          {rollforward.map((r) => (
            <tr key={r.periodKey}>
              <td>{r.periodKey}</td>
              <td className="num">{r.beginning.toFixed(2)}</td>
              <td className="num">{r.capitalized.toFixed(2)}</td>
              <td className="num">{r.trueUps.toFixed(2)}</td>
              <td className="num">({r.amortization.toFixed(2)})</td>
              <td className="num">({r.impairments.toFixed(2)})</td>
              <td className="num">{r.ending.toFixed(2)}</td>
              <td className="num">
                {r.current.toFixed(2)} / {r.nonCurrent.toFixed(2)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h4>Journal batches</h4>
      {failedBatches.length > 0 && (
        <p role="alert">
          {failedBatches.length} batch(es) FAILED — retry available.
        </p>
      )}
      <table className="je-table">
        <thead>
          <tr>
            <th scope="col">Type</th>
            <th scope="col">Status</th>
            <th scope="col">DR</th>
            <th scope="col">CR</th>
            <th scope="col">ERP</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {batches.map((b) => (
            <tr key={b.batchId}>
              <td>{b.jeType}</td>
              <td>
                <span className={`je-status-${b.status.toLowerCase()}`}>{b.status}</span>
                {b.status === 'FAILED' && ' (needs retry)'}
              </td>
              <td className="num">{b.totalDr}</td>
              <td className="num">{b.totalCr}</td>
              <td>{b.erpTarget}</td>
              <td>
                {b.status === 'FAILED' && onPushRetry && (
                  <button type="button" onClick={() => onPushRetry(b.batchId)}>
                    Retry push
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h4>Book policy</h4>
      {book && (
        <form
          className="policy-editor"
          onSubmit={(e) => e.preventDefault()}
          aria-label={`Policy for ${book.bookCode}`}
        >
          <label>
            Amortization convention{' '}
            <select
              value={editedPolicy?.amortizationConvention ?? book.amortizationConvention}
              onChange={(e) =>
                setEditedPolicy({
                  ...(editedPolicy ?? book),
                  amortizationConvention: e.target
                    .value as BookPolicy['amortizationConvention'],
                })
              }
            >
              <option value="EXACT_DAILY">Exact daily</option>
              <option value="FULL_MONTH">Full month</option>
              <option value="MID_MONTH">Mid month</option>
              <option value="PO_WEIGHTED">PO weighted</option>
            </select>
          </label>
          <label>
            Practical expedient threshold (months){' '}
            <input
              type="number"
              min={1}
              max={36}
              value={editedPolicy?.expedientThresholdMonths ?? book.expedientThresholdMonths}
              onChange={(e) =>
                setEditedPolicy({
                  ...(editedPolicy ?? book),
                  expedientThresholdMonths: Number(e.target.value) || 12,
                })
              }
            />
          </label>
          <label>
            Fringe rate{' '}
            <input
              type="text"
              value={editedPolicy?.fringeRate ?? book.fringeRate}
              onChange={(e) =>
                setEditedPolicy({ ...(editedPolicy ?? book), fringeRate: e.target.value })
              }
            />
          </label>
          <p>
            Classification decision table v{book.dmnVersion} — Controller edits are versioned
            and audited (FR-901.2/613).
          </p>
        </form>
      )}
    </section>
  );
}
