import { useEffect, useMemo, useRef, useState } from 'react';

// CRM-embedded statement + estimator (TG7-UI-3, FR-707): an iframe/tab
// widget the CRM hosts. PostMessage bridge: the CRM parent posts
// { type: 'eim:context', tenantId, payeeId, token } after mount; the embed
// acknowledges, then serves a compact statement summary plus the what-if
// estimator link-out. Origin-checked — messages from any other origin are
// dropped.

export interface CrmContext {
  readonly tenantId: string;
  readonly payeeId: string;
  readonly token: string;
}

export interface StatementSummary {
  readonly periodLabel: string;
  readonly totalPayout: string;
  readonly components: readonly { readonly name: string; readonly amount: string }[];
}

type BridgeState =
  | { readonly kind: 'waiting' }
  | { readonly kind: 'ready'; readonly ctx: CrmContext }
  | { readonly kind: 'rejected'; readonly reason: string };

const ACK = 'eim:context:ack' as const;

export function CrmEmbed({
  allowedOrigins,
  summary,
}: {
  readonly allowedOrigins: readonly string[];
  readonly summary: StatementSummary;
}) {
  const [state, setState] = useState<BridgeState>({ kind: 'waiting' });
  const parentWindow = useRef<Window | null>(null);

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (!allowedOrigins.includes(event.origin)) {
        return; // origin check — drop everything else (FR-707.1)
      }
      const data = event.data as { type?: string } | null;
      if (data?.type === 'eim:context') {
        const ctx = event.data as CrmContext & { type: string };
        parentWindow.current = event.source as Window | null;
        setState({ kind: 'ready', ctx: { tenantId: ctx.tenantId, payeeId: ctx.payeeId, token: '***' } });
        parentWindow.current?.postMessage({ type: ACK }, event.origin);
      }
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [allowedOrigins]);

  const badge = useMemo(() => {
    switch (state.kind) {
      case 'waiting':
        return 'Connecting to CRM…';
      case 'rejected':
        return `Embed rejected: ${state.reason}`;
      case 'ready':
        return `Connected · payee ${state.ctx.payeeId.slice(0, 8)}…`;
    }
  }, [state]);

  return (
    <section aria-label="EIM statement embed" className="crm-embed">
      <header>
        <span className="embed-badge">{badge}</span>
      </header>
      {state.kind === 'ready' ? (
        <div className="embed-summary">
          <h4>Statement · {summary.periodLabel}</h4>
          <p className="num embed-total">{summary.totalPayout}</p>
          <ul>
            {summary.components.map((c) => (
              <li key={c.name}>
                {c.name}: <span className="num">{c.amount}</span>
              </li>
            ))}
          </ul>
          <a
            href="/portal/statement?embed=1"
            target="_blank"
            rel="noreferrer"
            className="embed-open"
          >
            Open full statement + what-if estimator
          </a>
        </div>
      ) : (
        <p role="status">{badge}</p>
      )}
    </section>
  );
}
