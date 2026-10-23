-- src/main/resources/db/migration/V3__Create_triggers_and_functions.sql
-- Create triggers and functions for automatic timestamp updates

-- Function to update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Triggers for rum_jerricans
CREATE TRIGGER update_rum_jerricans_updated_at
    BEFORE UPDATE ON rum_jerricans
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Triggers for truck_markings
CREATE TRIGGER update_truck_markings_updated_at
    BEFORE UPDATE ON truck_markings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Add constraint checks
ALTER TABLE truck_markings
ADD CONSTRAINT chk_truck_markings_status
CHECK (status IN ('REGISTERED', 'DOSED', 'DISPENSED', 'COMPLETED'));

ALTER TABLE truck_markings
ADD CONSTRAINT chk_truck_markings_fuel_type
CHECK (fuel_type IN ('PETROL', 'DIESEL', 'KEROSENE'));

ALTER TABLE truck_markings
ADD CONSTRAINT chk_truck_markings_declared_volume
CHECK (declared_volume > 0);

ALTER TABLE rum_jerricans
ADD CONSTRAINT chk_rum_jerricans_weight
CHECK (weight > 0);

ALTER TABLE rum_jerricans
ADD CONSTRAINT chk_rum_jerricans_remaining_volume
CHECK (remaining_volume >= 0);

ALTER TABLE compartment_details
ADD CONSTRAINT chk_compartment_details_capacity
CHECK (capacity > 0);

ALTER TABLE compartment_details
ADD CONSTRAINT chk_compartment_details_actual_volume
CHECK (actual_volume >= 0);