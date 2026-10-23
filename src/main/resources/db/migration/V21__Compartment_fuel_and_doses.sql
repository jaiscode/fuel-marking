-- A truck can carry a different fuel in each compartment, and each fuel type is marked with one particular rum type (set at head office).
-- So the fuel and rum type now belong to the compartment, a truck can need more than one dose (one per rum type), and each dose is a row.

-- every fuel type names the rum type it is marked with (the starting lists use the standard rum until head office says otherwise)
ALTER TABLE fuel_types ADD COLUMN rum_type_code VARCHAR(30);
UPDATE fuel_types SET rum_type_code = 'STANDARD' WHERE rum_type_code IS NULL;

ALTER TABLE compartment_details ADD COLUMN fuel_type_code VARCHAR(30);
ALTER TABLE compartment_details ADD COLUMN rum_type_code VARCHAR(30);
UPDATE compartment_details cd SET fuel_type_code = m.fuel_type, rum_type_code = m.rum_type
FROM truck_markings m WHERE m.id = cd.truck_marking_id;

-- a truck with several fuels shows them all ("DIESEL+PETROL"), so the summary columns need room
ALTER TABLE truck_markings ALTER COLUMN fuel_type TYPE VARCHAR(120);
ALTER TABLE truck_markings ALTER COLUMN rum_type TYPE VARCHAR(120);

CREATE TABLE marking_doses (
    id BIGSERIAL PRIMARY KEY,
    truck_marking_id INTEGER NOT NULL REFERENCES truck_markings(id) ON DELETE CASCADE,
    rum_type_code VARCHAR(30) NOT NULL,
    required_g DECIMAL(14,2) NOT NULL,           -- what the compartments with this rum type call for
    measured_g DECIMAL(14,2) NOT NULL,           -- what was weighed
    source VARCHAR(10) NOT NULL CHECK (source IN ('SCALE', 'MANUAL')),
    container_id UUID REFERENCES containers(id), -- the jerrican it was drawn from
    dosed_by UUID REFERENCES app_users(id),
    dosed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (truck_marking_id, rum_type_code)
);
CREATE INDEX idx_marking_doses_marking ON marking_doses(truck_marking_id);

-- what is already on record: each dosed truck had one dose
INSERT INTO marking_doses(truck_marking_id, rum_type_code, required_g, measured_g, source, container_id, dosed_by, dosed_at)
SELECT id, COALESCE(rum_type, 'STANDARD'), COALESCE(dose_calculated_ml, 0), COALESCE(dose_measured_g, dose_calculated_ml, 0),
       COALESCE(dose_source, 'MANUAL'), dose_container_id, dosed_by, dosed_time
FROM truck_markings WHERE dosed_time IS NOT NULL;
