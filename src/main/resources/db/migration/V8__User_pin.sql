-- PIN hash is replicated from the central system; the lockout counters are local to this installation
ALTER TABLE app_users
    ADD COLUMN pin_hash VARCHAR(200),
    ADD COLUMN failed_pin_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMP;
