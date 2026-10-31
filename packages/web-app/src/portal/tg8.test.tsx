import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import {
  SimulationExplorer,
  liabilityBands,
  meanDelta,
  type SimulationSummary,
} from './SimulationExplorer.tsx';

const scenarios: readonly SimulationSummary[] = [
  {
    simulationId: 's1',
    label: 'Baseline 2027',
    status: 'COMPLETED',
    iterations: 10_000,
    p50: 3_650_000,
    p95: 4_100_000,
    p99: 4_400_000,
    mean: 3_700_000,
    windfallPayees: [],
  },
  {
    simulationId: 's2',
    label: 'Accelerator x1.5',
    status: 'COMPLETED',
    iterations: 10_000,
    p50: 4_500_000,
    p95: 5_300_000,
    p99: 5_900_000,
    mean: 4_600_000,
    windfallPayees: ['p1', 'p7', 'p12'],
  },
];

describe('TG8-UI-1 simulation explorer (FR-1001 UI)', () => {
  it('percentile bands partition the distribution', () => {
    const bands = liabilityBands(50, 80, 100);
    expect(bands.length).toBe(3);
    expect(bands[0]!.label).toBe('≤ p50');
    expect(bands[2]!.to).toBe(100);
    const total = bands[1]!.to - bands[1]!.from;
    expect(total).toBeGreaterThan(0);
  });

  it('mean delta is a signed fraction of B', () => {
    expect(meanDelta(scenarios[1]!, scenarios[0]!)).toBeCloseTo(0.2432, 3);
    expect(meanDelta(scenarios[0]!, scenarios[0]!)).toBe(0);
  });

  it('renders scenario compare, distribution and windfall columns', () => {
    const { container } = render(<SimulationExplorer scenarios={scenarios} />);
    expect(screen.getAllByText('Baseline 2027').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Accelerator x1.5').length).toBeGreaterThan(0);
    expect(screen.getByRole('status').textContent).toContain('%');
    expect(screen.getAllByText((_, el) => (el?.textContent ?? '').includes('3 (p1, p7, p12)')).length).toBeGreaterThan(0);
    expect(screen.getByRole('figure', { name: /Liability distribution/ })).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Scenario B'), { target: { value: 's1' } });
    expect(screen.getByRole('status').textContent).toContain('(within noise)');
    // a11y: the explorer is axe-clean
    return axe(container).then((results) => {
      expect(results.violations).toHaveLength(0);
    });
  });

  it('empty state is announced', () => {
    render(<SimulationExplorer scenarios={[]} />);
    expect(screen.getByRole('status').textContent).toContain('No simulations yet');
  });
});
