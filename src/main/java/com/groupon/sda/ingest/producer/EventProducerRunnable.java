package com.groupon.sda.ingest.producer;

import com.groupon.sda.config.IngestProperties;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.events.generator.EventGenerator;
import com.groupon.sda.queue.PartitionedEventQueue;
import com.groupon.sda.queue.QueueClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One producer thread's loop: pull from the {@link EventGenerator}, publish onto the
 * {@link PartitionedEventQueue} (which routes to the right partition by the event's
 * key), exit when the generator returns {@code null} or the manager signals stop.
 *
 * <p>Backpressure: when {@code sda.ingest.put-timeout-ms = 0} (default), uses the
 * blocking {@link PartitionedEventQueue#publish} so producers self-throttle to
 * consumer speed. When set, uses the timed {@link PartitionedEventQueue#tryPublish}
 * and counts shed events.
 */
public class EventProducerRunnable implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(EventProducerRunnable.class);

    private final String name;
    private final EventGenerator generator;
    private final PartitionedEventQueue queues;
    private final IngestProperties props;
    private final AtomicBoolean running;

    public EventProducerRunnable(String name,
                                 EventGenerator generator,
                                 PartitionedEventQueue queues,
                                 IngestProperties props,
                                 AtomicBoolean running) {
        this.name = name;
        this.generator = generator;
        this.queues = queues;
        this.props = props;
        this.running = running;
    }

    @Override
    public void run() {
        log.info("producer {} starting", name);
        long published = 0;
        long dropped = 0;
        try {
            while (running.get()) {
                Event e = generator.next();
                if (e == null) break;   // generator exhausted

                if (props.shedOnTimeout()) {
                    boolean accepted = queues.tryPublish(e, props.putTimeoutMs(), TimeUnit.MILLISECONDS);
                    if (accepted) {
                        published++;
                    } else {
                        dropped++;
                        log.warn("producer {} shed event {} after {}ms (partition full)",
                                name, e.eventId(), props.putTimeoutMs());
                    }
                } else {
                    queues.publish(e);
                    published++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("producer {} interrupted", name);
        } catch (QueueClosedException e) {
            log.info("producer {} saw queue closed; exiting", name);
        }
        log.info("producer {} exiting after publishing {} (dropped {})", name, published, dropped);
    }
}
