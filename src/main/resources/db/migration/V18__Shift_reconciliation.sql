-- The supervisor can reconcile the opened jerricans at any time during a shift (and when closing it). It is a signed count like the
-- warehouse stock count, kept in the same tables: kind tells them apart, and a shortfall carries its cause and the incident raised for it.
ALTER TABLE stock_counts ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'WAREHOUSE' CHECK (kind IN ('WAREHOUSE', 'SHIFT'));
ALTER TABLE stock_counts ADD COLUMN shift_id UUID REFERENCES shifts(id);
ALTER TABLE stock_count_lines ADD COLUMN cause VARCHAR(20) CHECK (cause IN ('SPILLAGE', 'LEAKAGE', 'OTHER'));
ALTER TABLE stock_count_lines ADD COLUMN note VARCHAR(300);
ALTER TABLE stock_count_lines ADD COLUMN incident_id UUID REFERENCES incidents(id);
