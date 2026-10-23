-- Containers are no longer weighed when they move between sites: an item is simply received or not.
-- (The two weight columns stay for transfers recorded earlier; nothing new is written to them.)
ALTER TABLE transfer_items ADD COLUMN received_at TIMESTAMP;
UPDATE transfer_items SET received_at = now() WHERE weight_kg_at_receipt IS NOT NULL;
