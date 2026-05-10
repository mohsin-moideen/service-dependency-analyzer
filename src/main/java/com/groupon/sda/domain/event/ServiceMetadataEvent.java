package com.groupon.sda.domain.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

public record ServiceMetadataEvent(
        @JsonProperty("event_id") String eventId,
        Instant timestamp,
        String service,
        Map<String, String> attributes
) implements Event {}
