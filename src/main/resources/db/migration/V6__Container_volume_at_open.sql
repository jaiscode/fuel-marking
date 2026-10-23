-- Volume recorded when a jerrican's seals are opened; used for shift reconciliation
ALTER TABLE containers ADD COLUMN volume_at_open_ml DECIMAL(14,2);
