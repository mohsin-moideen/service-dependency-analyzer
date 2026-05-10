package com.groupon.sda.config;

import com.groupon.sda.ingest.dedup.InMemorySetCache;
import com.groupon.sda.ingest.dedup.SeenEventsCache;
import com.groupon.sda.queue.ArrayBlockingQueueAdapter;
import com.groupon.sda.queue.EventQueue;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Wires the ingest pipeline's transport-layer beans:
 * <ul>
 *   <li>The bounded {@link EventQueue} (sized from {@code sda.ingest.queue-capacity}).</li>
 *   <li>The consumer-side {@link SeenEventsCache} (exact in-memory set today;
 *       BloomFilterCache is the production swap).</li>
 *   <li>The Hikari {@link DataSource}, with its pool size tied to
 *       {@code sda.ingest.consumer-count} so the two settings can never drift apart.</li>
 * </ul>
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

    /**
     * Replaces Spring Boot's auto-configured {@code DataSource} so the Hikari pool size
     * is sourced from {@link IngestProperties#consumerCount()} instead of being a magic
     * number in {@code application.yml}.
     *
     * <p>Pool size = {@code consumerCount + 5}. SQLite serializes writes anyway, so we
     * can't extract more concurrency from the DB than {@code consumerCount} writers; the
     * {@code +5} headroom is for API request threads + scheduled maintenance running
     * alongside ingest.
     *
     * <p>Other Hikari knobs (notably {@code connection-init-sql}) come from
     * {@code spring.datasource.hikari.*} via {@link ConfigurationProperties}.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties props, IngestProperties ingest) {
        HikariDataSource ds = props.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setMaximumPoolSize(ingest.consumerCount() + 5);
        return ds;
    }
}
