-- An incident says which rum was lost (absent on incidents reported before it was tracked).
CREATE OR REPLACE VIEW v_incidents AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'shiftId')::uuid AS shift_id,
       (payload ->> 'containerId')::uuid AS container_id,
       (payload ->> 'reportedBy')::uuid AS reported_by,
       payload ->> 'type' AS incident_type,
       (payload ->> 'quantityMl')::numeric AS quantity_ml,
       payload ->> 'description' AS description,
       (payload ->> 'occurredAt')::timestamp AS occurred_at,
       payload ->> 'rumTypeCode' AS rum_type
FROM synced_entities WHERE entity_type = 'INCIDENT';
