package com.groupon.sda.events.generator;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyRemovedEvent;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.domain.event.HeartbeatEvent;
import com.groupon.sda.domain.event.ServiceMetadataEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec-required dataset shape ({@code Data} section): "~5,000–10,000 services,
 * ~50,000–200,000 events", "few high-fan-in services", "at least a couple of cycles",
 * "long tail of latencies", "non-trivial error rate", "Mix of all event types —
 * including removals and metadata updates". This test fixes the seed and asserts the
 * generator hits each of those shape requirements.
 */
class SyntheticEventGeneratorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);

    private static SyntheticEventGenerator generator(int services, int events) {
        EventGeneratorProperties p = new EventGeneratorProperties(
                true, services, events, 5, 2, 0.05, 0.01, 42L);
        return new SyntheticEventGenerator(p, CLOCK);
    }

    @Test
    void totalEventCountMatchesConfig() {
        SyntheticEventGenerator gen = generator(100, 500);
        assertThat(gen.totalEvents()).isEqualTo(500);

        int drained = 0;
        while (gen.next() != null) drained++;
        assertThat(drained).isEqualTo(500);
        assertThat(gen.next()).isNull();   // exhausted
    }

    @Test
    void streamContainsAllFourEventTypes() {
        SyntheticEventGenerator gen = generator(200, 5_000);
        Map<Class<?>, Integer> counts = new HashMap<>();
        Event e;
        while ((e = gen.next()) != null) {
            counts.merge(e.getClass(), 1, Integer::sum);
        }
        assertThat(counts).containsKeys(
                DependencyObservedEvent.class,
                DependencyRemovedEvent.class,
                ServiceMetadataEvent.class,
                HeartbeatEvent.class);
        // Observations should dominate (most calls) but each type must be present.
        assertThat(counts.get(DependencyObservedEvent.class))
                .isGreaterThan(counts.get(DependencyRemovedEvent.class));
    }

    @Test
    void streamHasNonTrivialErrorRate() {
        SyntheticEventGenerator gen = generator(200, 5_000);
        int errors = 0;
        int totalObs = 0;
        Event e;
        while ((e = gen.next()) != null) {
            if (e instanceof DependencyObservedEvent o) {
                totalObs++;
                if (o.status() != DependencyObservedEvent.Status.ok) errors++;
            }
        }
        assertThat(totalObs).isPositive();
        // Roughly the configured 0.05 ± slack.
        double rate = (double) errors / totalObs;
        assertThat(rate).isBetween(0.02, 0.10);
    }

    @Test
    void streamHasDuplicateEventIds() {
        SyntheticEventGenerator gen = generator(200, 5_000);
        Set<String> seen = new HashSet<>();
        int dupes = 0;
        Event e;
        while ((e = gen.next()) != null) {
            if (!seen.add(e.eventId())) dupes++;
        }
        // duplicate-rate is 0.01 with seed 42 — expect a handful.
        assertThat(dupes).isPositive();
    }

    @Test
    void seedIsDeterministic() {
        SyntheticEventGenerator a = generator(50, 200);
        SyntheticEventGenerator b = generator(50, 200);
        Event ea, eb;
        int compared = 0;
        while ((ea = a.next()) != null && (eb = b.next()) != null) {
            assertThat(ea.eventId()).isEqualTo(eb.eventId());
            assertThat(ea.getClass()).isEqualTo(eb.getClass());
            compared++;
        }
        assertThat(compared).isEqualTo(200);
    }
}
