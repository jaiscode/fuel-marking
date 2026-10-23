-- Stock counts now come in two kinds: the warehouse manager's count of IBCs and drums (WAREHOUSE, the only kind before) and the
-- supervisor's reconciliation of the opened jerricans during a shift (SHIFT, which carries the cause of each shortfall).
CREATE OR REPLACE VIEW v_stock_counts AS
SELECT (payload ->> 'id')::uuid AS id,
       (payload ->> 'siteId')::uuid AS site_id,
       (payload ->> 'countedBy')::uuid AS counted_by,
       (payload ->> 'countedAt')::timestamp AS counted_at,
       payload ->> 'comment' AS comment,
       (payload ->> 'totalSystemG')::numeric AS total_system_g,
       (payload ->> 'totalCountedG')::numeric AS total_counted_g,
       (payload ->> 'totalVarianceG')::numeric AS total_variance_g,
       payload -> 'lines' AS lines,
       COALESCE(payload ->> 'kind', 'WAREHOUSE') AS kind,
       (payload ->> 'shiftId')::uuid AS shift_id
FROM synced_entities WHERE entity_type = 'STOCK_COUNT';
