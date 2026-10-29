// GENERATED FILE — DO NOT EDIT. Regenerate with: pnpm codegen

export interface paths {
    "/api/v1/hello": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Liveness hello from the service (read-only) */
        get: operations["getHello"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/calc/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Execute a calculation run for a payout period against a published plan version (FR-503/507) */
        post: operations["requestCalculationRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/calc/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Synchronous sandbox preview for the builder (FR-407) — inline DRAFT plan + labeled synthetic deals, nothing persisted */
        post: operations["previewPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/calc/shadow-replay": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Bi-temporal shadow replay of a LOCKED period; emits PRIOR_PERIOD_TRUEUP into the open period (FR-508) */
        post: operations["shadowReplay"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        HelloResponse: {
            service: string;
            /** @enum {string} */
            status: "ok";
            message: string;
        };
        CalcRunRequest: {
            tenantId: string;
            payoutPeriodId: string;
            planVersionHash: string;
            /** @enum {string|null} */
            roundingMode?: "HALF_EVEN" | "HALF_UP" | null;
        };
        RunStats: {
            runId: string;
            /** @enum {string} */
            status: "COMPLETED" | "FAILED";
            waves: number;
            payees: number;
            transactions: number;
            ledgerEntries: number;
            durationMs: number;
            error?: string | null;
        };
        RunFailure: {
            error: string;
            runId: string;
        };
        PreviewRequest: {
            plan: components["schemas"]["PreviewPlan"];
            transactions: {
                date: string;
                amount: string;
            }[];
            ctx?: {
                [key: string]: string;
            };
            roundingMode?: string | null;
        };
        PreviewPlan: {
            planName?: string | null;
            components: {
                name: string;
                /** @enum {string} */
                kind: "FORMULA" | "TIER_TABLE" | "WINDOW" | "DRAW" | "CAP" | "CLAWBACK" | "ROLLUP";
                outputType?: string | null;
                formula?: string | null;
                parameters?: {
                    name?: string;
                    value?: string | null;
                }[];
                tiers?: {
                    /** @enum {string} */
                    mode?: "MARGINAL" | "CLIFF";
                    /** @enum {string} */
                    basis?: "AMOUNT" | "PERCENT";
                    brackets?: {
                        upTo?: string | null;
                        rate: string;
                    }[];
                };
                protections?: {
                    drawKind?: string | null;
                    capScope?: string | null;
                    capBasis?: string | null;
                    capValue?: string | null;
                    clawbackWindowDays?: string | null;
                };
                dependsOn?: string[];
            }[];
        };
        PreviewOutput: {
            components: {
                [key: string]: string;
            };
            lines: Record<string, never>[];
            trace: Record<string, never>[];
            elapsedMs: number;
        };
        ReplayRequest: {
            tenantId: string;
            lockedPeriodId: string;
            openPeriodId: string;
            planVersionHash: string;
        };
        ReplayResult: {
            diffs: number;
            lines: Record<string, never>[];
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getHello: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Service greeting */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HelloResponse"];
                };
            };
        };
    };
    requestCalculationRun: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CalcRunRequest"];
            };
        };
        responses: {
            /** @description Run executed; stats returned */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunStats"];
                };
            };
            /** @description Missing tenantId / payoutPeriodId / planVersionHash */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Run failed — atomic-or-clean, zero ledger rows */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunFailure"];
                };
            };
        };
    };
    previewPlan: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PreviewRequest"];
            };
        };
        responses: {
            /** @description Component values, ledger lines and the step trace with elapsedMs */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PreviewOutput"];
                };
            };
            /** @description Missing plan or transactions */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    shadowReplay: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReplayRequest"];
            };
        };
        responses: {
            /** @description Diff summary + emitted true-up lines */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReplayResult"];
                };
            };
            /** @description Missing identifiers */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Replay failed (e.g. period not LOCKED) */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
