-- src/main/resources/db/migration/V2__Create_indexes.sql
-- Create indexes for better query performance

-- Indexes on rum_jerricans
CREATE INDEX idx_rum_jerricans_serial_number ON rum_jerricans(serial_number);
CREATE INDEX idx_rum_jerricans_opened ON rum_jerricans(opened);
CREATE INDEX idx_rum_jerricans_received_date ON rum_jerricans(received_date);

-- Indexes on truck_markings
CREATE INDEX idx_truck_markings_status ON truck_markings(status);
CREATE INDEX idx_truck_markings_order_number ON truck_markings(order_number);
CREATE INDEX idx_truck_markings_truck_number ON truck_markings(truck_number);
CREATE INDEX idx_truck_markings_registered_time ON truck_markings(registered_time);
CREATE INDEX idx_truck_markings_fuel_type ON truck_markings(fuel_type);

-- Indexes on compartment_details
CREATE INDEX idx_compartment_details_truck_marking_id ON compartment_details(truck_marking_id);
CREATE INDEX idx_compartment_details_compartment_number ON compartment_details(compartment_number);