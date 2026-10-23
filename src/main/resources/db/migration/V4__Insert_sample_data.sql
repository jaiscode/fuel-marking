-- src/main/resources/db/migration/V4__Insert_sample_data.sql
-- Insert some sample data for testing

-- Sample rum jerricans
INSERT INTO rum_jerricans (serial_number, weight, top_seal, bottom_seal, remaining_volume) VALUES
('RUM-001-2024', 15.50, 'TS-001', 'BS-001', 1000.00),
('RUM-002-2024', 15.45, 'TS-002', 'BS-002', 950.00),
('RUM-003-2024', 15.60, 'TS-003', 'BS-003', 1000.00);

-- Sample oil marketing companies data (you can add more as needed)
-- This is just for reference, you might want to create a separate table for companies
-- INSERT INTO oil_marketing_companies (name, code) VALUES
-- ('Indian Oil Corporation', 'IOCL'),
-- ('Bharat Petroleum', 'BPCL'),
-- ('Hindustan Petroleum', 'HPCL');