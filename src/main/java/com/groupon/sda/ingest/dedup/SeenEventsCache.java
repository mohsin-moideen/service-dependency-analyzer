package com.groupon.sda.ingest.dedup;

/**
 * In-memory pre-check for event-id deduplication. Consulted by consumers before they
 * attempt the durable {@code processed_events} INSERT.
 *
 * <p><b>Contract:</b> {@link #contains(String)} may return {@code true} for an event
 * that was never added (false positive — allowed). It must never return {@code false}
 * for an event that was successfully added (false negative — forbidden).
 *
 * <p>The exact-set default ({@link InMemorySetCache}) returns no false positives. The
 * production swap point ({@code BloomFilterCache}) trades a tunable FPR for
 * dramatically smaller memory at scale; on a probabilistic "maybe seen" the consumer
 * resolves the ambiguity by checking the durable {@code processed_events} table.
 *
 * <p><b>Not the source of truth.</b> The cache is empty after a restart and may be
 * lossy under heavy churn. Restart-consistent dedup is provided by the
 * {@code processed_events} primary-key constraint.
 */
public interface SeenEventsCache {

    /**
     * Atomically claim processing rights for {@code eventId}.
     *
     * <p><b>Exact implementations</b> ({@link InMemorySetCache}): returns {@code true}
     * iff this is the first time the id has been seen since JVM start. At most one
     * caller wins per id.
     *
     * <p><b>Probabilistic implementations</b> (future {@code BloomFilterCache}): the
     * boolean may be a false-negative-on-claim (i.e., a real new event reports
     * {@code false}) due to a hash collision. Callers that lose the claim must
     * confirm against the durable {@code processed_events} store before deciding
     * the event is a duplicate.
     *
     * @return {@code true} if the caller now owns processing for this id.
     */
    boolean tryClaim(String eventId);

    /**
     * @return {@code true} if {@code eventId} was probably added before
     *         (definitely added, in exact implementations).
     */
    boolean contains(String eventId);

    /** Record that {@code eventId} has been processed. Idempotent. */
    void add(String eventId);

    /** Number of distinct event ids currently tracked, where defined. */
    int size();
}
