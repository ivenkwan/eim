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
    "/api/v1/admin/config-change": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Submit a versioned configuration change into the audit plane (skeleton) */
        post: operations["submitConfigChange"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/payee": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** RLS-protected seller view (exit criterion TG-1 */
        get: operations["getMyPayee"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/periods/{periodKey}/lock": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Lock a payout period (SoD — finance_approver only, FR-603) */
        post: operations["lockPayoutPeriod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/periods/{periodKey}/reopen": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** MI-18 break-glass reopen — dual approval (finance_approver AND system_admin, two distinct people, impact memo mandatory) */
        post: operations["reopenPayoutPeriod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List accounting books with policy + decision-table version */
        get: operations["listAccountingBooks"];
        put?: never;
        /** Register an accounting book (conventions, currency, fringe, expedient) with the default classification decision table generated from policy (TG7-ACC-1/2) */
        post: operations["createAccountingBook"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/dmn": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Upload a bespoke ASC 606 classification decision table (validated on the KIE DMN runtime, versioned, audited — Controller-editable) */
        put: operations["uploadClassificationDmn"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/classification/classify/{periodKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Classify every ledger entry of a LOCKED period against the book's decision table (FR-901); CAPITALIZE rows become assets with FX lock + schedules */
        post: operations["classifyPeriod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/classification/completeness/{periodKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Pre-close completeness gate (FR-908) — 100% classified or itemized exceptions */
        get: operations["classificationCompleteness"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/amortization/rollforward/{periodKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Deferred cost rollforward with the accounting identity check + current/non-current split (FR-906.1) */
        get: operations["deferredCostRollforward"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/amortization/rollforward/{periodKey}/export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Full drill-down CSV (schedule line → asset → ledger → transaction → rep, FR-906.3) */
        get: operations["rollforwardDrilldownExport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/amortization/reclass/{periodKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Monthly ST/LT reclassification journal (FR-906.2) */
        post: operations["stLtReclass"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/jes/{periodKey}/{jeType}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Generate a journal batch from a template (PAYROLL_ACCRUAL/CAPITALIZATION/AMORTIZATION/IMPAIRMENT/CATCH_UP) with lineage (FR-907.1) */
        post: operations["generateJournalBatch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/jes/{periodKey}/balance-check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Period balance gate (FR-907.2) — debits = credits per subsidiary/currency */
        get: operations["journalBalanceCheck"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/jes/batch/{batchId}/push": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** ERP push (PENDING/EXPORTED/FAILED transitions; retry from FAILED) — FR-907.4 */
        post: operations["pushJournalBatch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/books/{bookId}/jes/batch/{batchId}/ack": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** ERP acknowledgement (EXPORTED → SYNCED) */
        post: operations["ackJournalBatch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/assets/{assetId}/churn": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Full churn (FR-903.1) — zero unposted future lines + impairment of the remainder in the open period */
        post: operations["churnAsset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/assets/{assetId}/downgrade": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Partial downgrade (FR-903.2) — continue or pro-rata impairment per book policy */
        post: operations["downgradeAsset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/assets/{assetId}/retro-trueup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Retroactive true-up (FR-903.3) — child stream from the original start, cumulative catch-up in the discovery period */
        post: operations["retroTrueupAsset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/lineage/gl-lines/{glLineId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Zero-orphan drill-through GL → schedule → asset → ledger → transaction → rep (FR-909) */
        get: operations["lineageDrill"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounting/lineage/audit-sample/{periodKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Monthly sampled lineage audit (NFR-61) — sampled GL lines must resolve end-to-end */
        post: operations["lineageAuditSample"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/disputes/sla": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** SLA timers per category with breach flags (FR-705.2) */
        get: operations["disputeSlaReport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/disputes/{disputeId}/escalate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Escalate an open dispute to its category's escalation role (FR-705.2) */
        post: operations["escalateDispute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/security/attachments/scan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Attachment malware scan gate (FR-704.2/NFR-56) — EICAR vector, executable magics, blocked extensions */
        post: operations["scanAttachment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/security/gdpr/erasure": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** GDPR erasure request (NFR-71) — pseudonymize the payee, preserve immutable financials */
        post: operations["gdprErasure"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/security/residency": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Data-residency posture report (NFR-72) */
        get: operations["residencyReport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/spm/territories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Territories with quota totals + active assignment counts */
        get: operations["listTerritories"];
        put?: never;
        /** Territory design (effective-dated, hierarchical; FR-1002) */
        post: operations["createTerritory"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/spm/territories/{territoryId}/assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Assign a payee or account key to a territory (effective-dated, split fractions) */
        post: operations["assignTerritoryCoverage"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/spm/territories/{territoryId}/quotas": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Quota allocation per period (FR-1002) */
        post: operations["allocateQuota"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/spm/territories/realignment-preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** What-if realignment preview — pure read of current account routes (FR-1002) */
        post: operations["realignmentPreview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/spm/routing": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Routing rules in priority order */
        get: operations["listRoutingRules"];
        put?: never;
        /** Account-routing rule feeding credit assignment (FR-1003) */
        post: operations["createRoutingRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/spm/routing/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Resolve the payees an account credits (rule → territory fan-out with splits; FR-1003) */
        get: operations["resolveAccountRouting"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/channel/partners": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Register a channel partner (mTLS cert CN → partner identity, role-separated; FR-1004) */
        post: operations["registerChannelPartner"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/channel/partners/whoami": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Partner mTLS CN → partner identity (never internal roles; FR-1004) */
        get: operations["partnerWhoami"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/channel/partners/{partnerPayeeId}/statement": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Partner statement (reuses the ledger machinery, partner-scoped; FR-1004) */
        get: operations["partnerStatement"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/channel/partners/{partnerPayeeId}/disputes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Partner self-service dispute (reuses dispute machinery; FR-1004) */
        post: operations["partnerDispute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/channel/claims": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Channel claims with status + accrual linkage */
        get: operations["listChannelClaims"];
        put?: never;
        /** Channel claim — eligibility checked at submission (FR-1005) */
        post: operations["submitChannelClaim"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/channel/claims/{claimId}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Approve + accrue on the core engine (claimed × program rate → audited ledger entry; FR-1005) */
        post: operations["approveChannelClaim"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ai/plan-modeling/requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Natural-language plan modeling — deterministic grammar compiles a draft; human approval always required (FR-408) */
        post: operations["createNlModelRequest"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ai/plan-modeling/requests/{requestId}/decision": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Human decision on an AI-generated plan (SoD — requester cannot approve; FR-408) */
        post: operations["decideNlModelRequest"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ai/plan-diagnostics/{planVersionHash}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** AI plan diagnostics — windfall/cliff/gaming anomaly detection (FR-1006) */
        post: operations["diagnosePlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ai/earnings-explainer/{ledgerId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** GenAI earnings explainer strictly grounded in the stored trace, with dispute handoff (FR-708) */
        get: operations["explainEarnings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/migration/spm/{source}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Legacy-SPM migration importer (Varicent/Xactly/Anaplan shapes; TG8-ONB-1) — deterministic ids make re-imports idempotent */
        post: operations["importLegacySpm"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/partners": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List partner tenants with onboarding lifecycle status */
        get: operations["listPartners"];
        put?: never;
        /** Register a partner tenant for onboarding with its IdP federation posture (TG6-ONB-1) */
        post: operations["onboardPartner"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/partners/{partnerId}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Advance a partner one lifecycle step (ONBOARDING→BACKFILLED→PARALLEL_RUN→SIGNED_OFF→LIVE); SIGNED_OFF gated on two 100% sign-offs */
        post: operations["advancePartnerStatus"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parallel-run/partners/{partnerId}/periods/{periodKey}/legacy-statement": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Upload the legacy system's statement for a period (shadow totals for the parallel run) */
        post: operations["uploadLegacyStatement"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parallel-run/partners/{partnerId}/periods/{periodKey}/diff": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Regenerate the cent-for-cent EIM-vs-legacy diff for the period (full outer join per payee) */
        post: operations["generateParallelDiff"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parallel-run/partners/{partnerId}/periods/{periodKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Per-period diff report with triage state and sign-off evidence */
        get: operations["parallelRunReport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parallel-run/diffs/{diffId}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Triage a DISCREPANT row (RESOLVED_ADJUSTED flows through the audited override path; note mandatory) */
        post: operations["resolveParallelDiff"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parallel-run/partners/{partnerId}/periods/{periodKey}/signoff": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Record the cent-for-cent sign-off — refused below a 100.0% match (ONB-4); evidence hash over the diff set */
        post: operations["signoffParallelPeriod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parallel-run/close-cycle": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Close-cycle telemetry — business days from period end to lock per locked period (ONB-6, SLA < 3) */
        get: operations["closeCycleTelemetry"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/config-change/{configurationId}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Approve a governed configuration change (FR-613.2) */
        post: operations["approveConfigChange"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/audit/reconcile": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** AUD-9 evidence (mutation/outbox/event counts + gap) */
        get: operations["auditReconcile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/audit/seals/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** AUD-6 seal verification (recompute hash chain) */
        get: operations["auditSealVerify"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/audit/graph/reconcile": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** DAT-4 graph drift check (empty = clean) */
        get: operations["auditGraphReconcile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/overrides": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Manual adjustment override (separate entry type, original preserved, mandatory reason) */
        post: operations["createManualOverride"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/audit-viewer": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Audit viewer — search all three streams with CSV/JSON export (audit-read audited) */
        get: operations["searchAudit"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/exports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Payroll export download (file stream) */
        get: operations["downloadPayrollExport"];
        put?: never;
        /** Payroll export (ADP/Workday/NetSuite templates, hashed, byte-identical regen) */
        post: operations["generatePayrollExport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/statement": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Seller statement overview (RLS-scoped; read-only during engine outage) */
        get: operations["getStatementOverview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/statement/trace/{ledgerId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Calculation trace drawer payload (JSONB-bound) */
        get: operations["getStatementTrace"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/statement/download": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Statement CSV download matching on-screen values */
        get: operations["downloadStatement"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/disputes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Dispute queue (admin) */
        get: operations["listDisputes"];
        put?: never;
        /** Dispute submission (4 structured categories, backward-chaining pre-tag) */
        post: operations["submitDispute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/disputes/{disputeId}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Approve/reject with mandatory comment; full lifecycle audit */
        post: operations["resolveDispute"];
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
        OverrideRequest: {
            payoutPeriodId: string;
            payeeId: string;
            componentName: string;
            adjustedAmount: string;
            reasonCode: string;
            reasonComment: string;
            disputeId?: string | null;
        };
        ExportRequest: {
            periodId: string;
            /** @enum {string} */
            template: "ADP" | "WORKDAY" | "NETSUITE_JOURNAL";
        };
        DisputeSubmission: {
            /** @enum {string} */
            category: "SOURCE_DATA" | "CREDIT_SPLIT" | "WINDOW_STATE" | "PLAN_LOGIC" | "OTHER";
            notes: string;
            ledgerId?: string | null;
            transactionId?: string | null;
            symptom?: string | null;
        };
        DisputeResolution: {
            /** @enum {string} */
            resolution: "APPROVE" | "REJECT";
            comment: string;
        };
        HelloResponse: {
            service: string;
            /** @enum {string} */
            status: "ok";
            message: string;
        };
        ConfigChangeRequest: {
            entity: string;
            payload?: string;
            reason: string;
        };
        ConfigChangeAccepted: {
            accepted: boolean;
            requestId: string;
        };
        MePayeeResponse: {
            actor?: string;
            correlation_id?: string;
            tenant_id?: string;
            payee?: Record<string, never>;
        };
        PeriodLockRequest: {
            reason: string;
        };
        PeriodReopenRequest: {
            /** @description Documented incident procedure reference + impact assessment (MI-18 P1) */
            impactMemo: string;
        };
        PartnerUpsertRequest: {
            name: string;
            idpEntityId?: string | null;
            /** @enum {string} */
            idpPosture?: "GATEWAY_OIDC" | "SAML_FEDERATED";
            payeeTarget?: number;
        };
        PartnerStatusRequest: {
            /** @enum {string} */
            status: "ONBOARDING" | "BACKFILLED" | "PARALLEL_RUN" | "SIGNED_OFF" | "LIVE";
        };
        DiffResolveRequest: {
            /** @enum {string} */
            resolution: "RESOLVED_ADJUSTED" | "RESOLVED_EXPLAINED";
            note: string;
        };
        AccountingBookRequest: {
            bookCode: string;
            description?: string | null;
            functionalCurrency?: string;
            /** @enum {string} */
            amortizationConvention?: "EXACT_DAILY" | "FULL_MONTH" | "MID_MONTH" | "PO_WEIGHTED";
            expedientThresholdMonths?: number;
            fringeRate?: string;
            capitalizeManagerOverrides?: boolean;
            /** @enum {string} */
            seSpiffPolicy?: "FORMULAIC_ONLY" | "ALWAYS" | "NEVER";
            /** @enum {string} */
            renewalPolicy?: "CAP_NON_COMMENSURATE" | "CAP_ALWAYS" | "EXPENSE";
            /** @enum {string} */
            downgradePolicy?: "CONTINUE" | "PRO_RATE_IMPAIR";
        };
        JeGenerateRequest: {
            subsidiary?: string;
            costCenter?: string;
        };
        ErpPushRequest: {
            /** @enum {string} */
            erpTarget?: "NETSUITE" | "SAP" | "WORKDAY";
            simulateFailure?: string;
        };
        AssetLifecycleRequest: {
            openPeriodKey?: string;
            discoveryPeriodKey?: string;
            contractionPct?: number;
            additionalAmount?: string;
            comment?: string;
        };
        GdprErasureRequest: {
            payeeId: string;
            reason: string;
        };
        ErrorResponse: {
            error: string;
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
    submitConfigChange: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ConfigChangeRequest"];
            };
        };
        responses: {
            /** @description Change accepted into the audited config registry */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConfigChangeAccepted"];
                };
            };
            /** @description Invalid request */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    getMyPayee: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Caller identity + RLS-scoped payee record */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MePayeeResponse"];
                };
            };
        };
    };
    lockPayoutPeriod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["PeriodLockRequest"];
            };
        };
        responses: {
            /** @description Period locked */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Role not permitted (SoD) */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Unknown period */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    reopenPayoutPeriod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PeriodReopenRequest"];
            };
        };
        responses: {
            /** @description PENDING_DUAL_APPROVAL (one signature recorded) or EXECUTED (period OPEN again) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description impactMemo missing */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Role not permitted */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Period not locked, or dual control violated */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listAccountingBooks: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Books for the tenant */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createAccountingBook: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountingBookRequest"];
            };
        };
        responses: {
            /** @description Book created */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    uploadClassificationDmn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "text/xml": string;
            };
        };
        responses: {
            /** @description New decision-table version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Decision table failed DMN validation */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    classifyPeriod: {
        parameters: {
            query?: {
                monthsHint?: number;
            };
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Classification summary (capitalized/expensed) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Period not locked or empty */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    classificationCompleteness: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Completeness report */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    deferredCostRollforward: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Rollforward row */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    rollforwardDrilldownExport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSV */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    stLtReclass: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Reclass batch */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Already reclassed */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    generateJournalBatch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
                jeType: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["JeGenerateRequest"];
            };
        };
        responses: {
            /** @description Balanced batch created (PENDING) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description No source amounts / duplicate */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    journalBalanceCheck: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Balance report */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    pushJournalBatch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                batchId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ErpPushRequest"];
            };
        };
        responses: {
            /** @description Pushed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    ackJournalBatch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookId: string;
                batchId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description SYNCED */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    churnAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                assetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssetLifecycleRequest"];
            };
        };
        responses: {
            /** @description CHURNED with impairment */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    downgradeAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                assetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssetLifecycleRequest"];
            };
        };
        responses: {
            /** @description DOWNGRADED (or CONTINUE policy) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    retroTrueupAsset: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                assetId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssetLifecycleRequest"];
            };
        };
        responses: {
            /** @description Child asset + catch-up */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    lineageDrill: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                glLineId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Full lineage chain */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    lineageAuditSample: {
        parameters: {
            query?: {
                sample?: number;
            };
            header?: never;
            path: {
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Sample result with orphan list */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    disputeSlaReport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Open disputes with due times */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    escalateDispute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                disputeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Escalated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Not open / already escalated */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    scanAttachment: {
        parameters: {
            query?: {
                filename?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/octet-stream": string;
            };
        };
        responses: {
            /** @description Clean */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Rejected with findings */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    gdprErasure: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GdprErasureRequest"];
            };
        };
        responses: {
            /** @description Pseudonymized with ledger rows preserved */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    residencyReport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Residency flags */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listTerritories: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Territories */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createTerritory: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    name: string;
                    parentTerritoryId?: string | null;
                    effectiveStart?: string | null;
                };
            };
        };
        responses: {
            /** @description Territory created */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    assignTerritoryCoverage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                territoryId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    payeeId?: string | null;
                    accountKey?: string | null;
                    splitPct?: number;
                    effectiveStart?: string | null;
                };
            };
        };
        responses: {
            /** @description Assignment recorded */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    allocateQuota: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                territoryId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    periodKey: string;
                    quotaAmount: string;
                };
            };
        };
        responses: {
            /** @description Quota allocated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    realignmentPreview: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": Record<string, never>;
            };
        };
        responses: {
            /** @description Account routes */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listRoutingRules: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Rules */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createRoutingRule: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    priority?: number;
                    matchAccountKey?: string | null;
                    matchComponent?: string | null;
                    routeToTerritoryId?: string | null;
                    routeToPayeeId?: string | null;
                };
            };
        };
        responses: {
            /** @description Rule created */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    resolveAccountRouting: {
        parameters: {
            query: {
                accountKey: string;
                component?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Routed payees */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    registerChannelPartner: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    orgName: string;
                    certCn?: string | null;
                    contactEmail?: string | null;
                };
            };
        };
        responses: {
            /** @description Partner registered */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    partnerWhoami: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Partner identity */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing/unknown cert CN */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    partnerStatement: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerPayeeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Statement lines + total */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    partnerDispute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerPayeeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    category: string;
                    notes: string;
                };
            };
        };
        responses: {
            /** @description Dispute submitted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listChannelClaims: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Claims */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    submitChannelClaim: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    partnerPayeeId: string;
                    programCode: string;
                    claimPeriodKey: string;
                    claimedAmount: string;
                };
            };
        };
        responses: {
            /** @description Claim submitted (ELIGIBILITY_CHECKED or REJECTED) */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    approveChannelClaim: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                claimId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description ACCRUED with ledger entry id */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createNlModelRequest: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    prompt: string;
                };
            };
        };
        responses: {
            /** @description Draft compiled (PENDING_HUMAN) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Grammar rejected the prompt */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    decideNlModelRequest: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                requestId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @enum {string} */
                    decision: "APPROVED" | "REJECTED";
                };
            };
        };
        responses: {
            /** @description Decision recorded */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Requester cannot approve their own draft */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    diagnosePlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                planVersionHash: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Findings persisted */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    explainEarnings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Narrative + dispute handoff */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    importLegacySpm: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                source: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "text/csv": string;
            };
        };
        responses: {
            /** @description Import summary with per-row errors */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listPartners: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Partners for the caller tenant */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    onboardPartner: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PartnerUpsertRequest"];
            };
        };
        responses: {
            /** @description Partner registered (status ONBOARDING) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Invalid name or idpPosture */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Role not permitted */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    advancePartnerStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PartnerStatusRequest"];
            };
        };
        responses: {
            /** @description Status advanced */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Not a one-step advance or sign-off gate unmet */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    uploadLegacyStatement: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "text/csv": string;
            };
        };
        responses: {
            /** @description Rows loaded (upsert per payee) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed CSV */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    generateParallelDiff: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description payees/matched/discrepant counts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    parallelRunReport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Diff rows + match rate + signoff hash */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    resolveParallelDiff: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                diffId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DiffResolveRequest"];
            };
        };
        responses: {
            /** @description Row resolved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description note missing or invalid resolution */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Row not DISCREPANT */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    signoffParallelPeriod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partnerId: string;
                periodKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Sign-off recorded with evidence hash */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Not at 100% or already signed off */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    closeCycleTelemetry: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Per-period close durations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    approveConfigChange: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                configurationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Approved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Role not permitted */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Unknown configuration */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    auditReconcile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Reconciliation window counts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    auditSealVerify: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Per-day verification results */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    auditGraphReconcile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Drift lines */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createManualOverride: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OverrideRequest"];
            };
        };
        responses: {
            /** @description Override recorded */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description reasonCode/reasonComment missing */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Period locked */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    searchAudit: {
        parameters: {
            query?: {
                stream?: string;
                actor?: string;
                entity?: string;
                actionType?: string;
                correlationId?: string;
                from?: string;
                to?: string;
                format?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Rows (JSON) or CSV attachment */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    downloadPayrollExport: {
        parameters: {
            query: {
                periodId: string;
                template: string;
                format?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSV bytes or metadata */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    generatePayrollExport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ExportRequest"];
            };
        };
        responses: {
            /** @description CSV + file hash */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Unknown template */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getStatementOverview: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Overview with components + thermometer */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getStatementTrace: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ledgerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trace */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Not found for this payee */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    downloadStatement: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSV */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listDisputes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Open tickets */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    submitDispute: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DisputeSubmission"];
            };
        };
        responses: {
            /** @description Created with pre-tagged category */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    resolveDispute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                disputeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DisputeResolution"];
            };
        };
        responses: {
            /** @description Resolved */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Not open */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
