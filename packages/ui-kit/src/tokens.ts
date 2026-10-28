/**
 * Design tokens (UX wireframe spec §3): colorblind-safe semantic palette —
 * negative amounts always carry an explicit minus glyph, never red text
 * alone (NFR-122).
 */
export const palette = {
  base: '#2563EB', // slate blue — base tier / normal state
  accelerated: '#059669', // emerald — positive delta (with ^ chevron)
  clawback: '#E11D48', // rose — clawback / draw recovery (with - sign)
  locked: '#52525B', // zinc — locked / immutable periods
  surface: '#FFFFFF',
  textPrimary: '#18181B',
} as const;

export type PaletteKey = keyof typeof palette;

export const typography = {
  numeric: 'font-variant-numeric: tabular-nums',
} as const;
