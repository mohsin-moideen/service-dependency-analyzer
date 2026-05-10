package com.groupon.sda.domain.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record DependencyRemovedEvent(
        @JsonProperty("event_id") String eventId,
        Instant timestamp,
        String source,
        String target
) implements Event {}
