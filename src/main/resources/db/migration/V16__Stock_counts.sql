-- The warehouse manager counts the rum in the site's IBCs and drums and signs the result. The count is a record only:
-- the system's stock is not changed by it (a difference is dealt with separately, e.g. as an incident).
CREATE TABLE stock_counts (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES marking_sites(id),
    counted_by UUID NOT NULL REFERENCES app_users(id),
    counted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    comment VARCHAR(500),
    total_system_g DECIMAL(16,2) NOT NULL,     -- grams of rum, like every rum quantity
    total_counted_g DECIMAL(16,2) NOT NULL
);
CREATE INDEX idx_stock_counts_site ON stock_counts(site_id, counted_at DESC);

CREATE TABLE stock_count_lines (
    id BIGSERIAL PRIMARY KEY,
    stock_count_id UUID NOT NULL REFERENCES stock_counts(id) ON DELETE CASCADE,
    container_id UUID NOT NULL REFERENCES containers(id),
    barcode VARCHAR(100) NOT NULL,
    container_type VARCHAR(30) NOT NULL,
    rum_type_code VARCHAR(30),
    system_g DECIMAL(14,2) NOT NULL,
    counted_g DECIMAL(14,2) NOT NULL,
    source VARCHAR(10) NOT NULL CHECK (source IN ('SCALE', 'MANUAL'))
);
CREATE INDEX idx_stock_count_lines_count ON stock_count_lines(stock_count_id);
