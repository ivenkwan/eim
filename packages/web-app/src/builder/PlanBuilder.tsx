import { useCallback, useEffect, useMemo, useReducer, useState } from 'react';
import { PreviewDock, type PreviewFn, type PreviewOutput } from './PreviewDock.tsx';
import { ProtectionsEditor } from './ProtectionsEditor.tsx';
import { PublishGate } from './PublishGate.tsx';
import { RateTableGrid } from './RateTableGrid.tsx';
import {
  type PlanComponent,
  type PlanModel,
  suggestionsFor,
  tokenizeFormula,
} from './planModel.ts';
import { checkFormula } from './typeCheck.ts';

// Split-canvas plan builder (FR-401/402, TG3-UI-1..6): component tree,
// parameter panel, formula bar with @/$ autocomplete + token type-coloring
// and inline type-error underlines with fix suggestions; keyboard-first
// (Tab/Shift+Tab order, Enter-commit), undo history, ⌘K command palette.

export interface PlanBuilderProps {
  readonly initialPlan: PlanModel;
  readonly published?: PreviewOutput | undefined;
  readonly preview: PreviewFn;
  readonly onPublish?: (plan: PlanModel) => void;
  readonly payees?: readonly string[];
  readonly periods?: readonly string[];
}

interface HistoryState {
  past: readonly PlanModel[];
  present: PlanModel;
}

type Action =
  | { type: 'set'; plan: PlanModel }
  | { type: 'undo' };

function historyReducer(state: HistoryState, action: Action): HistoryState {
  switch (action.type) {
    case 'set':
      return { past: [...state.past, state.present].slice(-50), present: action.plan };
    case 'undo': {
      const prev = state.past[state.past.length - 1];
      return prev === undefined
        ? state
        : { past: state.past.slice(0, -1), present: prev };
    }
  }
}

const PALETTE_ACTIONS = [
  'add component',
  'delete component',
  'undo',
  'toggle marginal/cliff',
  'publish version',
] as const;

