-- TG8: windfall_ratio overflow — a payee with near-zero historical payout
-- can carry p95/hist in the thousands; NUMERIC(7,3) capped at 9999.999 and
-- the infinity sentinel (999999) overflowed. Widen (expand-only).
ALTER TABLE simulation_payee_outcome ALTER COLUMN windfall_ratio TYPE NUMERIC(12,3);
