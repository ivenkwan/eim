-- TG-2 SCD-2 re-keying (FR-302.1) — the CONTRACT half of expand/contract.
-- A mid-period manager move inserts a second row for the SAME payee_id with
-- a new effective range, so the primary key must cover the version, not the
-- person. FKs from child tables reference the PERSON (payee_id), which is no
-- longer unique — they become logical references (enforced by the
-- ingestion/hierarchy paths) and the composite PK guarantees version
-- uniqueness instead. Everything here is guarded and re-runnable.
DO $ddl$
BEGIN
    -- Person-keyed FKs cannot reference a non-unique column and they pin
    -- the single-column PK's index: drop them first, then re-key the table.
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'payee'::regclass AND conname = 'payee_manager_payee_id_fkey') THEN
        ALTER TABLE payee DROP CONSTRAINT payee_manager_payee_id_fkey;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'plan_assignment'::regclass AND conname = 'plan_assignment_payee_id_fkey') THEN
        ALTER TABLE plan_assignment DROP CONSTRAINT plan_assignment_payee_id_fkey;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'credit_allocation'::regclass AND conname = 'credit_allocation_payee_id_fkey') THEN
        ALTER TABLE credit_allocation DROP CONSTRAINT credit_allocation_payee_id_fkey;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'commission_ledger_entry'::regclass
                 AND conname = 'commission_ledger_entry_payee_id_fkey') THEN
        ALTER TABLE commission_ledger_entry DROP CONSTRAINT commission_ledger_entry_payee_id_fkey;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'dispute_ticket'::regclass AND conname = 'dispute_ticket_payee_id_fkey') THEN
        ALTER TABLE dispute_ticket DROP CONSTRAINT dispute_ticket_payee_id_fkey;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payee'::regclass AND conname = 'payee_pkey'
          AND pg_get_constraintdef(oid) NOT LIKE '%effective_start_date%'
    ) THEN
        ALTER TABLE payee DROP CONSTRAINT payee_pkey;
        ALTER TABLE payee ADD CONSTRAINT payee_pkey
            PRIMARY KEY (payee_id, effective_start_date);
    END IF;
END
$ddl$;

-- Employee numbers are person-scoped; under SCD-2 each version repeats the
-- number, so the uniqueness guard moves to the version grain.
DROP INDEX IF EXISTS uq_payee_employee_number;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payee_employee_number
    ON payee (tenant_id, employee_number, effective_start_date)
    WHERE employee_number IS NOT NULL;
