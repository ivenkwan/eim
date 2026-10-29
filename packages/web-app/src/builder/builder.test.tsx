import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PlanBuilder } from './PlanBuilder.tsx';
import { PreviewDock, type PreviewOutput } from './PreviewDock.tsx';
import { ProtectionsEditor } from './ProtectionsEditor.tsx';
import { PublishGate } from './PublishGate.tsx';
import { RateTableGrid, healBrackets, parseTierCsv } from './RateTableGrid.tsx';
import type { TierTable } from './planModel.ts';
import { createPreviewCache, hashParts } from './cache.ts';
import { suggestionsFor, tokenizeFormula, type PlanModel } from './planModel.ts';
import { checkFormula } from './typeCheck.ts';

function inputValueById(id: string): string {
  const el: HTMLInputElement = screen.getByTestId(id);
  return el.value;
}

function buttonByTestId(id: string): HTMLButtonElement {
  const el = screen.getByTestId(id);
  return el as HTMLButtonElement;
}


const PLAN: PlanModel = {
  planName: 'Enterprise AE 2026',
  components: [
    {
      name: 'attainment',
      kind: 'FORMULA',
      outputType: 'CURRENCY',
      formula: '$window_to',
    },
    {
      name: 'commission',
      kind: 'TIER_TABLE',
      outputType: 'CURRENCY',
      tiers: {
        mode: 'MARGINAL',
        basis: 'AMOUNT',
        brackets: [
          { upTo: '100000', rate: '0.10' },
          { upTo: '150000', rate: '0.15' },
          { upTo: null, rate: '0.20' },
        ],
      },
      dependsOn: ['attainment'],
    },
  ],
};

const preview = vi.fn(() => {
  const output: PreviewOutput = ({
  components: { commission: '10500.00' },
  lines: [{ component: 'commission', entryType: 'CALCULATED', amount: '10500.00' }],
  elapsedMs: 7,
} as const);
  return Promise.resolve(output);
});

describe('formula tokenizer + autocomplete (FR-401)', () => {
  it('classifies @refs, $facts, numbers and operators', () => {
    const tokens = tokenizeFormula('$window_to * @commission + 0.1');
    expect(tokens.map((t) => t.kind)).toEqual(['fact', 'operator', 'ref', 'operator', 'number']);
  });

  it('suggests components for @ and facts for $ with prefixes', () => {
    expect(suggestionsFor(PLAN, '@', 'com').map((s) => s.label)).toEqual(['@commission']);
    const facts = suggestionsFor(PLAN, '$', 'window');
    expect(facts.map((s) => s.label)).toContain('$window_to');
  });
});

describe('inline type checking (FR-402.2)', () => {
  it('flags currency + date mismatches with a fix suggestion', () => {
    const { issues } = checkFormula(PLAN, 'attainment', '$booked_amount + $period_start');
    expect(issues.some((i) => i.message.includes('cannot apply'))).toBe(true);
    expect(issues.some((i) => i.fix === 'use days_between(date, date)')).toBe(true);
  });

  it('infers CURRENCY for the clean attainment formula', () => {
    const { type, issues } = checkFormula(PLAN, 'attainment', '$window_to');
    expect(type).toBe('CURRENCY');
    expect(issues).toHaveLength(0);
  });

  it('flags unknown refs and facts', () => {
    const { issues } = checkFormula(PLAN, 'attainment', '@nope * $also_nope');
    expect(issues.some((i) => i.message.includes('unknown component'))).toBe(true);
    expect(issues.some((i) => i.message.includes('unknown fact'))).toBe(true);
  });
});

