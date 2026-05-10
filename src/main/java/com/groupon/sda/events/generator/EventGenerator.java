package com.groupon.sda.events.generator;

/**
 * Synthetic event generator for tests and load.
 *
 * The spec asks for ~5,000–10,000 services and ~50,000–200,000 events with realistic shape:
 *   - A few high-fan-in services (databases, auth)
 *   - Mostly modest fan-out
 *   - At least a couple of cycles
 *   - A long tail of latencies
 *   - A non-trivial error rate
 *   - All event types interleaved, including removals and metadata updates
 *
 * Commit either the generator or a generated dataset to the repo.
 */
public class EventGenerator {
    // TODO: generation logic. Expose a CLI entry point or a Spring @Bean.
}
