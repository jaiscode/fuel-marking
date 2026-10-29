-- seq is no longer taken while a push is writing: a push stores rows with seq NULL and SyncSequencer numbers committed
-- TRANSFER rows afterwards (a sequence value taken at write time can commit after a higher one, and pullers would skip it
-- for good). Only transfers are pulled, so only they are numbered; other types keep seq NULL from now on.
-- Rows already numbered keep their values, so the sites' pull cursors stay valid.
-- synced_entities_seq (created in V1, which is released and must not be edited) must stay CACHE 1, the default: with a
-- larger cache each session hands out numbers from its own pre-allocated range, so a later numbering run could get a LOWER
-- number than an earlier one and pullers would skip it. Never ALTER it to a larger cache.
-- Stop every older backend instance before this runs (docs/deployment.md, "Upgrading the central backend").
ALTER TABLE synced_entities ALTER COLUMN seq DROP DEFAULT, ALTER COLUMN seq DROP NOT NULL;
CREATE INDEX idx_synced_transfers_unnumbered ON synced_entities(received_at, entity_id) WHERE entity_type = 'TRANSFER' AND seq IS NULL;

-- Custody-conflict checks look transfers up by the barcodes they carry.
CREATE INDEX idx_synced_transfers_items ON synced_entities USING gin ((payload -> 'items') jsonb_path_ops) WHERE entity_type = 'TRANSFER';

-- An audit entry that must be recorded only once (a custody conflict) carries a key; NULL for everything else.
ALTER TABLE audit_log ADD COLUMN dedupe_key VARCHAR(800);
CREATE UNIQUE INDEX uq_audit_log_dedupe_key ON audit_log(dedupe_key) WHERE dedupe_key IS NOT NULL;
-- The admin audit view can be filtered by action, newest first.
CREATE INDEX idx_audit_log_action ON audit_log(action, id);
