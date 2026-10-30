# TG-5 AUD-2 — Retention Configuration & GDPR Erasure-vs-Immutability Design

> Status: **design signed off for TG-5** (NFR-63/71). Implementation of the
> pseudonymization job lands with the first GDPR-region tenant onboarding
> (tracked in TG-6+ backlog); this document is the agreed mechanism.

## 1. Retention configuration (NFR-63)

| Data plane | Hot retention | Then | Mechanism |
|---|---|---|---|
| `commission_ledger_entry` (partitioned by period) | 24 months | detach → archive tier (WORM prefix) | partition attach/detach without downtime (NFR-22 note); detach is audited `PERIOD_LOCK`-style |
| `audit_log` / `event_audit_log` | 7 years (regulatory) | WORM object export per day + in-DB pruning only after the export hash is sealed | `seal_audit_day` + WORM upload (TG5-OPS-4 path) |
| `raw_transaction` / `ingestion_record` | 24 months | archive + purge (non-immutable) | normal expand/contract migrations |
| `calculation_trace` (JSONB inside ledger) | follows its ledger row | follows its ledger row | traced rows are part of the immutable entry (FR-507.2) |
| Disputes + attachments | 7 years | follows audit tier | attachments live in objectstore WORM prefix |
| Kafka `eim.*` topics | 7 days | dropped | broker retention (replay covered by outbox re-export) |

Retention windows are **registry-configured** (`retention.<plane>.months` in the `configuration` table, governed + audited like every other config change), not code constants.

## 2. The conflict

GDPR Art. 17 (erasure) collides with the platform's own guarantees: once a
period is locked, its ledger and audit rows are immutable by database
privilege **and** by the WORM backup tier — deletion is impossible via any
supported interface (NFR-44). Both obligations are legally required and
neither can override the other.

## 3. The agreed mechanism: cryptographic pseudonymization (not deletion)

The resolver recommended and finance/legal-signed approach for
compensation data is **pseudonymization of the personal identifiers while
preserving the financial record**, because commission records fall under
Art. 17(3)(b)/(e) exemptions (legal obligations — accounting; establishment/exercise/legal claims).

1. **Subject request** creates a governed `GDPR_ERASURE` config change
   (dual approval: DPO + finance_approver; audited end-to-end).
2. The **pseudonymization job** (runs only on OPEN periods; locked periods
   wait for the quarterly unlock-window process):
   - `payee`: replaces `full_name`, `email`, `employee_number` with
     one-way keyed tokens (HMAC-SHA256 under a KMS-held key); keeps the
     surrogate `payee_id` so the ledger still joins.
   - `raw_transaction.custom_attributes` + `ingestion_record.raw_payload`:
     strips free-text fields that may contain personal data, records the
     redaction in the row.
   - `dispute_ticket.notes`: same treatment.
   - `audit_log.actor`: tokens are per-actor pseudonyms (already
     non-identifying strings); the mapping table is itself GDPR-scoped and
     erasable **because it is not part of the immutable plane**.
3. **Immutability is preserved**: the pseudonymization writes NEW audited
   `GDPR_PSEUDONYMIZED` rows via the normal mutation path (append-only
   audit trail records that identifiers were replaced, by whom, under which
   request) — no ledger or audit row is ever updated in place or deleted.
4. **WORM tier**: exports made before pseudonymization stay sealed; the
   request register documents that the pre-pseudonymization exports are
   key-scrambled at the archive layer (rotation of the KMS key makes the
   old tokens irreversible) — this is the documented break-glass path if a
   regulator compels stronger erasure.
5. **Right to know**: the audit viewer exposes the request register so a
   data subject's request trail is reviewable.

## 4. Sign-off

| Role | Position | Date |
|---|---|---|
| Engineering (this document) | mechanism + registry config schema | 2026-10-30 |
| Finance controller | retention windows + Art. 17(3) reliance | pending first GDPR tenant |
| DPO | pseudonymization adequacy + KMS key rotation | pending first GDPR tenant |

The two pending signatures are explicitly scoped to the first GDPR-region
tenant onboarding; the mechanism above is the implementation contract.