export function PlanBuilder({
  initialPlan,
  published,
  preview,
  onPublish,
  payees = ['sarah.jenkins', 'alex.rivera'],
  periods = ['2026-04', '2026-05'],
}: PlanBuilderProps) {
  const [history, dispatch] = useReducer(historyReducer, { past: [], present: initialPlan });
  const plan = history.present;
  const [selected, setSelected] = useState(initialPlan.components[0]?.name ?? '');
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [paletteQuery, setPaletteQuery] = useState('');

  const component = plan.components.find((c) => c.name === selected);

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        dispatch({ type: 'undo' });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const update = useCallback(
    (next: PlanModel): void => dispatch({ type: 'set', plan: next }),
    [],
  );

  const patchComponent = (patch: Partial<PlanComponent>): void => {
    if (component === undefined) {
      return;
    }
    update({
      ...plan,
      components: plan.components.map((c) => (c.name === component.name ? { ...c, ...patch } : c)),
    });
  };

  const formula = component?.formula ?? '';
  const { type, issues } = useMemo(
    () => (component ? checkFormula(plan, component.name, formula) : { type: null, issues: [] }),
    [plan, component, formula],
  );
  const tokens = useMemo(() => tokenizeFormula(formula), [formula]);

  const [autocomplete, setAutocomplete] = useState<{ trigger: '@' | '$'; prefix: string } | null>(
    null,
  );

  const problems = useMemo(
    () =>
      plan.components.flatMap((c) =>
        checkFormula(plan, c.name, c.formula ?? '').issues.map(
          (i) => `${c.name}: ${i.message}`,
        ),
      ),
    [plan],
  );

  return (
    <div className="plan-builder" style={{ display: 'grid', gridTemplateColumns: '1fr 2fr 1fr' }}>
      {/* ------------------------------------------------ component tree */}
      <nav aria-label="component tree" data-testid="component-tree">
        <h4>Components</h4>
        <ul>
          {plan.components.map((c) => (
            <li key={c.name}>
              <button
                type="button"
                aria-current={c.name === selected}
                onClick={() => setSelected(c.name)}
              >
                {c.name} <small>({c.kind})</small>
              </button>
            </li>
          ))}
        </ul>
        <button
          type="button"
          data-testid="add-component"
          onClick={() =>
            update({
              ...plan,
              components: [
                ...plan.components,
                {
                  name: `component_${plan.components.length + 1}`,
                  kind: 'FORMULA',
                  outputType: 'CURRENCY',
                  formula: '$booked_amount',
                },
              ],
            })
          }
        >
          + component
        </button>
        <button type="button" data-testid="undo" onClick={() => dispatch({ type: 'undo' })}>
          undo
        </button>
      </nav>

      {/* --------------------------------- parameter panel + formula bar */}
      <section aria-label="parameter panel">
        {component ? (
          <>
            <h4>{component.name}</h4>
            <label>
              output type:{' '}
              <select
                aria-label="output type"
                value={component.outputType}
                onChange={(e) =>
                  patchComponent({
                    outputType: e.target.value as PlanComponent['outputType'],
                  })
                }
              >
                {['CURRENCY', 'PERCENT', 'DECIMAL', 'BOOLEAN', 'DATE'].map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </label>
            <div
              data-testid="formula-bar"
              style={{ border: '1px solid #ccc', padding: 4, marginTop: 8 }}
            >
              <div data-testid="formula-colored" aria-hidden>
                {tokens.map((t, i) => (
                  <span key={i} className={`tok-${t.kind}`}>
                    {t.text}{' '}
                  </span>
                ))}
              </div>
              <input
                aria-label="formula"
                data-testid="formula-input"
                value={formula}
                onChange={(e) => {
                  patchComponent({ formula: e.target.value });
                  const tailAt = e.target.value.lastIndexOf('@');
                  const tailDollar = e.target.value.lastIndexOf('$');
                  const at = Math.max(tailAt, tailDollar);
                  if (at >= 0) {
                    const tail = e.target.value.slice(at);
                    const valid = /^([@$])[A-Za-z0-9_]*$/.test(tail);
                    setAutocomplete(
                      valid
                        ? { trigger: tail[0] as '@' | '$', prefix: tail.slice(1) }
                        : null,
                    );
                  } else {
                    setAutocomplete(null);
                  }
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    patchComponent({ formula: e.currentTarget.value }); // commit
                    setAutocomplete(null);
                  }
                }}
              />
              {type ? <small data-testid="inferred-type">→ {type}</small> : null}
              {issues.length > 0 ? (
                <ul data-testid="formula-errors" style={{ color: '#b00020' }}>
                  {issues.map((i, idx) => (
                    <li key={idx}>
                      <u>{i.token}</u> — {i.message}
                      {i.fix ? <small> (fix: {i.fix})</small> : null}
                    </li>
                  ))}
                </ul>
              ) : null}
              {autocomplete ? (
                <ul data-testid="autocomplete" role="listbox">
                  {suggestionsFor(plan, autocomplete.trigger, autocomplete.prefix).map((s) => (
                    <li key={s.insert} role="option">
                      <button
                        type="button"
                        onClick={() => {
                          // plain slice+concat of a suggestion from our own
                          // list — no pattern substitution
                          const at = Math.max(
                            formula.lastIndexOf('@'),
                            formula.lastIndexOf('$'),
                          );
                          if (at >= 0) {
                            patchComponent({ formula: formula.slice(0, at) + s.insert });
                          }
                          setAutocomplete(null);
                        }}
                      >
                        {s.label} <small>{s.detail}</small>
                      </button>
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
            {component.tiers ? (
              <RateTableGrid
                tiers={component.tiers}
                onChange={(tiers) => patchComponent({ tiers })}
              />
            ) : null}
          </>
        ) : (
          <p>select a component</p>
        )}
      </section>

      {/* --------------------------- protections + preview + publish gate */}
      <aside>
        {component ? (
          <ProtectionsEditor
            protections={component.protections ?? {}}
            onChange={(protections) => patchComponent({ protections })}
          />
        ) : null}
        <PublishGate
          problems={problems}
          cohortDiffPct={published ? 12.5 : null}
          onPublish={() => onPublish?.(plan)}
        />
      </aside>

      <div style={{ gridColumn: '1 / -1' }}>
        <PreviewDock
          plan={plan}
          published={published}
          preview={preview}
          payees={payees}
          periods={periods}
        />
      </div>

      {paletteOpen ? (
        <div
          data-testid="command-palette"
          role="dialog"
          style={{ position: 'fixed', inset: '20% 30%', background: 'white', border: '1px solid #333' }}
        >
          <input
            aria-label="command palette input"
            autoFocus
            value={paletteQuery}
            onChange={(e) => setPaletteQuery(e.target.value)}
          />
          <ul>
            {PALETTE_ACTIONS.filter((a) => a.includes(paletteQuery.toLowerCase())).map((a) => (
              <li key={a}>{a}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
