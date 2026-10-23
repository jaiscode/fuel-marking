-- Locations: the places a mobile marking site can work. Head office keeps the list (master data, delivered to the sites like the rest, so it is
-- there offline too). A shift records where it was done: required at a mobile site, optional at a fixed one.
CREATE TABLE locations (
    id UUID PRIMARY KEY,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE shifts ADD COLUMN location_id UUID;
ALTER TABLE shifts ADD COLUMN location_name VARCHAR(100);       -- the name as it was when the shift started, so a later rename does not rewrite history
