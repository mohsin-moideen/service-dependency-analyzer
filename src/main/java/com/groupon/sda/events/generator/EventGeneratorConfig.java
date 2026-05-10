package com.groupon.sda.events.generator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the {@link SyntheticEventGenerator} when {@code sda.events.generator.enabled=true}.
 * When disabled, no {@link EventGenerator} bean is created and the producer manager runs
 * idle — the system serves queries against whatever's already in SQLite plus anything
 * that arrives via the HTTP ingest endpoint.
 */
@Configuration
@EnableConfigurationProperties(EventGeneratorProperties.class)
public class EventGeneratorConfig {

    @Bean
    @ConditionalOnProperty(prefix = "sda.events.generator", name = "enabled",
            havingValue = "true", matchIfMissing = false)
    public EventGenerator eventGenerator(EventGeneratorProperties props, Clock clock) {
        return new SyntheticEventGenerator(props, clock);
    }
}