describe('rate table grid (FR-403)', () => {
  it('heals overlapping brackets into ascending contiguous bounds', () => {
    const healed = healBrackets([
      { upTo: '50000', rate: '0.05' },
      { upTo: '40000', rate: '0.04' },
      { upTo: null, rate: '0.10' },
    ]);
    expect(healed.map((b) => b.upTo)).toEqual(['50000', '50001', null]);
  });

  it('parses CSV tiers including the catch-all row', () => {
    const brackets = parseTierCsv('upTo,rate\n100000,0.10\n150000,0.15\n,0.20');
    expect(brackets).toHaveLength(3);
    expect(brackets[2]?.upTo).toBeNull();
    expect(brackets[2]?.rate).toBe('0.20');
  });

  it('toggles marginal/cliff with a threshold explanation', () => {
    const onChange = vi.fn();
    render(
      <RateTableGrid
        tiers={{ mode: 'MARGINAL', basis: 'AMOUNT', brackets: [{ upTo: '100000', rate: '0.10' }, { upTo: null, rate: '0.20' }] }}
        onChange={onChange}
      />,
    );
    expect(screen.getByTestId('mode-explanation').textContent).toContain('Marginal');
    fireEvent.click(screen.getByLabelText('Cliff'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ mode: 'CLIFF' }));
  });
});

describe('preview dock (FR-407 / NFR-23)', () => {
  it('renders deal-by-deal outputs, delta vs published, and labels synthetic deals', async () => {
    const published: PreviewOutput = {
      components: { commission: '9800.00' },
      lines: [],
      elapsedMs: 9,
    };
    render(
      <PreviewDock
        plan={PLAN}
        published={published}
        preview={preview}
        payees={['sarah.jenkins']}
        periods={['2026-04']}
      />,
    );
    fireEvent.click(screen.getByTestId('preview-run'));
    await waitFor(() => expect(screen.getByTestId('preview-output')).toBeTruthy());
    expect(screen.getByTestId('preview-delta').textContent).toContain('+700.00');
    expect(screen.getByTestId('synthetic-label').textContent).toBe('synthetic');
    expect(preview).toHaveBeenCalledTimes(1);
  });
});

describe('publish gate (FR-406.2/406.3)', () => {
  it('blocks on validation problems', () => {
    render(<PublishGate problems={['cycle: a -> b -> a']} cohortDiffPct={2} onPublish={() => {}} />);
    expect(buttonByTestId('publish-button').disabled).toBe(true);
    expect(screen.getByTestId('gate-problems').textContent).toContain('cycle');
  });

  it('requires acknowledgement when cohort payout moves >10%', () => {
    render(<PublishGate problems={[]} cohortDiffPct={12.5} onPublish={() => {}} />);
    const button = buttonByTestId('publish-button');
    expect(button.disabled).toBe(true);
    fireEvent.click(screen.getByTestId('variance-warning').querySelector('input') as HTMLElement);
    expect(button.disabled).toBe(false);
  });
});

describe('builder shell (TG3-UI-1/6)', () => {
  it('colors tokens, shows inline errors, and supports undo + ⌘K palette', async () => {
    render(<PlanBuilder initialPlan={PLAN} preview={preview} />);
    expect(screen.getByTestId('component-tree').textContent).toContain('commission');

    // inline type error with underline on a bad formula
    const input = screen.getByTestId('formula-input');
    fireEvent.change(input, { target: { value: '$booked_amount + $period_start' } });
    expect(screen.getByTestId('formula-errors').textContent).toContain('cannot apply');

    // undo restores the previous formula
    fireEvent.click(screen.getByTestId('undo'));
    await waitFor(() =>
      expect(inputValueById('formula-input')).toBe('$window_to'),
    );

    // ⌘K opens the command palette
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    expect(screen.getByTestId('command-palette')).toBeTruthy();
  });
});

describe('dirty-flag preview cache (FR-509)', () => {
  it('hits only when (ast, inputs) both match and never gates correctness', () => {
    const cache = createPreviewCache(4);
    const [a1, i1] = hashParts(PLAN, { deals: [1] });
    const [a2, i2] = hashParts(PLAN, { deals: [1, 2] });
    const ast2 = hashParts({ ...PLAN, planName: 'draft-2' }, { deals: [1] })[0];
    cache.put(a1, i1, 'first');
    expect(cache.get(a1, i1)?.value).toBe('first');
    expect(cache.get(a1, i2)).toBeUndefined(); // inputs changed
    expect(cache.get(ast2, i1)).toBeUndefined(); // ast changed
    expect(a2).toBeTruthy(); // distinct inputs hash used above
    expect(cache.hits).toBe(1);
    expect(cache.misses).toBe(2);
  });
});

