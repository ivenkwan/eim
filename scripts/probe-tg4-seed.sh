-- TG-4 monthly-cycle fixture seed (executed via: kubectl exec -i
-- eim-postgres-0 -- psql -U eim -d eim < this)
DELETE FROM commission_ledger_entry WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM calc_run WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM trueup_review WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM export_run WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM manual_override WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM dispute_ticket WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM process_task WHERE process_id IN (SELECT process_id FROM process_instance WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001');
DELETE FROM process_instance WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001');
DELETE FROM raw_transaction WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
SELECT eim_ops.detach_delete_payee('cccccccc-c000-0000-0000-000000000002');
SELECT eim_ops.detach_delete_payee('cccccccc-c000-0000-0000-000000000003');
DELETE FROM payee WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
DELETE FROM payout_period WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE tenant_id = 'cccccccc-c000-0000-0000-000000000001';
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;

INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date)
VALUES ('cccccccc-c000-0000-0000-000000000002','cccccccc-c000-0000-0000-000000000001','C-0','Cycle Manager','cycle.mgr@example.test','Mgr',DATE '2026-01-01');
INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, manager_payee_id, effective_start_date)
VALUES ('cccccccc-c000-0000-0000-000000000003','cccccccc-c000-0000-0000-000000000001','C-1','Cycle Rep','cycle.rep@example.test','AE','cccccccc-c000-0000-0000-000000000002',DATE '2026-01-01');
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status)
VALUES ('cccccccc-c000-0000-0000-000000000004','cccccccc-c000-0000-0000-000000000001','2026-c1',DATE '2026-04-01',DATE '2026-04-30','OPEN');
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status)
VALUES ('cccccccc-c000-0000-0000-000000000005','cccccccc-c000-0000-0000-000000000001','2026-c2',DATE '2026-05-01',DATE '2026-05-31','OPEN');
