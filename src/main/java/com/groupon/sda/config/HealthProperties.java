package com.groupon.sda.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code health(service, window)} query and per-edge sample
 * retention. Keys live under {@code sda.health.*}.
 *
 * @param windowSeconds        default trailing window for the health query
 * @param maxSamplesPerEdge    hard cap on per-edge in-memory sample deque (size guard)
 */
@ConfigurationProperties("sda.health")
public record HealthProperties(
        int windowSeconds,
        int maxSamplesPerEdge
) {

    public HealthProperties {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "sda.health.window-seconds must be > 0, got " + windowSeconds);
        }
        if (maxSamplesPerEdge <= 0) {
            throw new IllegalArgumentException(
                    "sda.health.max-samples-per-edge must be > 0, got " + maxSamplesPerEdge);
        }
    }
}
