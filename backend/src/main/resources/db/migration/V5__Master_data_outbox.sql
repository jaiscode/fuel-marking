-- Master data changes waiting to be sent to the sites over Kafka. Written in the same transaction as the change itself,
-- so a Kafka outage can delay delivery but never lose it. Delivered strictly in id order.
CREATE TABLE master_data_outbox (
    id BIGSERIAL PRIMARY KEY,
    msg_key VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);
CREATE INDEX idx_master_data_outbox_pending ON master_data_outbox(id) WHERE published_at IS NULL;
CREATE INDEX idx_master_data_outbox_published ON master_data_outbox(published_at) WHERE published_at IS NOT NULL;
