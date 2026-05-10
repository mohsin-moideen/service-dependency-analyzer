package com.groupon.sda.ingest.consumer;

import com.groupon.sda.config.IngestProperties;
import com.groupon.sda.queue.PartitionedEventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns and manages the consumer thread fleet. {@link SmartLifecycle} so Spring orders
 * startup and shutdown around it; {@code phase = 500} means consumers start <i>after</i>
 * the WAL-pragma and graph-restore listeners (which fire on {@code ContextRefreshedEvent},
 * before any {@code SmartLifecycle.start()}) and <i>before</i> the producers
 * ({@code phase = 1000}). On shutdown, lifecycle phases run in reverse, so producers
 * stop first (closing the queue), then this manager waits for consumers to drain.
 *
 * <p>Threads are virtual ({@link Thread#ofVirtual}) — cheap to spawn, blocking on the
 * SQLite write lock or the queue lock doesn't pin a platform thread.
 */
@Component
public class ConsumerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConsumerManager.class);
    private static final int PHASE = 500;
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(30);

    private final PartitionedEventQueue queues;
    private final EventConsumer consumer;
    private final IngestProperties props;

    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = false;

    public ConsumerManager(PartitionedEventQueue queues, EventConsumer consumer, IngestProperties props) {
        this.queues = queues;
        this.consumer = consumer;
        this.props = props;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        int n = queues.partitionCount();
        log.info("starting {} consumer thread(s), one per partition", n);
        for (int i = 0; i < n; i++) {
            String name = "sda-consumer-" + i;
            Runnable r = new EventConsumerRunnable(name, queues.partition(i), consumer);
            Thread t = Thread.ofVirtual().name(name).start(r);
            threads.add(t);
        }
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        log.info("stopping consumer fleet (waiting for queue drain)");
        // Producers (higher phase) have already stopped and closed the queue, so
        // queue.take() inside each consumer will return null once drained.
        for (Thread t : threads) {
            try {
                if (!t.join(JOIN_TIMEOUT)) {
                    log.warn("consumer {} did not exit within {}; interrupting",
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
        running = false;
        log.info("consumer fleet stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
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
