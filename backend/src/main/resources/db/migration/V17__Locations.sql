-- Locations: the places a mobile marking site can work. Master data, delivered to the sites; a shift records where it was done.
CREATE TABLE locations (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP VIEW v_shifts;
CREATE VIEW v_shifts AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'supervisorId')::uuid AS supervisor_id,
       payload ->> 'status' AS status,
       (payload ->> 'startedAt')::timestamp AS started_at,
       (payload ->> 'endedAt')::timestamp AS ended_at,
       (payload ->> 'openingRumMl')::numeric AS opening_ml,
       (payload ->> 'closingRumMl')::numeric AS closing_ml,
       (payload ->> 'varianceMl')::numeric AS variance_ml,
       (payload ->> 'locationId')::uuid AS location_id,
       payload ->> 'locationName' AS location_name
FROM synced_entities WHERE entity_type = 'SHIFT';
