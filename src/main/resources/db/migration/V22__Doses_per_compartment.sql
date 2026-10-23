-- Dosing is per compartment: each compartment is dosed on its own, against its own target (its litres over 1,000 times the dosage), from a
-- jerrican of the rum type its fuel is marked with. Until now there was one dose per rum type.
ALTER TABLE marking_doses ADD COLUMN compartment_number VARCHAR(20);
ALTER TABLE marking_doses DROP CONSTRAINT IF EXISTS marking_doses_truck_marking_id_rum_type_code_key;

-- what is already on record: spread each dose over the compartments that carry its rum type, by their litres
INSERT INTO marking_doses(truck_marking_id, rum_type_code, required_g, measured_g, source, container_id, dosed_by, dosed_at, compartment_number)
SELECT d.truck_marking_id, d.rum_type_code,
       d.required_g * cd.actual_volume / t.litres, d.measured_g * cd.actual_volume / t.litres,
       d.source, d.container_id, d.dosed_by, d.dosed_at, cd.compartment_number
FROM marking_doses d
JOIN compartment_details cd ON cd.truck_marking_id = d.truck_marking_id AND cd.rum_type_code = d.rum_type_code
JOIN (SELECT truck_marking_id, rum_type_code, sum(actual_volume) AS litres FROM compartment_details GROUP BY 1, 2) t
     ON t.truck_marking_id = d.truck_marking_id AND t.rum_type_code = d.rum_type_code
WHERE d.compartment_number IS NULL AND t.litres > 0;
DELETE FROM marking_doses d WHERE d.compartment_number IS NULL AND EXISTS (
    SELECT 1 FROM compartment_details cd JOIN (SELECT truck_marking_id, rum_type_code, sum(actual_volume) AS litres FROM compartment_details GROUP BY 1, 2) t
        ON t.truck_marking_id = cd.truck_marking_id AND t.rum_type_code = cd.rum_type_code
    WHERE cd.truck_marking_id = d.truck_marking_id AND cd.rum_type_code = d.rum_type_code AND t.litres > 0);
-- a truck recorded without compartments had one dose for the whole truck
UPDATE marking_doses SET compartment_number = 'ALL' WHERE compartment_number IS NULL;
ALTER TABLE marking_doses ALTER COLUMN compartment_number SET NOT NULL;
ALTER TABLE marking_doses ADD CONSTRAINT marking_doses_one_per_compartment UNIQUE (truck_marking_id, compartment_number);
