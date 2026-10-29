// Plan DSL model for the builder (FR-401/402): mirrors the plan-compiler
// schema; the formula tokenizer powers autocomplete + token coloring and
// the client type checker drives inline underlines with fix suggestions.

export type OutputType = 'CURRENCY' | 'PERCENT' | 'DECIMAL' | 'BOOLEAN' | 'DATE';
export type ComponentKind = 'FORMULA' | 'TIER_TABLE' | 'WINDOW' | 'DRAW' | 'CAP' | 'CLAWBACK' | 'ROLLUP';
export type TierMode = 'MARGINAL' | 'CLIFF';
export type TierBasis = 'AMOUNT' | 'PERCENT';

export interface Bracket {
  /** null = catch-all (must be last) */
  readonly upTo: string | null;
  readonly rate: string;
}

export interface TierTable {
  readonly mode: TierMode;
  readonly basis: TierBasis;
  readonly brackets: readonly Bracket[];
}

export interface Protections {
  readonly drawKind?: 'RECOVERABLE' | 'NON_RECOVERABLE' | undefined;
  readonly capScope?: 'PERIOD' | 'ANNUAL' | undefined;
  readonly capBasis?: 'AMOUNT' | 'PERCENT' | undefined;
  readonly capValue?: string | undefined;
  readonly clawbackWindowDays?: string | undefined;
}

export interface PlanComponent {
  readonly name: string;
  readonly kind: ComponentKind;
  readonly outputType: OutputType;
  readonly formula?: string;
  readonly parameters?: readonly { name: string; type: OutputType; value: string }[];
  readonly tiers?: TierTable;
  readonly protections?: Protections;
  readonly dependsOn?: readonly string[];
}

export interface PlanModel {
  readonly planName: string;
  readonly components: readonly PlanComponent[];
}

/** Standard facts every formula may reference (mirrors the compiler). */
export const STANDARD_FACTS: Readonly<Record<string, OutputType>> = {
  booked_amount: 'CURRENCY',
  window_from: 'CURRENCY',
  window_to: 'CURRENCY',
  transaction_count: 'DECIMAL',
  attainment_quota: 'PERCENT',
  active_days: 'DECIMAL',
  period_days: 'DECIMAL',
  period_start: 'DATE',
  period_end: 'DATE',
};

export type TokenKind = 'ref' | 'fact' | 'number' | 'operator' | 'function' | 'unknown';

export interface FormulaToken {
  readonly text: string;
  readonly kind: TokenKind;
  /** referenced component name for @ tokens */
  readonly ref?: string;
  /** referenced fact/param name for $ tokens */
  readonly fact?: string;
}

/** Tokenize a formula for coloring + autocomplete (no evaluation here). */
export function tokenizeFormula(formula: string): readonly FormulaToken[] {
  const tokens: FormulaToken[] = [];
  const re = /(@[A-Za-z_][A-Za-z0-9_]*|\$[A-Za-z_][A-Za-z0-9_]*|\d+(?:\.\d+)?|[A-Za-z_][A-Za-z0-9_]*|[+\-*/(),<>]|[^\s])/g;
  for (const m of formula.matchAll(re)) {
    const text = m[0];
    if (text.startsWith('@')) {
      tokens.push({ text, kind: 'ref', ref: text.slice(1) });
    } else if (text.startsWith('$')) {
      tokens.push({ text, kind: 'fact', fact: text.slice(1) });
    } else if (/^\d/.test(text)) {
      tokens.push({ text, kind: 'number' });
    } else if (/^[A-Za-z_]/.test(text)) {
      tokens.push({ text, kind: 'function' });
    } else if (/[+\-*/(),<>]/.test(text)) {
      tokens.push({ text, kind: 'operator' });
    } else if (!/\s/.test(text)) {
      tokens.push({ text, kind: 'unknown' });
    }
  }
  return tokens;
}

export interface Suggestion {
  readonly insert: string;
  readonly label: string;
  readonly detail: string;
}

/** Autocomplete suggestions at `@`/`$` trigger positions (FR-401). */
export function suggestionsFor(plan: PlanModel, trigger: '@' | '$', prefix: string): readonly Suggestion[] {
  if (trigger === '@') {
    return plan.components
      .map((c) => ({ insert: '@' + c.name, label: '@' + c.name, detail: `${c.kind} → ${c.outputType}` }))
      .filter((s) => s.label.startsWith('@' + prefix));
  }
  return Object.entries(STANDARD_FACTS)
    .map(([name, type]) => ({ insert: '$' + name, label: '$' + name, detail: type }))
    .filter((s) => s.label.startsWith('$' + prefix));
}
