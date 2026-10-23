package org.jai.fuelmarking.central.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class SyncDtos {
    private SyncDtos() {}

    public record Event(long outboxId, String entityType, String entityId, String operation, JsonNode payload,
                        String createdAt) {}

    public record PushRequest(List<Event> events) {}

    /** acked: stored. rejected: refused permanently (will never succeed, so the site should stop retrying them). */
    public record PushResponse(List<Long> acked, List<Rejection> rejected) {}

    public record Rejection(long outboxId, String reason) {}

    public record PullResponse(List<JsonNode> transfers, List<JsonNode> containers, long cursor) {}
}
