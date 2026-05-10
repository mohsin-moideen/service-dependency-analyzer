package com.groupon.sda.config;

import com.groupon.sda.graph.ServiceGraph;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires the in-memory {@link ServiceGraph} bean. Sample retention is sized from
 * {@link HealthProperties} so the per-edge deques only keep what the {@code health}
 * window can ask for.
 *
 * <p>The {@link Clock} is injectable so tests can use a fixed or mutable clock; it
 * defaults to {@link Clock#systemUTC()}. Eviction inside {@code Edge} uses this clock
 * (not event timestamps) so stale or future-dated events can't poison the eviction logic.
 */
@Configuration
@EnableConfigurationProperties(HealthProperties.class)
public class GraphConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ServiceGraph serviceGraph(HealthProperties health, Clock clock) {
        return new ServiceGraph(
                health.maxSamplesPerEdge(),
                Duration.ofSeconds(health.windowSeconds()),
                clock
        );
    }
}
