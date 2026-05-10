package com.groupon.sda.api;

import com.groupon.sda.domain.event.Event;
import com.groupon.sda.queue.PartitionedEventQueue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP entry point for publishing events. The controller's only job is to drop
 * incoming events into the same {@link EventQueue} the synthetic generator's producer
 * threads use; consumers don't care which path delivered an event.
 *
 * <p><b>Backpressure on the API path.</b> A blocking {@code put} on a full queue would
 * tie up the Tomcat worker thread indefinitely. We use {@link EventQueue#offer} with a
 * short timeout (1s) and translate timeout to HTTP 503 so the caller sees a structured
 * error and can retry. This is the only place backpressure surfaces to the outside
 * world; internal producers still BLOCK by default.
 */
@Tag(name = "Event Ingest", description = "Publish dependency events into the processing pipeline")
@RestController
@RequestMapping("/api/v1/events")
public class EventIngestController {

    private static final long INGEST_OFFER_TIMEOUT_MS = 1_000L;

    private final PartitionedEventQueue queues;

    public EventIngestController(PartitionedEventQueue queues) {
        this.queues = queues;
    }

    @Operation(summary = "Publish one event")
    @PostMapping
    public ResponseEntity<Void> publish(@RequestBody Event event) throws InterruptedException {
        boolean accepted = queues.tryPublish(event, INGEST_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!accepted) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Publish a batch of events")
    @PostMapping("/batch")
    public ResponseEntity<BatchAck> publishBatch(@RequestBody List<Event> events) throws InterruptedException {
        int accepted = 0;
        for (Event e : events) {
            if (queues.tryPublish(e, INGEST_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                accepted++;
            } else {
                // Stop on the first rejection to avoid amplifying backpressure;
                // caller can retry the remaining slice.
                break;
            }
        }
        BatchAck body = new BatchAck(events.size(), accepted, events.size() - accepted);
        if (accepted == events.size()) {
            return ResponseEntity.accepted().body(body);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    public record BatchAck(int submitted, int accepted, int rejected) {
    }
}
