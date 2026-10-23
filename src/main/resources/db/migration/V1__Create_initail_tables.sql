-- src/main/resources/db/migration/V1__Create_initial_tables.sql
-- Initial database schema for fuel marking system

-- Create rum_jerricans table
CREATE TABLE rum_jerricans (
    id SERIAL PRIMARY KEY,
    serial_number VARCHAR(100) UNIQUE NOT NULL,
    weight DECIMAL(10,2) NOT NULL,
    top_seal VARCHAR(100) NOT NULL,
    bottom_seal VARCHAR(100) NOT NULL,
    received_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    opened BOOLEAN DEFAULT FALSE,
    remaining_volume DECIMAL(10,2) DEFAULT 1000.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create truck_markings table
CREATE TABLE truck_markings (
    id SERIAL PRIMARY KEY,
    order_number VARCHAR(100) UNIQUE NOT NULL,
    oil_marketing_company VARCHAR(200) NOT NULL,
    truck_number VARCHAR(50) NOT NULL,
    fuel_type VARCHAR(20) NOT NULL,
    declared_volume DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'REGISTERED',
    registered_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    dosed_time TIMESTAMP,
    dispensed_time TIMESTAMP,
    completed_time TIMESTAMP,
    rum_used DECIMAL(10,3) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create compartment_details table
CREATE TABLE compartment_details (
    id SERIAL PRIMARY KEY,
    truck_marking_id INTEGER REFERENCES truck_markings(id) ON DELETE CASCADE,
    compartment_number VARCHAR(10) NOT NULL,
    capacity DECIMAL(10,2) NOT NULL,
    actual_volume DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Add comments to tables
COMMENT ON TABLE rum_jerricans IS 'Stores information about rum jerricans used for fuel marking';
COMMENT ON TABLE truck_markings IS 'Main table for tracking truck fuel marking operations';
COMMENT ON TABLE compartment_details IS 'Details of truck compartments for each marking operation';

-- Add column comments
COMMENT ON COLUMN rum_jerricans.serial_number IS 'Unique serial number of the jerrican';
COMMENT ON COLUMN rum_jerricans.weight IS 'Weight of the jerrican in kg';
COMMENT ON COLUMN rum_jerricans.remaining_volume IS 'Remaining rum volume in ml';
COMMENT ON COLUMN truck_markings.order_number IS 'Unique order number for the marking operation';
COMMENT ON COLUMN truck_markings.declared_volume IS 'Declared volume of fuel in liters';
COMMENT ON COLUMN truck_markings.rum_used IS 'Amount of rum used for marking in mg';