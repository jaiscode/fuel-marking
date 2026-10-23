-- More master data owned by the web app and replicated to the sites over Kafka:
-- the fuel types a truck can carry, the rum types, and the jerricans head office has registered (id, material, capacity, rum type).
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

CREATE TABLE registered_jerricans (
    id UUID PRIMARY KEY,
    barcode VARCHAR(100) UNIQUE NOT NULL,
    material VARCHAR(10) NOT NULL CHECK (material IN ('PLASTIC', 'METAL')),   -- decides the seals: plastic 1, metal 2
    capacity_g NUMERIC(14,2) NOT NULL CHECK (capacity_g > 0),                 -- grams of rum, like every rum quantity
    rum_type_id UUID NOT NULL REFERENCES rum_types(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Starting lists (the same ids are built into the desktop, so a site that has not synced yet already has them).
INSERT INTO fuel_types(id, code, name) VALUES
    ('f0000000-0000-4000-8000-000000000001', 'PETROL', 'Petrol'),
    ('f0000000-0000-4000-8000-000000000002', 'DIESEL', 'Diesel'),
    ('f0000000-0000-4000-8000-000000000003', 'KEROSENE', 'Kerosene');
INSERT INTO rum_types(id, code, name) VALUES ('a0000000-0000-4000-8000-000000000001', 'STANDARD', 'Standard');

-- ... and delivered to the sites with the rest of the master data.
INSERT INTO master_data_outbox(msg_key, payload)
SELECT 'FUEL_TYPE:' || id, json_build_object('type', 'FUEL_TYPE', 'op', 'UPSERT',
       'payload', json_build_object('id', id, 'code', code, 'name', name, 'active', active))::text FROM fuel_types ORDER BY code;
INSERT INTO master_data_outbox(msg_key, payload)
SELECT 'RUM_TYPE:' || id, json_build_object('type', 'RUM_TYPE', 'op', 'UPSERT',
       'payload', json_build_object('id', id, 'code', code, 'name', name, 'active', active))::text FROM rum_types;
