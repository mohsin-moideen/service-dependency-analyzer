package com.groupon.sda.queue;

import com.groupon.sda.domain.event.Event;

/**
 * In-memory bounded queue for events. Must be built from primitives —
 * the spec forbids Kafka, RabbitMQ, NATS, Redis Streams, SQS, etc.
 *
 * Implementation choices to make:
 *   - Backing structure (e.g. ArrayBlockingQueue, custom ring buffer with locks/condvars)
 *   - Backpressure policy: block producers vs deliberate shed (document the choice)
 *   - Multi-producer / multi-consumer safety
 */
public interface EventQueue {

    void publish(Event event) throws InterruptedException;

    Event take() throws InterruptedException;

    int size();
}
