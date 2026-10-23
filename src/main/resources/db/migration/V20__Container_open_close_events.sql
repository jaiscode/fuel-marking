-- A ledger of jerricans being opened and closed back (sealed again with rum still in them), with the rum they held at that moment.
-- The shift reconciliation adds up what was opened and takes off what was closed back; a jerrican can be opened and closed more than once.
CREATE TABLE container_events (
    id BIGSERIAL PRIMARY KEY,
    container_id UUID NOT NULL REFERENCES containers(id),
    site_id UUID NOT NULL,
    event_type VARCHAR(12) NOT NULL CHECK (event_type IN ('OPEN', 'CLOSE_BACK')),
    volume_g DECIMAL(14,2) NOT NULL,           -- grams of rum in the container at that moment
    at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_container_events_site ON container_events(site_id, event_type, at);

-- what is already on record: each jerrican's latest opening
INSERT INTO container_events(container_id, site_id, event_type, volume_g, at)
SELECT id, site_id, 'OPEN', volume_at_open_ml, opened_at FROM containers
WHERE opened_at IS NOT NULL AND volume_at_open_ml IS NOT NULL AND site_id IS NOT NULL AND container_type LIKE 'JERRICAN%';
