package com.groupon.sda.restart;

import com.groupon.sda.domain.event.Event;
import com.groupon.sda.graph.algorithms.Criticality;
import com.groupon.sda.graph.algorithms.Cycles;
import com.groupon.sda.graph.algorithms.Reachability;
import com.groupon.sda.graph.algorithms.ShortestPath;
import com.groupon.sda.testsupport.FixtureLoader;
import com.groupon.sda.testsupport.TestStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restart consistency — the spec's strongest persistence requirement:
 *
 * <blockquote>
 *   "After a restart, queries must return the same answers as before the restart,
 *    given the same input stream."
 * </blockquote>
 *
 * <p>Strategy: open a {@link TestStack} on a temp SQLite file, ingest the rich
 * mixed-stream fixture, snapshot every query's result, then close and re-open a
 * <em>second</em> stack against the same file. Re-run the same queries; the
 * answers must be identical.
 *
 * <p>The rich-mixed-stream fixture is curated to exercise every persistence path:
 * service metadata + heartbeat (services rows), observations (edges + edge_samples
 * rows), removals (DELETE on edges, plus the in-memory tombstone — known not to
 * survive restart, so the test is structured to not depend on that), duplicates
 * (processed_events idempotency), and a cycle (so {@code cycles()} is non-trivial).
 */
class RestartConsistencyTest {

    private static final Instant NOW = Instant.parse("2026-05-10T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofSeconds(300);

    @Test
    void queriesReturnIdenticalAnswersAcrossRestart(@TempDir Path tmp) {
        Path dbFile = tmp.resolve("restart-test.db");
        List<Event> events = FixtureLoader.load("restart/01-rich-mixed-stream.json");

        // ---- Phase 1: ingest -------------------------------------------------------
        QuerySnapshot before;
        try (TestStack stack = TestStack.open(dbFile, FIXED_CLOCK, WINDOW)) {
            for (Event e : events) {
                stack.consumer.consume(e);
            }
            before = QuerySnapshot.capture(stack);

            // Sanity checks on the pre-restart state — these prove the fixture is
            // doing meaningful work.
            assertThat(stack.graph.nodeCount()).isGreaterThanOrEqualTo(8);
            assertThat(stack.graph.edgeCount()).isGreaterThanOrEqualTo(10);
            assertThat(before.cycles.cycles()).isNotEmpty();
            assertThat(before.criticalServices.services()).isNotEmpty();
        }

        // ---- Phase 2: re-open the same DB and verify ------------------------------
        try (TestStack stack = TestStack.open(dbFile, FIXED_CLOCK, WINDOW)) {
            QuerySnapshot after = QuerySnapshot.capture(stack);

            assertThat(after.reachableCheckout)
                    .as("reachable(checkout-api) survives restart")
                    .isEqualTo(before.reachableCheckout);
            assertThat(after.dependentsUsersDb)
                    .as("dependents(users-db) survives restart")
                    .isEqualTo(before.dependentsUsersDb);
            assertThat(after.shortestCheckoutToUsersDb)
                    .as("shortest-path(checkout-api -> users-db) survives restart")
                    .isEqualTo(before.shortestCheckoutToUsersDb);
            assertThat(after.criticalServices)
                    .as("critical_services top-5 survives restart")
                    .isEqualTo(before.criticalServices);
            assertThat(after.cycles)
                    .as("cycles() survives restart")
                    .isEqualTo(before.cycles);
            assertThat(after.healthCheckout)
                    .as("health(checkout-api) survives restart")
                    .isEqualTo(before.healthCheckout);
            assertThat(after.healthPayments)
                    .as("health(payments) survives restart")
                    .isEqualTo(before.healthPayments);
        }
    }

    /**
     * Bundle of query results we re-run before and after restart. Records use
     * value semantics so {@code .equals} compares structurally.
     */
    private record QuerySnapshot(
            Reachability.Result reachableCheckout,
            Reachability.Result dependentsUsersDb,
            ShortestPath.Result shortestCheckoutToUsersDb,
            Criticality.Result criticalServices,
            Cycles.Result cycles,
            HealthSnap healthCheckout,
            HealthSnap healthPayments
    ) {
        static QuerySnapshot capture(TestStack stack) {
            return new QuerySnapshot(
                    Reachability.reachable(stack.graph, "checkout-api"),
                    Reachability.dependents(stack.graph, "users-db"),
                    ShortestPath.shortestPath(stack.graph, "checkout-api", "users-db"),
                    Criticality.criticalServices(stack.graph, 5),
                    Cycles.cycles(stack.graph),
                    HealthSnap.from(stack, "checkout-api"),
                    HealthSnap.from(stack, "payments")
            );
        }
    }

    /** Compact health value for equality comparison. */
    private record HealthSnap(int sampleCount, double errorRate, int p95) {
        static HealthSnap from(TestStack stack, String svc) {
            var h = stack.graph.computeHealth(svc, NOW, WINDOW);
            return h == null
                    ? new HealthSnap(0, 0.0, 0)
                    : new HealthSnap(h.sampleCount(), h.errorRate(), h.p95LatencyMs());
        }
    }
}
