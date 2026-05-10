package com.groupon.sda.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration knobs under {@code sda.ingest.*} (see {@code application.yml}).
 *
 * @param producerCount        number of producer threads to spawn
 * @param consumerCount        number of consumer threads to spawn
 * @param queueCapacity        bounded capacity of the in-memory event queue
 * @param putTimeoutMs         when {@code > 0}, producers use {@code offer(timeout)} and
 *                             shed events on timeout (deliberate drop, counted +
 *                             logged). When {@code 0} (default), producers use {@code put}
 *                             and block indefinitely — the BLOCK backpressure policy.
 * @param dedupCacheCapacity   sizing hint for the {@code SeenEventsCache} implementation
 *                             (ignored by the exact-set default; used by the future
 *                             bloom-filter implementation)
 * @param closeQueueOnGeneratorExhaust when {@code true}, the producer watchdog closes
 *                             the queue once every synthetic-generator producer thread
 *                             exits — useful for short CLI/test runs that should
 *                             terminate when the seed dataset is fully consumed. When
 *                             {@code false} (default), the queue stays open so the HTTP
 *                             ingest endpoint can keep accepting events after the
 *                             synthetic generator has seeded the graph.
 */
@ConfigurationProperties("sda.ingest")
public record IngestProperties(
        int producerCount,
        int consumerCount,
        int queueCapacity,
        long putTimeoutMs,
        int dedupCacheCapacity,
        boolean closeQueueOnGeneratorExhaust
) {

    public IngestProperties {
        if (producerCount < 1) {
            throw new IllegalArgumentException(
                    "sda.ingest.producer-count must be >= 1, got " + producerCount);
        }
        if (consumerCount < 1) {
            throw new IllegalArgumentException(
                    "sda.ingest.consumer-count must be >= 1, got " + consumerCount);
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "sda.ingest.queue-capacity must be > 0, got " + queueCapacity);
        }
        if (putTimeoutMs < 0) {
            throw new IllegalArgumentException(
                    "sda.ingest.put-timeout-ms must be >= 0, got " + putTimeoutMs);
        }
    }

    /** True when shed-on-timeout is configured; false when producers should BLOCK. */
    public boolean shedOnTimeout() {
        return putTimeoutMs > 0;
    }
}
