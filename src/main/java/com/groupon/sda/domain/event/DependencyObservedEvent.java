package com.groupon.sda.domain.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record DependencyObservedEvent(
        @JsonProperty("event_id") String eventId,
        Instant timestamp,
        String source,
        String target,
        @JsonProperty("latency_ms") int latencyMs,
        Status status
) implements Event {

    public enum Status { ok, error, timeout }
}
