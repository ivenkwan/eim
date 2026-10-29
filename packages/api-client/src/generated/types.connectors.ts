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
    "/api/v1/ingestion/csv": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Upload a CSV batch for ingestion with per-row quarantine (FR-201.1/201.5, FR-203) */
        post: operations["uploadCsv"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ingestion/salesforce/run": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Pull an object from Salesforce via OAuth password grant and ingest it (FR-202.1) */
        post: operations["runSalesforceSync"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ingestion/hubspot/run": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Pull HubSpot CRM v3 deals with a private-app token and ingest them (FR-201.1) */
        post: operations["runHubspotSync"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ingestion/query/run": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Ingest from a read-only SQL query (Snowflake/warehouse posture, FR-202.3) */
        post: operations["runQueryConnector"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/org/payee": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Create a payee with cycle validation and graph projection (FR-302.2) */
        post: operations["createPayee"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/org/payee/{payeeId}/manager": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Move a payee to a new manager effective-dated (SCD-2, FR-302.1) */
        put: operations["movePayeeManager"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/org/rollup/{managerPayeeId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Payees under a manager, optionally as-of a date (FR-303; read-only) */
        get: operations["getRollupScope"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/org/wavefronts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Topological levels of every payee for parallel calc scheduling (FR-503; read-only) */
        get: operations["getWavefronts"];
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
        CsvUploadRequest: {
            /**
             * @description Connector object key for watermark state
             * @default manual_upload
             */
            objectName: string;
            /** @description RFC-4180-ish CSV with a header row */
            csv: string;
            /** @description canonical field -> source column override */
            mapping?: {
                [key: string]: string;
            };
        };
        IngestionResult: {
            accepted: boolean;
            /** Format: uuid */
            batchId: string;
            total: number;
            ingested: number;
            quarantined: number;
            watermarkAfter: string | null;
            /** @description Rows landing in a LOCKED period — each emits eim.trueup.required */
            lateArriving: number;
        };
        SalesforceRunRequest: {
            /** @description SOQL SELECT to execute */
            soql: string;
            watermark?: string | null;
            mapping?: {
                [key: string]: string;
            };
            /** @description Supplied from the secret store at call time, never persisted */
            client_id?: string;
            client_secret?: string;
            username?: string;
            password?: string;
        };
        HubspotRunRequest: {
            /** @description Private-app access token from the secret store at call time */
            token: string;
            /** @description Paging cursor (after) for incremental sync */
            watermark?: string | null;
            /** @default USD */
            default_currency: string;
            mapping?: {
                [key: string]: string;
            };
        };
        QueryRunRequest: {
            /** @description SELECT-only SQL (enforced) */
            query: string;
            /** @default warehouse_probe */
            object_name: string;
            mapping?: {
                [key: string]: string;
            };
        };
        ConnectorRunResult: {
            /** Format: uuid */
            batchId: string;
            accepted: number;
            quarantined: number;
        };
        PayeeUpsertRequest: {
            /** Format: uuid */
            payeeId?: string | null;
            employeeNumber?: string;
            fullName?: string;
            email?: string;
            roleTitle?: string;
            /** Format: uuid */
            managerPayeeId?: string | null;
            /** Format: date */
            effectiveStart?: string;
            /** Format: date */
            effectiveEnd?: string | null;
        };
        ManagerMoveRequest: {
            /** Format: uuid */
            manager_payee_id: string;
            /** Format: date */
            effective_start: string;
            /** Format: date */
            effective_end?: string | null;
        };
        PayeeMutationResult: {
            /** Format: uuid */
            payeeId: string;
            manager?: string | null;
            /** Format: date */
            effectiveStart?: string | null;
        };
        RollupResponse: {
            manager: string;
            /** @description the literal "current" or the requested date */
            asOf: string;
            payees: string[];
            count: number;
        };
        WavefrontResponse: {
            levels: {
                /** Format: uuid */
                payeeId: string;
                level: number;
            }[];
            maxLevel: number;
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
    uploadCsv: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CsvUploadRequest"];
            };
        };
        responses: {
            /** @description Batch accepted for ingestion (rows may quarantine individually) */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["IngestionResult"];
                };
            };
            /** @description Missing csv body */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    runSalesforceSync: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SalesforceRunRequest"];
            };
        };
        responses: {
            /** @description Sync executed; per-row quarantine reported */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConnectorRunResult"];
                };
            };
            /** @description Missing SOQL or credentials */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Salesforce unreachable / token exchange failed */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    runHubspotSync: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["HubspotRunRequest"];
            };
        };
        responses: {
            /** @description Sync executed; per-row quarantine reported */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConnectorRunResult"];
                };
            };
            /** @description HubSpot unreachable / token rejected */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    runQueryConnector: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["QueryRunRequest"];
            };
        };
        responses: {
            /** @description Query executed and ingested */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConnectorRunResult"];
                };
            };
            /** @description Non-SELECT statement rejected */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createPayee: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PayeeUpsertRequest"];
            };
        };
        responses: {
            /** @description Payee created; audit + outbox emitted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayeeMutationResult"];
                };
            };
            /** @description Hierarchy cycle detected — edit rejected */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    movePayeeManager: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                payeeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ManagerMoveRequest"];
            };
        };
        responses: {
            /** @description Version row inserted; graph edge re-dated */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayeeMutationResult"];
                };
            };
            /** @description Payee not found or already closed */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Hierarchy cycle detected — edit rejected */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getRollupScope: {
        parameters: {
            query?: {
                asOf?: string;
            };
            header?: never;
            path: {
                managerPayeeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Transitive rollup scope */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RollupResponse"];
                };
            };
        };
    };
    getWavefronts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Payee levels (0 = leaf) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WavefrontResponse"];
                };
            };
        };
    };
}
