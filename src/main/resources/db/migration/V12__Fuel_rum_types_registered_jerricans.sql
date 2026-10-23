-- Fuel types, rum types and the jerricans head office has registered arrive from the web app (master data, over Kafka).
-- The starting lists below carry the same ids as at head office, so a site that has not synced yet already has them.
ALTER TABLE truck_markings DROP CONSTRAINT IF EXISTS chk_truck_markings_fuel_type;
ALTER TABLE truck_markings ALTER COLUMN fuel_type TYPE VARCHAR(30);

CREATE TABLE fuel_types (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rum_types (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A jerrican head office knows about: its material decides the seals (plastic 1, metal 2), capacity is in grams of rum.
CREATE TABLE registered_jerricans (
    id UUID PRIMARY KEY,
    barcode VARCHAR(100) UNIQUE NOT NULL,
    material VARCHAR(10) NOT NULL CHECK (material IN ('PLASTIC', 'METAL')),
    capacity_g DECIMAL(14,2) NOT NULL CHECK (capacity_g > 0),
    rum_type_code VARCHAR(30) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE containers ADD COLUMN rum_type_code VARCHAR(30);
COMMENT ON COLUMN containers.rum_type_code IS 'The rum type the container holds (a jerrican: the one it is registered for).';

INSERT INTO fuel_types(id, code, name) VALUES
    ('f0000000-0000-4000-8000-000000000001', 'PETROL', 'Petrol'),
    ('f0000000-0000-4000-8000-000000000002', 'DIESEL', 'Diesel'),
    ('f0000000-0000-4000-8000-000000000003', 'KEROSENE', 'Kerosene');
INSERT INTO rum_types(id, code, name) VALUES ('a0000000-0000-4000-8000-000000000001', 'STANDARD', 'Standard');
