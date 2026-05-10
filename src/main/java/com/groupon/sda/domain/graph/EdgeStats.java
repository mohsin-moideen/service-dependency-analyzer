package com.groupon.sda.domain.graph;

/**
 * Post-mutation stats snapshot of a single {@link Edge}. Returned by
 * {@code ServiceGraph.applyDependencyObserved} so the consumer can persist the
 * authoritative rolling-average + sample-count pair without taking an extra read lock.
 */
public record EdgeStats(double rollingAvgLatencyMs, long sampleCount) {
}
