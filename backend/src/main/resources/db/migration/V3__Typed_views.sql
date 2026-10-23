-- Typed, queryable views over the JSON documents pushed by the sites.
-- Push validation (PayloadChecks) guarantees the casts below cannot fail.

CREATE VIEW v_truck_markings AS
SELECT (payload ->> 'uuid')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'shiftId')::uuid AS shift_id,
       (payload ->> 'omcId')::uuid AS omc_id,
       payload ->> 'orderNumber' AS order_number,
       payload ->> 'truckNumber' AS truck_number,
       payload ->> 'oilMarketingCompany' AS omc_name,
       payload ->> 'fuelType' AS fuel_type,
       (payload ->> 'declaredVolume')::numeric AS declared_volume_l,
       COALESCE((payload ->> 'doseCalculatedMl')::numeric, 0) AS rum_ml,
       payload ->> 'status' AS status,
       payload ->> 'rumType' AS rum_type,
       (payload ->> 'registeredTime')::timestamp AS registered_at,
       (payload ->> 'dosedTime')::timestamp AS dosed_at,
       (payload ->> 'dispensedTime')::timestamp AS dispensed_at,
       (payload ->> 'completedTime')::timestamp AS completed_at,
       payload -> 'compartments' AS compartments
FROM synced_entities WHERE entity_type = 'TRUCK_MARKING';

CREATE VIEW v_shifts AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'supervisorId')::uuid AS supervisor_id,
       payload ->> 'status' AS status,
       (payload ->> 'startedAt')::timestamp AS started_at,
       (payload ->> 'endedAt')::timestamp AS ended_at,
       (payload ->> 'openingRumMl')::numeric AS opening_ml,
       (payload ->> 'closingRumMl')::numeric AS closing_ml,
       (payload ->> 'varianceMl')::numeric AS variance_ml
FROM synced_entities WHERE entity_type = 'SHIFT';

CREATE VIEW v_incidents AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'shiftId')::uuid AS shift_id,
       (payload ->> 'containerId')::uuid AS container_id,
       (payload ->> 'reportedBy')::uuid AS reported_by,
       payload ->> 'type' AS incident_type,
       (payload ->> 'quantityMl')::numeric AS quantity_ml,
       payload ->> 'description' AS description,
       (payload ->> 'occurredAt')::timestamp AS occurred_at
FROM synced_entities WHERE entity_type = 'INCIDENT';

CREATE VIEW v_containers AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       payload ->> 'barcode' AS barcode,
       payload ->> 'type' AS container_type,
       payload ->> 'status' AS status,
       (payload ->> 'capacityMl')::numeric AS capacity_ml,
       (payload ->> 'currentVolumeMl')::numeric AS volume_ml
FROM synced_entities WHERE entity_type = 'CONTAINER';

CREATE VIEW v_transfers AS
SELECT (payload -> 'transfer' ->> 'id')::uuid AS id,
       (payload -> 'transfer' ->> 'fromSiteId')::uuid AS from_site_id,
       (payload -> 'transfer' ->> 'toSiteId')::uuid AS to_site_id,
       payload -> 'transfer' ->> 'status' AS status,
       (payload -> 'transfer' ->> 'dispatchedAt')::timestamp AS dispatched_at,
       payload -> 'items' AS items
FROM synced_entities WHERE entity_type = 'TRANSFER';

-- Signature metadata only; the image stays in the document and is served by id
CREATE VIEW v_signatures AS
SELECT (payload ->> 'id')::uuid AS id,
       payload ->> 'entityType' AS entity_type,
       (payload ->> 'entityId')::uuid AS entity_id,
       payload ->> 'step' AS step,
       (payload ->> 'signedBy')::uuid AS signed_by,
       source_created_at AS signed_at
FROM synced_entities WHERE entity_type = 'SIGNATURE';

CREATE INDEX idx_synced_markings_site_time ON synced_entities
    (((payload ->> 'siteId')), ((payload ->> 'registeredTime'))) WHERE entity_type = 'TRUCK_MARKING';
CREATE INDEX idx_synced_signatures_entity ON synced_entities
    (((payload ->> 'entityId'))) WHERE entity_type = 'SIGNATURE';
