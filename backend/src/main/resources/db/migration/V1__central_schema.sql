-- Master data: owned by the web app, distributed to sites over Kafka
CREATE TABLE marking_sites (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(200) NOT NULL,
    site_type VARCHAR(20) NOT NULL DEFAULT 'FIXED',
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
    role VARCHAR(30) NOT NULL,
    site_id UUID REFERENCES marking_sites(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One API key per site installation (only a SHA-256 hash is stored)
CREATE TABLE site_credentials (
    site_id UUID PRIMARY KEY REFERENCES marking_sites(id),
    api_key_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Everything the sites push: latest version of each entity, as a JSON document.
-- seq orders changes for pull-based consumers (transfers).
CREATE SEQUENCE synced_entities_seq;
CREATE TABLE synced_entities (
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    source_site_id UUID NOT NULL REFERENCES marking_sites(id),
    outbox_id BIGINT NOT NULL,
    operation VARCHAR(10) NOT NULL,
    payload JSONB NOT NULL,
    source_created_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    seq BIGINT NOT NULL DEFAULT nextval('synced_entities_seq'),
    PRIMARY KEY (entity_type, entity_id)
);
CREATE INDEX idx_synced_entities_seq ON synced_entities(entity_type, seq);
CREATE INDEX idx_synced_entities_site ON synced_entities(source_site_id, entity_type);
CREATE INDEX idx_synced_containers_barcode ON synced_entities ((payload ->> 'barcode')) WHERE entity_type = 'CONTAINER';
CREATE INDEX idx_synced_transfers_to ON synced_entities ((payload -> 'transfer' ->> 'toSiteId')) WHERE entity_type = 'TRANSFER';
CREATE INDEX idx_synced_transfers_from ON synced_entities ((payload -> 'transfer' ->> 'fromSiteId')) WHERE entity_type = 'TRANSFER';
