// GENERATED FILE — DO NOT EDIT. Regenerate with: pnpm codegen

import { z } from 'zod';

export const CalcRunRequestSchema = z.strictObject({
  payoutPeriodId: z.string(),
  planVersionHash: z.string(),
  roundingMode: z.unknown().optional(),
  tenantId: z.string(),
});

export const HelloResponseSchema = z.strictObject({
  message: z.string(),
  service: z.string(),
  status: z.enum(['ok']),
});

export const PreviewOutputSchema = z.strictObject({
  components: z.strictObject({

  }),
  elapsedMs: z.number().int(),
  lines: z.array(z.strictObject({

  })),
  trace: z.array(z.strictObject({

  })),
});

export const PreviewPlanSchema = z.strictObject({
  components: z.array(z.strictObject({
    dependsOn: z.array(z.string()).optional(),
    formula: z.unknown().optional(),
    kind: z.enum(['FORMULA', 'TIER_TABLE', 'WINDOW', 'DRAW', 'CAP', 'CLAWBACK', 'ROLLUP']),
    name: z.string(),
    outputType: z.unknown().optional(),
    parameters: z.array(z.strictObject({
      name: z.string().optional(),
      value: z.unknown().optional(),
    })).optional(),
    protections: z.strictObject({
      capBasis: z.unknown().optional(),
      capScope: z.unknown().optional(),
      capValue: z.unknown().optional(),
      clawbackWindowDays: z.unknown().optional(),
      drawKind: z.unknown().optional(),
    }).optional(),
    tiers: z.strictObject({
      basis: z.enum(['AMOUNT', 'PERCENT']).optional(),
      brackets: z.array(z.strictObject({
        rate: z.string(),
        upTo: z.unknown().optional(),
      })).optional(),
      mode: z.enum(['MARGINAL', 'CLIFF']).optional(),
    }).optional(),
  })),
  planName: z.unknown().optional(),
});

export const PreviewRequestSchema = z.strictObject({
  ctx: z.strictObject({

  }).optional(),
  plan: PreviewPlanSchema,
  roundingMode: z.unknown().optional(),
  transactions: z.array(z.strictObject({
    amount: z.string(),
    date: z.string(),
  })),
});

export const ReplayRequestSchema = z.strictObject({
  lockedPeriodId: z.string(),
  openPeriodId: z.string(),
  planVersionHash: z.string(),
  tenantId: z.string(),
});

export const ReplayResultSchema = z.strictObject({
  diffs: z.number().int(),
  lines: z.array(z.strictObject({

  })),
});

export const RunFailureSchema = z.strictObject({
  error: z.string(),
  runId: z.string(),
});

export const RunStatsSchema = z.strictObject({
  durationMs: z.number().int(),
  error: z.unknown().optional(),
  ledgerEntries: z.number().int(),
  payees: z.number().int(),
  runId: z.string(),
  status: z.enum(['COMPLETED', 'FAILED']),
  transactions: z.number().int(),
  waves: z.number().int(),
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

export const BracketSchema = z.strictObject({
  rate: z.string(),
  upTo: z.unknown().optional(),
});

export const ParameterSchema = z.strictObject({
  name: z.string(),
  type: z.enum(['CURRENCY', 'PERCENT', 'DECIMAL', 'BOOLEAN', 'DATE']),
  value: z.unknown().optional(),
});

export const ProtectionsSchema = z.strictObject({
  capBasis: z.unknown().optional(),
  capScope: z.unknown().optional(),
  capValue: z.unknown().optional(),
  clawbackWindowDays: z.unknown().optional(),
  drawKind: z.unknown().optional(),
});

export const PublishRejectedSchema = z.strictObject({
  error: z.string(),
  problems: z.array(z.string()),
});

export const PublishResultSchema = z.strictObject({
  newlyPublished: z.boolean(),
  planName: z.string(),
  planVersionHash: z.string(),
  topologicalOrder: z.array(z.string()).optional(),
});

export const StoredArtifactsSchema = z.strictObject({
  dmn: z.string(),
  drl: z.string(),
  helperJava: z.string(),
  planName: z.string(),
  publishedAt: z.string(),
});

export const TierTableSchema = z.strictObject({
  basis: z.enum(['AMOUNT', 'PERCENT']),
  brackets: z.array(BracketSchema),
  mode: z.enum(['MARGINAL', 'CLIFF']),
});

export const ComponentSchema = z.strictObject({
  dependsOn: z.array(z.string()).optional(),
  formula: z.unknown().optional(),
  kind: z.enum(['FORMULA', 'TIER_TABLE', 'WINDOW', 'DRAW', 'CAP', 'CLAWBACK', 'ROLLUP']),
  name: z.string(),
  outputType: z.enum(['CURRENCY', 'PERCENT', 'DECIMAL', 'BOOLEAN', 'DATE']),
  parameters: z.array(ParameterSchema).optional(),
  protections: ProtectionsSchema.optional(),
  tiers: TierTableSchema.optional(),
});

export const PlanModelSchema = z.strictObject({
  components: z.array(ComponentSchema),
  planName: z.string().optional(),
});

export const ValidationReportSchema = z.strictObject({
  ok: z.boolean(),
  report: z.strictObject({
    canonicalHash: z.string().optional(),
    inferredTypes: z.strictObject({

    }).optional(),
    problems: z.array(z.string()),
    topologicalOrder: z.array(z.string()),
  }),
});
