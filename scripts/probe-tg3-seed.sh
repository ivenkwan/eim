-- TG-3 fixture seed (executed via: kubectl exec -i eim-postgres-0 -- psql -U eim -d eim < this)
DELETE FROM commission_ledger_entry WHERE tenant_id = '99999999-0000-0000-0000-000000000001';
DELETE FROM calc_run WHERE tenant_id = '99999999-0000-0000-0000-000000000001';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = '99999999-0000-0000-0000-000000000001');
DELETE FROM raw_transaction WHERE tenant_id = '99999999-0000-0000-0000-000000000001';
SELECT eim_ops.detach_delete_payee('a11ce000-0000-0000-0000-000000000001');
SELECT eim_ops.detach_delete_payee('a11ce000-0000-0000-0000-000000000002');
DELETE FROM payee WHERE tenant_id = '99999999-0000-0000-0000-000000000001';
DELETE FROM payout_period WHERE tenant_id = '99999999-0000-0000-0000-000000000001';
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE tenant_id = '99999999-0000-0000-0000-000000000001';
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;

INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date)
VALUES ('a11ce000-0000-0000-0000-000000000001','99999999-0000-0000-0000-000000000001','P-0','Probe Manager','probe.mgr@example.test','Mgr',DATE '2026-01-01');
INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, manager_payee_id, effective_start_date)
VALUES ('a11ce000-0000-0000-0000-000000000002','99999999-0000-0000-0000-000000000001','P-1','Probe Rep','probe.rep@example.test','AE','a11ce000-0000-0000-0000-000000000001',DATE '2026-01-01');
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status)
VALUES ('a11ce000-0000-0000-0000-000000000101','99999999-0000-0000-0000-000000000001','2026-04',DATE '2026-04-01',DATE '2026-04-30','OPEN');
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status)
VALUES ('a11ce000-0000-0000-0000-000000000102','99999999-0000-0000-0000-000000000001','2026-05',DATE '2026-05-01',DATE '2026-05-31','OPEN');

INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, external_deal_id, transaction_date, booking_amount, currency_code, ingestion_batch_id)
VALUES (gen_random_uuid(),'99999999-0000-0000-0000-000000000001','CSV','P-1','2026-04-05',100000,'USD',gen_random_uuid());
INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, external_deal_id, transaction_date, booking_amount, currency_code, ingestion_batch_id)
VALUES (gen_random_uuid(),'99999999-0000-0000-0000-000000000001','CSV','P-2','2026-04-20',60000,'USD',gen_random_uuid());
INSERT INTO credit_allocation (credit_allocation_id, tenant_id, transaction_id, payee_id, split_pct, effective_date)
SELECT gen_random_uuid(),'99999999-0000-0000-0000-000000000001',t.transaction_id,'a11ce000-0000-0000-0000-000000000002',100,t.transaction_date FROM raw_transaction t WHERE t.tenant_id='99999999-0000-0000-0000-000000000001';
