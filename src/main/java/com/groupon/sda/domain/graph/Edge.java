package com.groupon.sda.domain.graph;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

/**
 * A directed edge {@code source → target} with rolling latency stats and a bounded window
 * of recent samples for the {@code health()} query.
 *
 * <p><b>Concurrency contract:</b> this class is <i>not</i> internally synchronized. It is
 * always touched while the owning {@code ServiceGraph}'s read or write lock is held.
 * <ul>
 *   <li><b>Write lock required:</b> {@link #recordObservation}, {@link #restoreSample},
 *       {@link #restoreRollingStats} — all mutate the sample deque and/or rolling stat
 *       fields.</li>
 *   <li><b>Read lock sufficient:</b> {@link #rollingAvgLatencyMs}, {@link #sampleCount},
 *       {@link #recentSamplesView}, {@link #computeHealth} — all read-only.</li>
 * </ul>
 *
 * <p>The recent-samples deque is bounded in two dimensions:
 * <ul>
 *   <li><b>Size cap</b> ({@code maxSamples}) — protects against runaway memory on a hot edge.</li>
 *   <li><b>Age cap</b> ({@code ageCap}) — anything older than {@code clock.instant() - ageCap}
 *       is dropped. Eviction runs <b>only on the writer path</b> (every {@link #recordObservation}).
 *       Read-side queries filter stragglers in-place by timestamp instead of evicting.</li>
 * </ul>
 *
 * <p><b>Eviction reference is wall clock, not event ts.</b> The injected {@link Clock}
 * is the source of truth for "now." Stale or future-dated event timestamps cannot poison
 * eviction (a stale event with {@code ts = now - 1h} no longer computes a cutoff far in
 * the past; a future-dated event no longer aggressively trims valid samples).
 *
 * <p><b>Eviction does a full scan, not a front-truncate.</b> Out-of-order inserts can put
 * older samples behind newer ones in the deque; a peek-and-bail loop would miss them.
 */
public final class Edge {

    private final String source;
    private final String target;
    private final int maxSamples;
    private final Duration ageCap;
    private final Clock clock;

    private double rollingAvgLatencyMs;
    private long sampleCount;

    /**
     * The event-timestamp of the most recent observation that has been applied. Used by
     * {@code ServiceGraph} for last-write-wins ordering checks. Distinct from the wall
     * clock used for sample-deque eviction. {@code null} until the first observation.
     */
    private Instant lastObservedTs;

    private final Deque<Sample> recentSamples = new ArrayDeque<>();

    public Edge(String source, String target, int maxSamples, Duration ageCap, Clock clock) {
        this.source = source;
        this.target = target;
        this.maxSamples = maxSamples;
        this.ageCap = ageCap;
        this.clock = clock;
    }

    public String source() {
        return source;
    }

    public String target() {
        return target;
    }

    public double rollingAvgLatencyMs() {
        return rollingAvgLatencyMs;
    }

    public long sampleCount() {
        return sampleCount;
    }

    public Instant lastObservedTs() {
        return lastObservedTs;
    }

    /** Read-only iterable over the in-memory sample buffer. Must be consumed under the read lock. */
    public Iterable<Sample> recentSamplesView() {
        return Collections.unmodifiableCollection(recentSamples);
    }

    /**
     * Apply a new observation. Updates rolling average + lifetime sample count and
     * appends to the recent-samples deque, then evicts by both size and age caps.
     */
    public void recordObservation(Instant ts, int latencyMs, Status status) {
        // Incremental running average: avg_n = avg_{n-1} + (x_n - avg_{n-1}) / n
        sampleCount++;
        rollingAvgLatencyMs += (latencyMs - rollingAvgLatencyMs) / sampleCount;

        if (lastObservedTs == null || ts.isAfter(lastObservedTs)) {
            lastObservedTs = ts;
        }

        recentSamples.addLast(new Sample(ts, latencyMs, status));
        while (recentSamples.size() > maxSamples) {
            recentSamples.removeFirst();
        }
        evictByAge();
    }

    /**
     * Used by the persistence layer to rehydrate the deque on boot. Bypasses rolling-stat
     * updates because those are restored separately from the {@code edges} row.
     */
    public void restoreSample(Sample s) {
        recentSamples.addLast(s);
        while (recentSamples.size() > maxSamples) {
            recentSamples.removeFirst();
        }
    }

    /** Used by the persistence layer to restore rolling stats on boot. */
    public void restoreRollingStats(double avgLatencyMs, long sampleCount, Instant lastObservedTs) {
        this.rollingAvgLatencyMs = avgLatencyMs;
        this.sampleCount = sampleCount;
        this.lastObservedTs = lastObservedTs;
    }

    /**
     * Compute error rate and p95 latency over samples within {@code window} ending at
     * {@code now}. Returns {@code null} if there are no samples in the window.
     *
     * <p><b>Read-only.</b> Filters stragglers in-place rather than evicting, so it's safe
     * under the read lock with multiple concurrent readers. Eviction is the writer's job
     * (see {@link #recordObservation}).
     */
    public HealthSnapshot computeHealth(Instant now, Duration window) {
        if (recentSamples.isEmpty()) {
            return null;
        }
        Instant cutoff = now.minus(window);
        int total = 0;
        int errors = 0;
        int[] latencies = new int[recentSamples.size()];
        for (Sample s : recentSamples) {
            if (s.ts().isBefore(cutoff)) continue;
            latencies[total] = s.latencyMs();
            if (s.status() != Status.ok) errors++;
            total++;
        }
        if (total == 0) return null;

        int[] window95 = new int[total];
        System.arraycopy(latencies, 0, window95, 0, total);
        java.util.Arrays.sort(window95);
        int p95Index = Math.min(total - 1, (int) Math.ceil(0.95 * total) - 1);
        if (p95Index < 0) p95Index = 0;
        int p95 = window95[p95Index];

        double errorRate = (double) errors / total;
        return new HealthSnapshot(total, errorRate, p95);
    }

    /**
     * Full-scan eviction: pops every element of the deque exactly once and re-pushes those
     * still in window. Necessary because out-of-order inserts can leave older samples
     * behind newer ones — a peek-and-bail loop would skip them.
     *
     * <p>O(N) where N is bounded by {@code maxSamples}. Runs only on the writer path.
     */
    private void evictByAge() {
        if (recentSamples.isEmpty()) return;
        Instant cutoff = clock.instant().minus(ageCap);
        int initialSize = recentSamples.size();
        for (int i = 0; i < initialSize; i++) {
            Sample s = recentSamples.removeFirst();
            if (!s.ts().isBefore(cutoff)) {
                recentSamples.addLast(s);
            }
        }
    }

    /** Result of {@link #computeHealth}. */
    public record HealthSnapshot(int sampleCount, double errorRate, int p95LatencyMs) {
    }
}
