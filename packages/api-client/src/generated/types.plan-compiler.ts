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
    "/api/v1/plans/validate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Type-check + cycle + tier checks without persisting (builder UI surface, FR-402.2/406.1) */
        post: operations["validatePlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/plans/publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Publish gate — compiles and appends the immutable artifact version (FR-406, NFR-62) */
        post: operations["publishPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/plans/{hash}/artifacts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Fetch the immutable artifact set for a plan version (read-only) */
        get: operations["getPlanArtifacts"];
        put?: never;
        post?: never;
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
        PlanModel: {
            planName?: string;
            components: components["schemas"]["Component"][];
        };
        Component: {
            /** @description lower_snake_case, unique per plan */
            name: string;
            /** @enum {string} */
            kind: "FORMULA" | "TIER_TABLE" | "WINDOW" | "DRAW" | "CAP" | "CLAWBACK" | "ROLLUP";
            /** @enum {string} */
            outputType: "CURRENCY" | "PERCENT" | "DECIMAL" | "BOOLEAN" | "DATE";
            /** @description Typed expression over $params/$facts and @component refs */
            formula?: string | null;
            parameters?: components["schemas"]["Parameter"][];
            tiers?: components["schemas"]["TierTable"];
            protections?: components["schemas"]["Protections"];
            dependsOn?: string[];
        };
        Parameter: {
            name: string;
            /** @enum {string} */
            type: "CURRENCY" | "PERCENT" | "DECIMAL" | "BOOLEAN" | "DATE";
            value?: string | null;
        };
        TierTable: {
            /** @enum {string} */
            mode: "MARGINAL" | "CLIFF";
            /** @enum {string} */
            basis: "AMOUNT" | "PERCENT";
            brackets: components["schemas"]["Bracket"][];
        };
        Bracket: {
            /** @description decimal upper bound; null = catch-all (must be last) */
            upTo?: string | null;
            /** @description decimal rate e.g. 0.10 */
            rate: string;
        };
        Protections: {
            /** @enum {string|null} */
            drawKind?: "RECOVERABLE" | "NON_RECOVERABLE" | null;
            /** @enum {string|null} */
            capScope?: "PERIOD" | "ANNUAL" | null;
            /** @enum {string|null} */
            capBasis?: "AMOUNT" | "PERCENT" | null;
            capValue?: string | null;
            clawbackWindowDays?: string | null;
        };
        ValidationReport: {
            ok: boolean;
            report: {
                problems: string[];
                topologicalOrder: string[];
                inferredTypes?: {
                    [key: string]: string;
                };
                canonicalHash?: string;
            };
        };
        PublishResult: {
            planVersionHash: string;
            planName: string;
            newlyPublished: boolean;
            topologicalOrder?: string[];
        };
        PublishRejected: {
            error: string;
            problems: string[];
        };
        StoredArtifacts: {
            planName: string;
            drl: string;
            /** @description //==FILE: marker-delimited source bundle */
            helperJava: string;
            dmn: string;
            publishedAt: string;
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
    validatePlan: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PlanModel"];
            };
        };
        responses: {
            /** @description Validation report (problems, topological order, inferred types, canonical hash) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ValidationReport"];
                };
            };
        };
    };
    publishPlan: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PlanModel"];
            };
        };
        responses: {
            /** @description Version registered */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublishResult"];
                };
            };
            /** @description Publish gate rejected the plan (type errors / cycles / tier gaps) */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublishRejected"];
                };
            };
        };
    };
    getPlanArtifacts: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                hash: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description DRL + helper bundle + DMN for the version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StoredArtifacts"];
                };
            };
            /** @description Unknown version */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
