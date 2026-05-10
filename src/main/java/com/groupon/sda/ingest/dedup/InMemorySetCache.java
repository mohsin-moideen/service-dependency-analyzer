package com.groupon.sda.ingest.dedup;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact, unbounded in-memory implementation of {@link SeenEventsCache} backed by a
 * {@link ConcurrentHashMap#newKeySet() ConcurrentHashMap.newKeySet()}.
 *
 * <p>Default implementation for the take-home. At ~200k events this fits comfortably in
 * heap. At true production scale the unbounded growth is the failure mode this class
 * deliberately doesn't try to solve — see {@code BloomFilterCache} (rolling, TTL'd) for
 * the production swap.
 *
 * <p>Concurrency: {@code ConcurrentHashMap.newKeySet()} provides lock-free reads and
 * fine-grained writes; {@link #add} is atomic.
 */
public class InMemorySetCache implements SeenEventsCache {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean contains(String eventId) {
        return seen.contains(eventId);
    }

    @Override
    public void add(String eventId) {
        seen.add(eventId);
    }

    @Override
    public int size() {
        return seen.size();
    }
}
