package com.groupon.sda.domain.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record HeartbeatEvent(
        @JsonProperty("event_id") String eventId,
        Instant timestamp,
        String service
) implements Event {}
