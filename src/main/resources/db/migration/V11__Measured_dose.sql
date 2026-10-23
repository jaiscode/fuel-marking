-- The dose is measured on the scale (tare an empty container, draw the dose, read the net weight), not just calculated.
-- dose_calculated_ml (historical name, grams) stays the REQUIRED amount; these hold what was actually weighed.
ALTER TABLE truck_markings
    ADD COLUMN dose_measured_g DECIMAL(12,3),
    ADD COLUMN dose_source VARCHAR(10);          -- SCALE (read from the scale) or MANUAL (typed in)
COMMENT ON COLUMN truck_markings.dose_measured_g IS 'Rum actually dosed, in g, as weighed (net of the tared container)';
COMMENT ON COLUMN truck_markings.dose_source IS 'SCALE = read from the digital scale, MANUAL = typed in by the operator';
