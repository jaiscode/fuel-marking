-- Transfers are either supplies out of a warehouse (SUPPLY, the only kind before) or finished jerricans sent back for a refill (RETURN).
CREATE OR REPLACE VIEW v_transfers AS
SELECT (payload -> 'transfer' ->> 'id')::uuid AS id,
       (payload -> 'transfer' ->> 'fromSiteId')::uuid AS from_site_id,
       (payload -> 'transfer' ->> 'toSiteId')::uuid AS to_site_id,
       payload -> 'transfer' ->> 'status' AS status,
       (payload -> 'transfer' ->> 'dispatchedAt')::timestamp AS dispatched_at,
       payload -> 'items' AS items,
       COALESCE(payload -> 'transfer' ->> 'kind', 'SUPPLY') AS kind
FROM synced_entities WHERE entity_type = 'TRANSFER';
