-- Key/value state for the sync agent (e.g. the pull cursor)
CREATE TABLE sync_state (
    key VARCHAR(50) PRIMARY KEY,
    value VARCHAR(200) NOT NULL
);
