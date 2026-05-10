package com.groupon.sda.events.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the synthetic event generator under {@code sda.events.generator.*}.
 *
 * @param enabled       master switch — when {@code false}, no generator bean is wired
 *                      and the producer threads have nothing to push (the system runs
 *                      as a "query-only" instance fed solely via the HTTP ingest API)
 * @param serviceCount  number of distinct service ids to fabricate (target ~5–10k)
 * @param eventCount    total number of events to emit (target ~50–200k)
 * @param hubCount      services that get a disproportionate share of incoming traffic
 *                      (databases, auth, cache — fan-in hubs)
 * @param cyclesToInject explicit dependency cycles to seed in the topology
 * @param errorRate     fraction of {@code dependency_observed} events with non-OK status
 * @param duplicateRate fraction of events that are an exact resend of a recent event id
 *                      (drives the dedup path during testing)
 * @param seed          random seed for reproducibility
 */
@ConfigurationProperties("sda.events.generator")
public record EventGeneratorProperties(
        boolean enabled,
        int serviceCount,
        int eventCount,
        int hubCount,
        int cyclesToInject,
        double errorRate,
        double duplicateRate,
        long seed
) {

    public EventGeneratorProperties {
        if (serviceCount < 2) {
            throw new IllegalArgumentException(
                    "sda.events.generator.service-count must be >= 2, got " + serviceCount);
        }
        if (eventCount < 0) {
            throw new IllegalArgumentException(
                    "sda.events.generator.event-count must be >= 0, got " + eventCount);
        }
        if (hubCount < 0 || hubCount > serviceCount) {
            throw new IllegalArgumentException(
                    "sda.events.generator.hub-count must be in [0, service-count], got " + hubCount);
        }
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException(
                    "sda.events.generator.error-rate must be in [0, 1], got " + errorRate);
        }
        if (duplicateRate < 0.0 || duplicateRate > 1.0) {
            throw new IllegalArgumentException(
                    "sda.events.generator.duplicate-rate must be in [0, 1], got " + duplicateRate);
        }
    }
}
