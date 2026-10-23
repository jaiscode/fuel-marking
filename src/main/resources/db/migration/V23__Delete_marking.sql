-- A marking can be deleted until its first dose. It is kept (who deleted it and when) but it no longer counts anywhere.
ALTER TABLE truck_markings DROP CONSTRAINT IF EXISTS chk_truck_markings_status;
ALTER TABLE truck_markings ADD CONSTRAINT chk_truck_markings_status CHECK (status IN ('REGISTERED', 'DOSED', 'DISPENSED', 'COMPLETED', 'DELETED'));
ALTER TABLE truck_markings ADD COLUMN deleted_time TIMESTAMP;
ALTER TABLE truck_markings ADD COLUMN deleted_by UUID REFERENCES app_users(id);
