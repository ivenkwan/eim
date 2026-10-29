-- TG3-CAL: the canonical plan_json is the version IDENTITY (string form);
-- model_json keeps the raw model so the calc engine can execute the exact
-- published plan without re-parsing the canonical form.
ALTER TABLE plan_artifact ADD COLUMN IF NOT EXISTS model_json JSONB;
