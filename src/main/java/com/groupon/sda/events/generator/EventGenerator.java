package com.groupon.sda.events.generator;

import com.groupon.sda.domain.event.Event;

/**
 * Source of events for the producer threads. Thread-safe.
 *
 * <p>The default implementation, {@link SyntheticEventGenerator}, pre-builds a finite
 * batch of events at startup with the shape the spec asks for (~5–10k services,
 * ~50–200k events, hubs, cycles, error tail, mix of all event types). Producers call
 * {@link #next()} until it returns {@code null}, signalling the batch is exhausted.
 *
 * <p>An external HTTP-driven workload bypasses this entirely — it goes straight to
 * the {@code EventIngestController} and into the queue.
 */
public interface EventGenerator {

    /**
     * @return the next event, or {@code null} if the generator is exhausted.
     *         Safe to call from many threads.
     */
    Event next();

    /** Total number of events this generator will produce. Used for logging only. */
    int totalEvents();
}
