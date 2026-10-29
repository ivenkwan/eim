// TG0-TST-5: consumer contract tests — canned server payloads validated
// against SPEC-DERIVED zod schemas. If the spec changes without the client
// regenerating, or a server response drifts from the spec, these fail.
import { describe, expect, it } from 'vitest';
import { HelloResponseSchema, ConfigChangeAcceptedSchema, RunStatsSchema } from './index.ts';

describe('spec-derived schemas accept conforming payloads', () => {
  it('HelloResponse (core-api)', () => {
    const payload: unknown = { service: 'eim-core-api', status: 'ok', message: 'hello' };
    expect(HelloResponseSchema.parse(payload)).toEqual(payload);
  });

  it('ConfigChangeAccepted (core-api)', () => {
    const payload: unknown = { accepted: true, requestId: 'req-1' };
    expect(ConfigChangeAcceptedSchema.parse(payload)).toEqual(payload);
  });

  it('RunStats (calc-engine)', () => {
    const payload: unknown = {
      runId: 'run-9',
      status: 'COMPLETED',
      waves: 2,
      payees: 5,
      transactions: 120,
      ledgerEntries: 7,
      durationMs: 812,
      error: null,
    };
    expect(RunStatsSchema.parse(payload)).toEqual(payload);
  });
});

describe('spec-derived schemas reject non-conforming payloads', () => {
  it('unknown status enum value', () => {
    expect(() => HelloResponseSchema.parse({ service: 'x', status: 'meh', message: 'hi' })).toThrow();
  });

  it('missing required field', () => {
    expect(() => HelloResponseSchema.parse({ service: 'x', status: 'ok' })).toThrow();
  });

  it('wrong primitive type', () => {
    expect(() => ConfigChangeAcceptedSchema.parse({ accepted: 'yes', requestId: 'r' })).toThrow();
  });

  it('unknown fields are rejected (strict contract)', () => {
    expect(() =>
      ConfigChangeAcceptedSchema.parse({ accepted: true, requestId: 'r', extra: 5 }),
    ).toThrow();
  });
});