describe('protections editor (FR-405)', () => {
  it('maps enum selections onto the DSL attributes', () => {
    const onChange = vi.fn();
    render(<ProtectionsEditor protections={{}} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('draw kind'), { target: { value: 'RECOVERABLE' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ drawKind: 'RECOVERABLE' }));
    fireEvent.change(screen.getByLabelText('cap scope'), { target: { value: 'PERIOD' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ capScope: 'PERIOD' }));
    fireEvent.change(screen.getByLabelText('cap value'), { target: { value: '25000' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ capValue: '25000' }));
    fireEvent.change(screen.getByLabelText('clawback window days'), { target: { value: '90' } });
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ clawbackWindowDays: '90' }),
    );
  });
});

describe('builder autocomplete + palette (TG3-UI-1/6)', () => {
  it('offers @ suggestions and inserts them on click', () => {
    render(<PlanBuilder initialPlan={PLAN} preview={preview} />);
    const input = screen.getByTestId('formula-input');
    fireEvent.change(input, { target: { value: '@' } });
    expect(screen.getByTestId('autocomplete').textContent).toContain('@commission');
    const first = screen.getByTestId('autocomplete').querySelector('li button');
    fireEvent.click(first as HTMLElement);
    expect(inputValueById('formula-input')).toBe('@attainment');
  });

  it('filters the command palette as you type', () => {
    render(<PlanBuilder initialPlan={PLAN} preview={preview} />);
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    const paletteInput = screen.getByLabelText('command palette input');
    fireEvent.change(paletteInput, { target: { value: 'undo' } });
    expect(screen.getByTestId('command-palette').textContent).toContain('undo');
    expect(screen.getByTestId('command-palette').textContent).not.toContain('add component');
  });
});

describe('preview cache path (NFR-23)', () => {
  it('renders output from the preview call within the dock', async () => {
    render(
      <PreviewDock plan={PLAN} preview={preview} payees={['p']} periods={['2026-04']} />,
    );
    fireEvent.click(screen.getByTestId('preview-run'));
    await waitFor(() => expect(screen.getByTestId('preview-output')).toBeTruthy());
    expect(preview).toHaveBeenCalled();
  });
});

describe('rate grid CSV + add tier (FR-403)', () => {
  it('applies imported CSV tiers through the healing pass', () => {
    const onChange = vi.fn();
    render(
      <RateTableGrid
        tiers={{ mode: 'MARGINAL', basis: 'AMOUNT', brackets: [{ upTo: null, rate: '0.2' }] }}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByText('Import CSV'));
    fireEvent.change(screen.getByLabelText('tier csv'), {
      target: { value: '100000,0.10\n90000,0.05\n,0.20' },
    });
    fireEvent.click(screen.getByText('Apply'));
    const applied = onChange.mock.calls.at(-1)?.[0] as TierTable;
    expect(applied.brackets.map((b) => b.upTo)).toEqual(['100000', '100001', null]);
  });

  it('inserts a new tier before the catch-all and heals bounds', () => {
    const onChange = vi.fn();
    render(
      <RateTableGrid
        tiers={{ mode: 'MARGINAL', basis: 'AMOUNT', brackets: [{ upTo: '100000', rate: '0.1' }, { upTo: null, rate: '0.2' }] }}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByText('Add tier'));
    const applied = onChange.mock.calls.at(-1)?.[0] as TierTable;
    expect(applied.brackets).toHaveLength(3);
    expect(applied.brackets.at(-1)?.upTo).toBeNull();
  });
});
