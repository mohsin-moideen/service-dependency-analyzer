package com.groupon.sda.ingest.producer;

import com.groupon.sda.config.IngestProperties;
import com.groupon.sda.events.generator.EventGenerator;
import com.groupon.sda.queue.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spawns and manages the producer thread fleet. Phase 1000 — starts <i>after</i>
 * consumers (phase 500) and stops <i>before</i> them.
 *
 * <p>When all producers exit naturally (the synthetic generator is finite), the manager
 * detects this in a watchdog thread and calls {@link EventQueue#close()} so consumers
 * can drain and exit. On SIGTERM, {@link #stop()} flips the running flag, joins
 * producers, then closes the queue if not already closed.
 *
 * <p>If no {@link EventGenerator} bean is wired (e.g. {@code sda.events.generator.enabled=false}),
 * this manager spawns no threads — the system runs as a query-only / HTTP-ingest-only
 * instance.
 */
@Component
public class ProducerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProducerManager.class);
    private static final int PHASE = 1000;
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(30);

    private final EventQueue queue;
    private final IngestProperties props;
    private final EventGenerator generator;   // may be null

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();
    private Thread watchdog;

    @Autowired
    public ProducerManager(EventQueue queue,
                           IngestProperties props,
                           @Nullable EventGenerator generator) {
        this.queue = queue;
        this.props = props;
        this.generator = generator;
    }

    @Override
    public synchronized void start() {
        if (running.get()) return;
        if (generator == null) {
            log.info("no EventGenerator bean configured; producer manager idle");
            running.set(true);
            return;
        }
        int n = props.producerCount();
        log.info("starting {} producer thread(s) for {} pre-generated events",
                n, generator.totalEvents());
        running.set(true);
        for (int i = 0; i < n; i++) {
            String name = "sda-producer-" + i;
            Runnable r = new EventProducerRunnable(name, generator, queue, props, running);
            Thread t = Thread.ofVirtual().name(name).start(r);
            threads.add(t);
        }
        // Watchdog: when every producer thread exits (generator exhausted), close
        // the queue so consumers see drained state and shut down cleanly.
        watchdog = Thread.ofVirtual().name("sda-producer-watchdog").start(() -> {
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.info("all producers complete; closing queue so consumers can drain");
            queue.close();
        });
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        log.info("stopping producer fleet");

        // Close the queue FIRST. A producer blocked in put()/offer() will wake up
        // with QueueClosedException (caught in the runnable) and exit. Without this,
        // join() below would hang waiting for a parked producer to come back.
        // Side effect: consumers (lower phase, still running) will start seeing a
        // drain signal — that's fine, they exit cleanly when the buffer empties.
        if (!queue.isClosed()) {
            queue.close();
        }

        for (Thread t : threads) {
            try {
                if (!t.join(JOIN_TIMEOUT)) {
                    log.warn("producer {} did not exit within {}; interrupting",
                            t.getName(), JOIN_TIMEOUT);
                    t.interrupt();
                    t.join(Duration.ofSeconds(5));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
        if (watchdog != null) {
            try {
                watchdog.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            watchdog = null;
        }
        log.info("producer fleet stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
