-- Master data (created in the web app, synced down via Kafka)
CREATE TABLE marking_sites (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(200) NOT NULL,
    site_type VARCHAR(20) NOT NULL DEFAULT 'FIXED',   -- FIXED | MOBILE | WAREHOUSE
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE oil_marketing_companies (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(60) UNIQUE NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    role VARCHAR(30) NOT NULL,   -- WAREHOUSE_MANAGER | SUPERVISOR | DOSING_OPERATOR | DISPENSE_OPERATOR
    site_id UUID REFERENCES marking_sites(id),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Shifts
CREATE TABLE shifts (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES marking_sites(id),
    supervisor_id UUID NOT NULL REFERENCES app_users(id),
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',       -- OPEN | CLOSED
    opening_rum_ml DECIMAL(12,2),
    closing_rum_ml DECIMAL(12,2),
    reconciliation_variance_ml DECIMAL(12,2),
    notes TEXT
);
CREATE UNIQUE INDEX uq_one_open_shift_per_site ON shifts(site_id) WHERE status = 'OPEN';

-- Containers: IBC / drum (bulk) and jerricans (plastic / metal) share one inventory table
CREATE TABLE containers (
    id UUID PRIMARY KEY,
    barcode VARCHAR(100) UNIQUE NOT NULL,
    container_type VARCHAR(20) NOT NULL,              -- IBC | DRUM | JERRICAN_PLASTIC | JERRICAN_METAL
    capacity_ml DECIMAL(14,2) NOT NULL,
    tare_weight_kg DECIMAL(10,3),
    current_volume_ml DECIMAL(14,2) NOT NULL DEFAULT 0,
    top_seal VARCHAR(100),
    bottom_seal VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'IN_STOCK',   -- IN_STOCK | IN_TRANSIT | SEALED | OPENED | EMPTY
    site_id UUID REFERENCES marking_sites(id),        -- current location
    source_container_id UUID REFERENCES containers(id), -- IBC/drum a jerrican was refilled from
    received_at TIMESTAMP,
    opened_at TIMESTAMP,
    opened_by UUID REFERENCES app_users(id),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_containers_site_status ON containers(site_id, status);

-- Warehouse transfers to sites
CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    from_site_id UUID NOT NULL REFERENCES marking_sites(id),
    to_site_id UUID NOT NULL REFERENCES marking_sites(id),
    status VARCHAR(20) NOT NULL DEFAULT 'DISPATCHED', -- DISPATCHED | RECEIVED
    dispatched_by UUID REFERENCES app_users(id),
    dispatched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_by UUID REFERENCES app_users(id),
    received_at TIMESTAMP
);
CREATE TABLE transfer_items (
    transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    container_id UUID NOT NULL REFERENCES containers(id),
    weight_kg_at_dispatch DECIMAL(10,3),
    weight_kg_at_receipt DECIMAL(10,3),
    PRIMARY KEY (transfer_id, container_id)
);

-- Extend truck markings
ALTER TABLE truck_markings
    ADD COLUMN uuid UUID,
    ADD COLUMN site_id UUID REFERENCES marking_sites(id),
    ADD COLUMN shift_id UUID REFERENCES shifts(id),
    ADD COLUMN omc_id UUID REFERENCES oil_marketing_companies(id),
    ADD COLUMN rum_type VARCHAR(50),
    ADD COLUMN dose_calculated_ml DECIMAL(10,3),
    ADD COLUMN dose_container_id UUID REFERENCES containers(id),
    ADD COLUMN registered_by UUID REFERENCES app_users(id),
    ADD COLUMN dosed_by UUID REFERENCES app_users(id),
    ADD COLUMN dispensed_by UUID REFERENCES app_users(id),
    ADD COLUMN verified_by UUID REFERENCES app_users(id),
    ADD COLUMN verified_time TIMESTAMP;
UPDATE truck_markings SET uuid = gen_random_uuid() WHERE uuid IS NULL;
ALTER TABLE truck_markings ALTER COLUMN uuid SET NOT NULL;
CREATE UNIQUE INDEX uq_truck_markings_uuid ON truck_markings(uuid);

-- Signatures captured at each workflow step (PNG from signature pad)
CREATE TABLE signatures (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL,   -- TRUCK_MARKING | SHIFT | TRANSFER | INCIDENT
    entity_id UUID NOT NULL,
    step VARCHAR(30) NOT NULL,          -- REGISTRATION | DOSING | DISPENSING | VERIFICATION | SHIFT_CLOSE ...
    signed_by UUID NOT NULL REFERENCES app_users(id),
    signature_png BYTEA NOT NULL,
    signed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_signatures_entity ON signatures(entity_type, entity_id);

-- Incidents (spillage / leakage)
CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES marking_sites(id),
    shift_id UUID REFERENCES shifts(id),
    container_id UUID REFERENCES containers(id),
    truck_marking_id INTEGER REFERENCES truck_markings(id),
    incident_type VARCHAR(20) NOT NULL,   -- SPILLAGE | LEAKAGE
    quantity_ml DECIMAL(12,2) NOT NULL,
    description TEXT,
    reported_by UUID NOT NULL REFERENCES app_users(id),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Sync outbox: every local write is queued here and pushed to the central backend
CREATE TABLE sync_outbox (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    operation VARCHAR(10) NOT NULL,       -- UPSERT | DELETE
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    synced_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE INDEX idx_sync_outbox_pending ON sync_outbox(id) WHERE synced_at IS NULL;
