-- The seal numbers on a container are shown in the web app's container list (and can be searched).
CREATE OR REPLACE VIEW v_containers AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       payload ->> 'barcode' AS barcode,
       payload ->> 'type' AS container_type,
       payload ->> 'status' AS status,
       (payload ->> 'capacityMl')::numeric AS capacity_ml,
       (payload ->> 'currentVolumeMl')::numeric AS volume_ml,
       payload ->> 'topSeal' AS top_seal,
       payload ->> 'bottomSeal' AS bottom_seal
FROM synced_entities WHERE entity_type = 'CONTAINER';
