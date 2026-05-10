package com.groupon.sda.config;

import com.groupon.sda.ingest.dedup.InMemorySetCache;
import com.groupon.sda.ingest.dedup.SeenEventsCache;
import com.groupon.sda.queue.ArrayBlockingQueueAdapter;
import com.groupon.sda.queue.EventQueue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ingest pipeline's transport-layer beans: the bounded {@link EventQueue}
 * and the consumer-side {@link SeenEventsCache}.
 *
 * <p>Producer/consumer thread managers are added in a follow-up commit and consume
 * these beans plus the persistence and graph layers.
 */
@Configuration
@EnableConfigurationProperties(IngestProperties.class)
public class IngestConfig {

    @Bean
    public EventQueue eventQueue(IngestProperties props) {
        return new ArrayBlockingQueueAdapter(props.queueCapacity());
    }

    @Bean
    public SeenEventsCache seenEventsCache(IngestProperties props) {
        // Take-home default: exact in-memory set. Production swap point lives here —
        // a BloomFilterCache built from props.dedupCacheCapacity() and a target FPR
        // would replace this without changing any caller.
        return new InMemorySetCache();
    }
}
