import { describe, expect, it } from 'vitest';
import { Money } from './money.ts';
import { isRoundingMode, type RoundingMode } from './rounding.ts';

describe('Money.fromString', () => {
  it('parses exact decimal strings', () => {
    expect(Money.fromString('0').toString()).toBe('0');
    expect(Money.fromString('123.45').toString()).toBe('123.45');
    expect(Money.fromString('-2.35').toString()).toBe('-2.35');
    expect(Money.fromString('10000.0000').toString()).toBe('10000.0000');
    expect(Money.fromString('0.100000').toString()).toBe('0.100000');
  });

  it('preserves trailing scale', () => {
    const m = Money.fromString('7.5000');
    expect(m.scale).toBe(4);
    expect(m.units).toBe(75000n);
  });

  it('rejects floats-as-strings, exponents and garbage (NFR-01 guardrail)', () => {
    expect(() => Money.fromString('1.2.3')).toThrow();
    expect(() => Money.fromString('1e5')).toThrow();
    expect(() => Money.fromString('abc')).toThrow();
    expect(() => Money.fromString('')).toThrow();
    expect(() => Money.fromString(' 1.00 ')).toThrow();
    // @ts-expect-error -- runtime guard against float input
    expect(() => Money.fromString(0.1)).toThrow();
  });
});

describe('Money arithmetic', () => {
  it('adds exactly where floats would drift', () => {
    const sum = Money.fromString('0.1').add(Money.fromString('0.2'));
    expect(sum.equals(Money.fromString('0.3'))).toBe(true);
    expect(sum.toString()).toBe('0.3');
  });

  it('aligns scales before adding', () => {
    const sum = Money.fromString('1.5').add(Money.fromString('2.25'));
    expect(sum.toString()).toBe('3.75');
    expect(sum.scale).toBe(2);
  });

  it('subtracts and formats negatives', () => {
    const diff = Money.fromString('1.00').sub(Money.fromString('3.35'));
    expect(diff.toString()).toBe('-2.35');
    expect(diff.isNegative()).toBe(true);
  });

  it('multiplies by an exact rate without rounding', () => {
    const product = Money.fromString('10000.0000').multiplyRate('0.100000');
    expect(product.toString()).toBe('1000.0000000000');
    expect(product.scale).toBe(10);
  });

  it('compares across scales', () => {
    expect(Money.fromString('0.3').compareTo(Money.fromString('0.30'))).toBe(0);
    expect(Money.fromString('-1').compareTo(Money.fromString('0'))).toBe(-1);
    expect(Money.fromString('2').compareTo(Money.fromString('1.9999'))).toBe(1);
  });

  it('withScale widens and refuses to narrow', () => {
    expect(Money.fromString('2.3').withScale(4).toString()).toBe('2.3000');
    expect(() => Money.fromString('2.3000').withScale(1)).toThrow();
  });

  it('zero() and isZero()', () => {
    expect(Money.zero(2).isZero()).toBe(true);
    expect(Money.zero(2).toString()).toBe('0.00');
  });
});

describe('Money.round (NFR-02: single rounding step)', () => {
  const cases: readonly (readonly [string, RoundingMode, string])[] = [
    // input, mode, expected at 2dp
    ['2.345', 'HALF_EVEN', '2.34'],
    ['2.355', 'HALF_EVEN', '2.36'],
    ['-2.345', 'HALF_EVEN', '-2.34'],
    ['-2.355', 'HALF_EVEN', '-2.36'],
    ['2.345', 'HALF_UP', '2.35'],
    ['-2.345', 'HALF_UP', '-2.35'],
    ['2.344', 'HALF_UP', '2.34'],
    ['1000.0000000000', 'HALF_EVEN', '1000.00'],
  ];
  it.each(cases)('rounds %s with %s -> %s', (input, mode, expected) => {
    expect(Money.fromString(input).round(mode, 2).toString()).toBe(expected);
  });

  it('widens when target scale exceeds current scale', () => {
    expect(Money.fromString('2.3').round('HALF_EVEN', 4).toString()).toBe('2.3000');
  });
});

describe('rounding modes', () => {
  it('validates mode strings', () => {
    expect(isRoundingMode('HALF_EVEN')).toBe(true);
    expect(isRoundingMode('HALF_UP')).toBe(true);
    expect(isRoundingMode('CEILING')).toBe(false);
    expect(isRoundingMode('')).toBe(false);
  });
});
