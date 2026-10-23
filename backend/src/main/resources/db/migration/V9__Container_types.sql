-- Container types are master data too: bulk containers (IBC, drum ...) and jerricans (plastic, metal ...), each with the number of
-- seals it carries and, optionally, its usual capacity. A jerrican is registered for one of the JERRICAN types.
CREATE TABLE container_types (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(10) NOT NULL CHECK (category IN ('BULK', 'JERRICAN')),
    seals INTEGER NOT NULL CHECK (seals BETWEEN 0 AND 2),                       -- seals the container carries (a jerrican needs at least 1)
    default_capacity_g NUMERIC(14,2) CHECK (default_capacity_g IS NULL OR default_capacity_g > 0),   -- grams of rum; blank = no usual size
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (category <> 'JERRICAN' OR seals >= 1)
);

-- Starting list (the same ids and codes are built into the desktop, and the codes are what containers already store).
INSERT INTO container_types(id, code, name, category, seals, default_capacity_g) VALUES
    ('c0000000-0000-4000-8000-000000000001', 'IBC', 'IBC', 'BULK', 0, NULL),
    ('c0000000-0000-4000-8000-000000000002', 'DRUM', 'Drum', 'BULK', 0, NULL),
    ('c0000000-0000-4000-8000-000000000003', 'JERRICAN_PLASTIC', 'Plastic jerrican', 'JERRICAN', 1, 22000),
    ('c0000000-0000-4000-8000-000000000004', 'JERRICAN_METAL', 'Metal jerrican', 'JERRICAN', 2, 18000);

-- Registered jerricans refer to their container type instead of a hard-wired material.
ALTER TABLE registered_jerricans ADD COLUMN container_type_id UUID REFERENCES container_types(id);
UPDATE registered_jerricans j SET container_type_id = t.id FROM container_types t
    WHERE t.code = CASE j.material WHEN 'METAL' THEN 'JERRICAN_METAL' ELSE 'JERRICAN_PLASTIC' END;
ALTER TABLE registered_jerricans ALTER COLUMN container_type_id SET NOT NULL;
ALTER TABLE registered_jerricans DROP COLUMN material;

INSERT INTO master_data_outbox(msg_key, payload)
SELECT 'CONTAINER_TYPE:' || id, json_build_object('type', 'CONTAINER_TYPE', 'op', 'UPSERT',
       'payload', json_build_object('id', id, 'code', code, 'name', name, 'category', category, 'seals', seals,
                                    'defaultCapacityG', default_capacity_g, 'active', active))::text
FROM container_types ORDER BY code;
