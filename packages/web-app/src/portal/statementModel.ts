// Seller statement model (FR-701/702): shapes mirror the core-api
// /api/v1/me/statement payloads; the trace drawer binds directly to the
// ledger's JSONB calculation_trace.

export interface StatementComponent {
  readonly component: string;
  readonly amount: string;
  readonly entries: number;
}

export interface StatementOverview {
  readonly period: string | null;
  readonly periodStatus: string;
  readonly totalPayout: string;
  readonly attainment: string;
  readonly nextTierHurdle: string | null;
  readonly components: readonly StatementComponent[];
}

export interface TraceSlice {
  readonly tierName: string;
  readonly sliceAmount: string;
  readonly rateApplied: string;
  readonly slicePayout: string;
}

export interface TraceStep {
  readonly component: string;
  readonly kind?: string;
  readonly value?: string;
  readonly slices?: readonly TraceSlice[];
  readonly [key: string]: unknown;
}

export interface LedgerTrace {
  readonly ledgerId: string;
  readonly component: string;
  readonly amount: string;
  readonly entryType: string;
  readonly trace: string;
  readonly transactionId: string | null;
}

/** Parsed calculation_trace with window position + slices for the drawer. */
export interface ParsedTrace {
  readonly windowFrom: string;
  readonly windowTo: string;
  readonly slices: readonly TraceSlice[];
  readonly steps: readonly TraceStep[];
}

export function parseTrace(raw: string): ParsedTrace {
  const obj = JSON.parse(raw) as {
    components?: Record<string, string>;
    steps?: readonly TraceStep[];
  };
  const components = obj.components ?? {};
  const steps = obj.steps ?? [];
  const tierStep = steps.find((s) => Array.isArray(s.slices) && s.slices.length > 0);
  return {
    windowFrom: components['window_from'] ?? '0',
    windowTo: components['window_to'] ?? components['attainment'] ?? '0',
    slices: tierStep?.slices ?? [],
    steps,
  };
}

/** Percent position of `to` between from and hurdle (thermometer). */
export function attainmentPercent(from: string, to: string, hurdle: string): number {
  const f = Number(from);
  const t = Number(to);
  const h = Number(hurdle);
  if (!Number.isFinite(f) || !Number.isFinite(t) || !Number.isFinite(h) || h <= f) {
    return 0;
  }
  return Math.max(0, Math.min(100, ((t - f) / (h - f)) * 100));
}

/** The four structured dispute categories (FR-704). */
export const DISPUTE_CATEGORIES = ['SOURCE_DATA', 'CREDIT_SPLIT', 'WINDOW_STATE', 'PLAN_LOGIC'] as const;
export type DisputeCategory = (typeof DISPUTE_CATEGORIES)[number];

/** Symptom → category pre-tag (mirrors the backward-chaining query). */
export function preTag(symptom: string): DisputeCategory {
  switch (symptom) {
    case 'late_arrival':
    case 'missing_source_row':
    case 'currency_mismatch':
      return 'SOURCE_DATA';
    case 'split_sum_off':
      return 'CREDIT_SPLIT';
    case 'window_boundary':
    case 'reset_cadence':
      return 'WINDOW_STATE';
    default:
      return 'PLAN_LOGIC';
  }
}

/** Negative amounts render with a leading − (minus) AND a sign marker —
 *  semantics never ride on color alone (NFR-122). */
export function signedAmount(amount: string): { text: string; negative: boolean } {
  const value = Number(amount);
  const negative = Number.isFinite(value) && value < 0;
  return { text: negative ? '−' + amount.replace('-', '') : amount, negative };
}
