-- Warehouse stock counts (reconciliation of the rum in IBCs and drums), reported by the warehouse desktop.
CREATE VIEW v_stock_counts AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'countedBy')::uuid AS counted_by,
       (payload ->> 'countedAt')::timestamp AS counted_at,
       payload ->> 'comment' AS comment,
       (payload ->> 'totalSystemG')::numeric AS total_system_g,
       (payload ->> 'totalCountedG')::numeric AS total_counted_g,
       (payload ->> 'totalVarianceG')::numeric AS total_variance_g,
       payload -> 'lines' AS lines
FROM synced_entities WHERE entity_type = 'STOCK_COUNT';
CREATE INDEX idx_synced_stock_counts_site ON synced_entities ((payload ->> 'siteId'), (payload ->> 'countedAt')) WHERE entity_type = 'STOCK_COUNT';
