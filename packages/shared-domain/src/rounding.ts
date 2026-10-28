/**
 * Rounding policy (NFR-02): rounding is configurable and applied exactly once,
 * at the final step. HALF_EVEN (Banker's) is the platform default.
 */
export const ROUNDING_MODES = ['HALF_EVEN', 'HALF_UP'] as const;

export type RoundingMode = (typeof ROUNDING_MODES)[number];

export function isRoundingMode(value: string): value is RoundingMode {
  return (ROUNDING_MODES as readonly string[]).includes(value);
}
