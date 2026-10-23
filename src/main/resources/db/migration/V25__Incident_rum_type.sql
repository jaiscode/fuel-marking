-- An incident records which rum was lost, not just how much: the rum type of the container it happened to, or the one chosen when no container is named.
ALTER TABLE incidents ADD COLUMN rum_type_code VARCHAR(30);
UPDATE incidents i SET rum_type_code = k.rum_type_code FROM containers k WHERE k.id = i.container_id AND i.rum_type_code IS NULL;
