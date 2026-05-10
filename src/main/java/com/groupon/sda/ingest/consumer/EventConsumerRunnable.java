package com.groupon.sda.ingest.consumer;

import com.groupon.sda.domain.event.Event;
import com.groupon.sda.queue.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consumer thread's loop: take from the queue, hand to {@link EventConsumer},
 * exit when the queue returns {@code null} (closed and drained).
 *
 * <p>Errors during apply are logged and the loop continues — a single bad event
 * shouldn't take down the consumer fleet. Idempotency means a retry-on-restart of a
 * failed event is safe.
 */
public class EventConsumerRunnable implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerRunnable.class);

    private final String name;
    private final EventQueue queue;
    private final EventConsumer consumer;

    public EventConsumerRunnable(String name, EventQueue queue, EventConsumer consumer) {
        this.name = name;
        this.queue = queue;
        this.consumer = consumer;
    }

    @Override
    public void run() {
        log.info("consumer {} starting", name);
        try {
            while (true) {
                Event event = queue.take();
                if (event == null) {
                    // Queue closed and drained.
                    break;
                }
                try {
                    consumer.consume(event);
                } catch (Throwable t) {
                    log.error("consumer {} failed to apply event {}; continuing",
                            name, event.eventId(), t);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("consumer {} interrupted; exiting", name);
        }
        log.info("consumer {} exiting", name);
    }
}
