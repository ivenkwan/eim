import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App.tsx';

describe('App', () => {
  it('renders the canonical FR-502.1 three-slice breakdown and total', () => {
    render(<App />);
    expect(screen.getByText('Tier 1 (0-100%)')).toBeTruthy();
    expect(screen.getByText('Tier 2 (100-150%)')).toBeTruthy();
    expect(screen.getByText('Tier 3 (>150%)')).toBeTruthy();
    expect(screen.getByTestId('total-payout').textContent).toBe('10500.00');
  });

  it('renders a single-slice view when the deal stays within one tier', () => {
    render(<App attainmentBefore="10000" attainmentAfter="20000" />);
    expect(screen.getByTestId('total-payout').textContent).toBe('1000.00');
  });
});
