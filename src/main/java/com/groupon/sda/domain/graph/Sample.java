package com.groupon.sda.domain.graph;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;

import java.time.Instant;

/**
 * A single observation on an edge: one call from {@code source} to {@code target} with
 * a measured latency and a status. Buffered in {@link Edge#recentSamples} for the
 * {@code health(service, window)} query.
 */
public record Sample(Instant ts, int latencyMs, Status status) {
}
