-- TG7 (NFR-25 scale gate): index the self-referential asset FK. Without
-- it, every DELETE on capitalized_cost_asset runs a seq-scan FK reference
-- check per row (FOR KEY SHARE on parent_asset_id) — deleting a 500k-row
-- scale portfolio took minutes; with the index it is an index probe.
CREATE INDEX IF NOT EXISTS ix_asset_parent ON capitalized_cost_asset (parent_asset_id);
