-- Who changed master data, when. Append-only; never stores secrets.
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor VARCHAR(200) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    action VARCHAR(50) NOT NULL,
    target VARCHAR(200),
    detail VARCHAR(500)
);
CREATE INDEX idx_audit_log_at ON audit_log(at);
