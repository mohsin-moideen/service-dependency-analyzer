package com.groupon.sda.api;

import com.groupon.sda.domain.event.Event;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Event Ingest", description = "Publish dependency events into the processing pipeline")
@RestController
@RequestMapping("/api/v1/events")
public class EventIngestController {

    @Operation(summary = "Publish one event")
    @PostMapping
    public ResponseEntity<Void> publish(@RequestBody Event event) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Operation(summary = "Publish a batch of events")
    @PostMapping("/batch")
    public ResponseEntity<Void> publishBatch(@RequestBody List<Event> events) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
