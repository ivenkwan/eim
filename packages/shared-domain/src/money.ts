/**
 * Money — exact decimal arithmetic for the EIM platform (NFR-01).
 *
 * Stored as an integer `units` of arbitrary size (BigInt) plus a decimal
 * `scale` (value = units / 10^scale). IEEE-754 floats are never used; parsing
 * and math operate on strings and BigInts only, so every operation is exact
 * and deterministic across replays (NFR-03).
 */
import type { RoundingMode } from './rounding.ts';

export interface MoneyShape {
  readonly units: bigint;
  readonly scale: number;
}

const MONEY_RE = /^-?\d+(\.\d+)?$/;

function pow10(exp: number): bigint {
  let result = 1n;
  for (let i = 0; i < exp; i += 1) result *= 10n;
  return result;
}

function rescale(a: MoneyShape, targetScale: number): MoneyShape {
  if (targetScale === a.scale) return a;
  if (targetScale > a.scale) {
    return { units: a.units * pow10(targetScale - a.scale), scale: targetScale };
  }
  throw new Error(`rescale only widens scale (${a.scale} -> ${targetScale})`);
}

/** Round |units| at `targetScale` using the given mode; sign-symmetric. */
function roundMagnitude(units: bigint, scale: number, targetScale: number, mode: RoundingMode): MoneyShape {
  if (targetScale >= scale) {
    return { units: units * pow10(targetScale - scale), scale: targetScale };
  }
  const factor = pow10(scale - targetScale);
  const negative = units < 0n;
  const magnitude = negative ? -units : units;
  const q = magnitude / factor;
  const r = magnitude % factor;
  const half = factor / 2n; // factor = 10^k (k>=1), so half = 5*10^(k-1) exactly
  let rounded = q;
  if (r > half || (r === half && (mode === 'HALF_UP' || q % 2n === 1n))) {
    rounded = q + 1n;
  }
  return { units: negative ? -rounded : rounded, scale: targetScale };
}

function format(units: bigint, scale: number): string {
  const negative = units < 0n;
  const digits = (negative ? -units : units).toString().padStart(scale + 1, '0');
  const whole = digits.slice(0, digits.length - scale);
  const frac = scale === 0 ? '' : `.${digits.slice(digits.length - scale)}`;
  return `${negative ? '-' : ''}${whole}${frac}`;
}

export class Money implements MoneyShape {
  readonly units: bigint;
  readonly scale: number;

  private constructor(units: bigint, scale: number) {
    if (scale % 1 !== 0 || scale < 0 || scale > 18) {
      throw new Error(`scale out of range [0,18]: ${String(scale)}`);
    }
    this.units = units;
    this.scale = scale;
  }

  /** Parse an exact decimal string such as "-12345.6700". Rejects floats/exponents. */
  static fromString(input: string): Money {
    if (typeof input !== 'string' || !MONEY_RE.test(input)) {
      throw new Error(`invalid decimal string: ${String(input)}`);
    }
    const negative = input.startsWith('-');
    const unsigned = negative ? input.slice(1) : input;
    const [whole = '0', frac = ''] = unsigned.split('.');
    const units = BigInt((negative ? '-' : '') + whole + frac);
    return new Money(units, frac.length);
  }

  static zero(scale: number): Money {
    return new Money(0n, scale);
  }

  /** Widen to at least `targetScale` without changing value. */
  withScale(targetScale: number): Money {
    return new Money(rescale(this, targetScale).units, targetScale);
  }

  add(other: Money): Money {
    const scale = Math.max(this.scale, other.scale);
    const a = rescale(this, scale);
    const b = rescale(other, scale);
    return new Money(a.units + b.units, scale);
  }

  sub(other: Money): Money {
    return this.add(new Money(-other.units, other.scale));
  }

  /**
   * Multiply by an exact decimal rate string (e.g. '0.100000').
   * Result scale = this.scale + rate.scale; no rounding happens here (NFR-02).
   */
  multiplyRate(rate: string): Money {
    const r = Money.fromString(rate);
    return new Money(this.units * r.units, this.scale + r.scale);
  }

  /** Apply the single rounding step at `targetScale` (NFR-02: call exactly once). */
  round(mode: RoundingMode, targetScale: number): Money {
    const rounded = roundMagnitude(this.units, this.scale, targetScale, mode);
    return new Money(rounded.units, rounded.scale);
  }

  compareTo(other: Money): -1 | 0 | 1 {
    const scale = Math.max(this.scale, other.scale);
    const a = rescale(this, scale).units;
    const b = rescale(other, scale).units;
    if (a < b) return -1;
    if (a > b) return 1;
    return 0;
  }

  equals(other: Money): boolean {
    return this.compareTo(other) === 0;
  }

  isNegative(): boolean {
    return this.units < 0n;
  }

  isZero(): boolean {
    return this.units === 0n;
  }

  toString(): string {
    return format(this.units, this.scale);
  }
}
