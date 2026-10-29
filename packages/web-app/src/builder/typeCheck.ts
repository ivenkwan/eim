// Client-side mirror of the compiler type checker (FR-402.2): fast enough
// for keystroke-time inline underlines in the builder; the compiler's
// verdict is authoritative at publish (the UI hint never overrules it).

import {
  type FormulaToken,
  type OutputType,
  type PlanModel,
  STANDARD_FACTS,
  tokenizeFormula,
} from './planModel.ts';

export interface TypeIssue {
  readonly token: string;
  readonly message: string;
  readonly fix?: string | undefined;
}

const NUMERIC: ReadonlySet<OutputType> = new Set(['CURRENCY', 'PERCENT', 'DECIMAL']);

function combine(op: string, l: OutputType, r: OutputType): OutputType {
  if (!NUMERIC.has(l) || !NUMERIC.has(r)) {
    return 'BOOLEAN'; // sentinel for an already-invalid operand pair
  }
  if (op === '+' || op === '-') {
    return l === r ? l : 'DECIMAL';
  }
  if (op === '*') {
    if (l === 'CURRENCY' && r === 'CURRENCY') {
      return 'BOOLEAN'; // currency * currency is invalid
    }
    return l === 'CURRENCY' || r === 'CURRENCY'
      ? 'CURRENCY'
      : l === 'PERCENT' || r === 'PERCENT'
        ? 'PERCENT'
        : 'DECIMAL';
  }
  if (l === 'CURRENCY' && r === 'CURRENCY') {
    return 'DECIMAL';
  }
  return l === 'CURRENCY' || r === 'CURRENCY' ? 'CURRENCY' : 'DECIMAL';
}

/**
 * Infer the static type of a formula + surface token-level issues with fix
 * suggestions (e.g. cast currency/percent mixups, unknown refs).
 */
export function checkFormula(
  plan: PlanModel,
  componentName: string,
  formula: string,
): { type: OutputType | null; issues: readonly TypeIssue[] } {
  const self = plan.components.find((c) => c.name === componentName);
  const params = new Map<string, OutputType>(
    (self?.parameters ?? []).map((p) => [p.name, p.type] as const),
  );
  const components = new Map(
    plan.components.map((c) => [c.name, c.outputType] as const),
  );

  const issues: TypeIssue[] = [];
  const tokens: readonly FormulaToken[] = tokenizeFormula(formula);
  let type: OutputType | null = null;

  // value stack machine over the token stream (operators always binary,
  // comparisons produce BOOLEAN)
  const stack: OutputType[] = [];
  const ops: string[] = [];
  const apply = (op: string): void => {
    const r = stack.pop();
    const l = stack.pop();
    if (l === undefined || r === undefined) {
      return;
    }
    if (op === '<' || op === '>') {
      if (!NUMERIC.has(l) || !NUMERIC.has(r)) {
        issues.push({
          token: op,
          message: `comparison needs numeric operands, got ${l} and ${r}`,
          fix: 'wrap dates in days_between(...)',
        });
      }
      stack.push('BOOLEAN');
      return;
    }
    const combined = combine(op, l, r);
    if (combined === 'BOOLEAN' && (op === '*' || op === '+' || op === '-')) {
      issues.push({
        token: op,
        message: `operator '${op}' cannot apply ${l} to ${r}`,
        fix: l === 'DATE' || r === 'DATE' ? 'use days_between(date, date)' : undefined,
      });
      stack.push('DECIMAL');
      return;
    }
    stack.push(combined);
  };

  for (const t of tokens) {
    switch (t.kind) {
      case 'ref': {
        const refType = components.get(t.ref ?? '');
        if (refType === undefined) {
          issues.push({ token: t.text, message: `unknown component ${t.text}`, fix: 'pick a component from @ autocomplete' });
          stack.push('DECIMAL');
        } else {
          stack.push(refType);
        }
        break;
      }
      case 'fact': {
        const factType = params.get(t.fact ?? '') ?? STANDARD_FACTS[t.fact ?? ''];
        if (factType === undefined) {
          issues.push({ token: t.text, message: `unknown fact ${t.text}`, fix: 'declare it as a parameter or use $ autocomplete' });
          stack.push('DECIMAL');
        } else {
          stack.push(factType);
        }
        break;
      }
      case 'number':
        stack.push('DECIMAL');
        break;
      case 'operator':
        if (t.text === '(' || t.text === ',') {
          ops.push(t.text);
        } else if (t.text === ')') {
          while (ops.length > 0 && ops[ops.length - 1] !== '(') {
            apply(ops.pop() as string);
          }
          ops.pop();
        } else {
          ops.push(t.text);
        }
        break;
      case 'function':
        break;
      case 'unknown':
        issues.push({ token: t.text, message: `unexpected token '${t.text}'` });
        break;
    }
  }
  while (ops.length > 0 && ops[ops.length - 1] !== '(') {
    apply(ops.pop() as string);
  }
  type = stack[stack.length - 1] ?? null;

  if (type !== null && self && self.outputType !== 'BOOLEAN' && !NUMERIC.has(type)) {
    issues.push({
      token: formula,
      message: `formula type ${type} does not match declared output ${self.outputType}`,
      fix: 'compare values (e.g. >=) for a BOOLEAN output',
    });
  }
  return { type: NUMERIC.has(type as OutputType) || type === 'BOOLEAN' ? type : null, issues };
}
