-- Each fuel type is marked with one particular rum type. A truck can carry a different fuel in each compartment, so the fuel (and with it the
-- rum type) is per compartment on the marking; the markings carry a breakdown per fuel, and a list of doses (one per rum type).
ALTER TABLE fuel_types ADD COLUMN rum_type_id UUID REFERENCES rum_types(id);
UPDATE fuel_types SET rum_type_id = 'a0000000-0000-4000-8000-000000000001' WHERE rum_type_id IS NULL;      -- the standard rum, until head office says otherwise
ALTER TABLE fuel_types ALTER COLUMN rum_type_id SET NOT NULL;

-- ... and delivered to the sites: the fuel types again, now with their rum type.
INSERT INTO master_data_outbox(msg_key, payload)
SELECT 'FUEL_TYPE:' || f.id, json_build_object('type', 'FUEL_TYPE', 'op', 'UPSERT',
       'payload', json_build_object('id', f.id, 'code', f.code, 'name', f.name, 'rumTypeId', f.rum_type_id, 'rumTypeCode', r.code, 'active', f.active))::text
FROM fuel_types f JOIN rum_types r ON r.id = f.rum_type_id ORDER BY f.code;

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
       payload ->> 'trailerNumber' AS trailer_number,
       -- litres and rum per fuel; a marking from before compartments carried a fuel counts as one fuel
       COALESCE(payload -> 'fuelBreakdown', jsonb_build_array(jsonb_build_object('fuelType', payload ->> 'fuelType', 'rumType', payload ->> 'rumType',
           'litres', (payload ->> 'declaredVolume')::numeric,
           'rumG', COALESCE((payload ->> 'doseMeasuredG')::numeric, (payload ->> 'doseCalculatedMl')::numeric, 0)))) AS fuel_breakdown,
       COALESCE(payload -> 'doses', '[]'::jsonb) AS doses
FROM synced_entities WHERE entity_type = 'TRUCK_MARKING';
