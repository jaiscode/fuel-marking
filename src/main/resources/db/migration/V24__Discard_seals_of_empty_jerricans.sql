-- When a jerrican is emptied its seals are discarded: it goes back to the warehouse without them and is sealed again when it is refilled.
-- The discarded numbers are remembered, so a seal number can still never go on a container twice.
CREATE TABLE discarded_seals (
    seal VARCHAR(100) PRIMARY KEY,
    container_id UUID NOT NULL REFERENCES containers(id),
    discarded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- jerricans that are already empty: their seals are discarded now
INSERT INTO discarded_seals(seal, container_id)
SELECT top_seal, id FROM containers WHERE status = 'EMPTY' AND container_type LIKE 'JERRICAN%' AND top_seal IS NOT NULL ON CONFLICT DO NOTHING;
INSERT INTO discarded_seals(seal, container_id)
SELECT bottom_seal, id FROM containers WHERE status = 'EMPTY' AND container_type LIKE 'JERRICAN%' AND bottom_seal IS NOT NULL ON CONFLICT DO NOTHING;
UPDATE containers SET top_seal = NULL, bottom_seal = NULL, updated_at = now() WHERE status = 'EMPTY' AND container_type LIKE 'JERRICAN%';
