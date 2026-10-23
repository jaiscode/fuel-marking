-- A truck can pull a trailer or a wagon with its own number; recorded alongside the truck number (optional).
ALTER TABLE truck_markings ADD COLUMN trailer_number VARCHAR(50);
