import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button } from './Button.tsx';
import { palette, typography } from './tokens.ts';

describe('Button', () => {
  it('renders children and defaults to primary variant', () => {
    render(<Button>Save plan</Button>);
    const button = screen.getByRole('button', { name: 'Save plan' });
    expect(button.getAttribute('data-variant')).toBe('primary');
    expect(button.getAttribute('type')).toBe('button');
  });

  it('carries semantic variants used across the platform', () => {
    const { rerender } = render(<Button variant="clawback">Clawback</Button>);
    expect(screen.getByRole('button', { name: 'Clawback' }).getAttribute('data-variant')).toBe('clawback');
    rerender(<Button variant="locked">Locked</Button>);
    expect(screen.getByRole('button', { name: 'Locked' }).getAttribute('data-variant')).toBe('locked');
  });
});

describe('palette tokens (UX spec §3)', () => {
  it('exposes the colorblind-safe semantic palette', () => {
    expect(palette.base).toBe('#2563EB');
    expect(palette.accelerated).toBe('#059669');
    expect(palette.clawback).toBe('#E11D48');
    expect(palette.locked).toBe('#52525B');
  });

  it('numeric typography is tabular (NFR-121)', () => {
    expect(typography.numeric).toContain('tabular-nums');
  });
});
