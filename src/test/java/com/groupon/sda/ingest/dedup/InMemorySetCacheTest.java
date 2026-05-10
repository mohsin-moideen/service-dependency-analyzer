package com.groupon.sda.ingest.dedup;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySetCacheTest {

    @Test
    void newCacheContainsNothing() {
        SeenEventsCache cache = new InMemorySetCache();
        assertThat(cache.contains("anything")).isFalse();
        assertThat(cache.size()).isZero();
    }

    @Test
    void addThenContains() {
        SeenEventsCache cache = new InMemorySetCache();
        cache.add("e1");
        assertThat(cache.contains("e1")).isTrue();
        assertThat(cache.contains("e2")).isFalse();
        assertThat(cache.size()).isOne();
    }

    @Test
    void addIsIdempotent() {
        SeenEventsCache cache = new InMemorySetCache();
        cache.add("e1");
        cache.add("e1");
        cache.add("e1");
        assertThat(cache.size()).isOne();
    }

    @Test
    void concurrentAddsAreSafe() throws Exception {
        SeenEventsCache cache = new InMemorySetCache();
        int threads = 8;
        int idsPerThread = 1_000;

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < idsPerThread; i++) {
                    cache.add("t" + threadId + "-i" + i);
                }
                done.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(done.get()).isEqualTo(threads);
        assertThat(cache.size()).isEqualTo(threads * idsPerThread);
    }
}
