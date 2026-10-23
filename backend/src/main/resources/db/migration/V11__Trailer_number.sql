-- Trailer / wagon number reported with the truck (optional; absent in records from before it existed).
CREATE OR REPLACE VIEW v_truck_markings AS
SELECT (payload ->> 'uuid')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'shiftId')::uuid AS shift_id,
       (payload ->> 'omcId')::uuid AS omc_id,
       payload ->> 'orderNumber' AS order_number,
       payload ->> 'truckNumber' AS truck_number,
       payload ->> 'oilMarketingCompany' AS omc_name,
       payload ->> 'fuelType' AS fuel_type,
       (payload ->> 'declaredVolume')::numeric AS declared_volume_l,
       COALESCE((payload ->> 'doseMeasuredG')::numeric, (payload ->> 'doseCalculatedMl')::numeric, 0) AS rum_ml,
       payload ->> 'status' AS status,
       payload ->> 'rumType' AS rum_type,
       (payload ->> 'registeredTime')::timestamp AS registered_at,
       (payload ->> 'dosedTime')::timestamp AS dosed_at,
       (payload ->> 'dispensedTime')::timestamp AS dispensed_at,
       (payload ->> 'completedTime')::timestamp AS completed_at,
       payload -> 'compartments' AS compartments,
       NULLIF((payload ->> 'doseCalculatedMl')::numeric, 0) AS dose_required_g,
       (payload ->> 'doseMeasuredG')::numeric AS dose_measured_g,
       payload ->> 'doseSource' AS dose_source,
       payload ->> 'trailerNumber' AS trailer_number
FROM synced_entities WHERE entity_type = 'TRUCK_MARKING';
