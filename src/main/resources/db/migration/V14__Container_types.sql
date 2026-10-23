-- Container types arrive from the web app (master data): bulk containers (IBC, drum ...) and jerricans (plastic, metal ...), each
-- with the number of seals it carries and an optional usual capacity. The starting list has the same ids and codes as at head office,
-- and the codes are what containers already store, so nothing existing changes.
CREATE TABLE container_types (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(10) NOT NULL CHECK (category IN ('BULK', 'JERRICAN')),
    seals INTEGER NOT NULL CHECK (seals BETWEEN 0 AND 2),
    default_capacity_g DECIMAL(14,2),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO container_types(id, code, name, category, seals, default_capacity_g) VALUES
    ('c0000000-0000-4000-8000-000000000001', 'IBC', 'IBC', 'BULK', 0, NULL),
    ('c0000000-0000-4000-8000-000000000002', 'DRUM', 'Drum', 'BULK', 0, NULL),
    ('c0000000-0000-4000-8000-000000000003', 'JERRICAN_PLASTIC', 'Plastic jerrican', 'JERRICAN', 1, 22000),
    ('c0000000-0000-4000-8000-000000000004', 'JERRICAN_METAL', 'Metal jerrican', 'JERRICAN', 2, 18000);

ALTER TABLE registered_jerricans ADD COLUMN container_type_code VARCHAR(30);
UPDATE registered_jerricans SET container_type_code = CASE material WHEN 'METAL' THEN 'JERRICAN_METAL' ELSE 'JERRICAN_PLASTIC' END;
ALTER TABLE registered_jerricans ALTER COLUMN container_type_code SET NOT NULL;
ALTER TABLE registered_jerricans DROP COLUMN material;
