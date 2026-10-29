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

export const ConnectorRunResultSchema = z.strictObject({
  accepted: z.number().int(),
  batchId: z.string(),
  quarantined: z.number().int(),
});

export const CsvUploadRequestSchema = z.strictObject({
  csv: z.string(),
  mapping: z.strictObject({

  }).optional(),
  objectName: z.string().optional(),
});

export const HubspotRunRequestSchema = z.strictObject({
  default_currency: z.string().optional(),
  mapping: z.strictObject({

  }).optional(),
  token: z.string(),
  watermark: z.unknown().optional(),
});

export const IngestionResultSchema = z.strictObject({
  accepted: z.boolean(),
  batchId: z.string(),
  ingested: z.number().int(),
  lateArriving: z.number().int(),
  quarantined: z.number().int(),
  total: z.number().int(),
  watermarkAfter: z.unknown(),
});

export const ManagerMoveRequestSchema = z.strictObject({
  effective_end: z.unknown().optional(),
  effective_start: z.string(),
  manager_payee_id: z.string(),
});

export const PayeeMutationResultSchema = z.strictObject({
  effectiveStart: z.unknown().optional(),
  manager: z.unknown().optional(),
  payeeId: z.string(),
});

export const PayeeUpsertRequestSchema = z.strictObject({
  effectiveEnd: z.unknown().optional(),
  effectiveStart: z.string().optional(),
  email: z.string().optional(),
  employeeNumber: z.string().optional(),
  fullName: z.string().optional(),
  managerPayeeId: z.unknown().optional(),
  payeeId: z.unknown().optional(),
  roleTitle: z.string().optional(),
});

export const QueryRunRequestSchema = z.strictObject({
  mapping: z.strictObject({

  }).optional(),
  object_name: z.string().optional(),
  query: z.string(),
});

export const RollupResponseSchema = z.strictObject({
  asOf: z.string(),
  count: z.number().int(),
  manager: z.string(),
  payees: z.array(z.string()),
});

export const SalesforceRunRequestSchema = z.strictObject({
  client_id: z.string().optional(),
  client_secret: z.string().optional(),
  mapping: z.strictObject({

  }).optional(),
  password: z.string().optional(),
  soql: z.string(),
  username: z.string().optional(),
  watermark: z.unknown().optional(),
});

export const WavefrontResponseSchema = z.strictObject({
  levels: z.array(z.strictObject({
    level: z.number().int(),
    payeeId: z.string(),
  })),
  maxLevel: z.number().int(),
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
