// GENERATED FILE — DO NOT EDIT. Regenerate with: pnpm codegen

import { z } from 'zod';

export const CalcRunAcceptedSchema = z.strictObject({
  accepted: z.boolean(),
  runId: z.string(),
});

export const CalcRunRequestSchema = z.strictObject({
  payoutPeriodId: z.string(),
  planVersionHash: z.string(),
  tenantId: z.string(),
});

export const HelloResponseSchema = z.strictObject({
  message: z.string(),
  service: z.string(),
  status: z.enum(['ok']),
});

export const ConfigChangeAcceptedSchema = z.strictObject({
  accepted: z.boolean(),
  requestId: z.string(),
});

export const ConfigChangeRequestSchema = z.strictObject({
  entity: z.string(),
  payload: z.string().optional(),
  reason: z.string(),
});

export const ErrorResponseSchema = z.strictObject({
  error: z.string(),
});

export const MePayeeResponseSchema = z.strictObject({
  actor: z.string().optional(),
  correlation_id: z.string().optional(),
  payee: z.strictObject({

  }).optional(),
  tenant_id: z.string().optional(),
});

export const PeriodLockRequestSchema = z.strictObject({
  reason: z.string(),
});
