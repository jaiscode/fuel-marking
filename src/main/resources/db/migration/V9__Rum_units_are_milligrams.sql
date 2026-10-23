-- Rum quantities are expressed in milligrams (mg): the dose is 10 mg per 1000 L of fuel, and container contents,
-- reconciliation and incident losses use the same unit. Column names ending in _ml are historical; the values are mg.
COMMENT ON COLUMN truck_markings.rum_used IS 'Amount of rum used for marking in mg';
COMMENT ON COLUMN truck_markings.dose_calculated_ml IS 'Rum dose calculated for the declared volume, in mg (10 mg per 1000 L). Column name is historical.';
COMMENT ON COLUMN rum_jerricans.remaining_volume IS 'Remaining rum in mg';
COMMENT ON COLUMN containers.capacity_ml IS 'Container capacity for rum, in mg. Column name is historical.';
COMMENT ON COLUMN containers.current_volume_ml IS 'Rum currently in the container, in mg. Column name is historical.';
COMMENT ON COLUMN containers.volume_at_open_ml IS 'Rum in the container when its seals were opened, in mg. Column name is historical.';
COMMENT ON COLUMN incidents.quantity_ml IS 'Rum lost in the incident, in mg. Column name is historical.';
COMMENT ON COLUMN shifts.opening_rum_ml IS 'Rum in opened jerricans at shift start, in mg. Column name is historical.';
COMMENT ON COLUMN shifts.closing_rum_ml IS 'Rum in opened jerricans at shift end, in mg. Column name is historical.';
COMMENT ON COLUMN shifts.reconciliation_variance_ml IS 'Closing minus expected rum, in mg. Column name is historical.';
