package com.groupon.sda.domain.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DependencyObservedEvent.class, name = "dependency_observed"),
        @JsonSubTypes.Type(value = DependencyRemovedEvent.class,  name = "dependency_removed"),
        @JsonSubTypes.Type(value = ServiceMetadataEvent.class,    name = "service_metadata"),
        @JsonSubTypes.Type(value = HeartbeatEvent.class,          name = "heartbeat"),
})
public sealed interface Event permits
        DependencyObservedEvent, DependencyRemovedEvent, ServiceMetadataEvent, HeartbeatEvent {

    String eventId();
    Instant timestamp();
}
