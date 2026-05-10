package com.groupon.sda.queue;

/**
 * Thrown by {@link EventQueue#put} and {@link EventQueue#offer} when the queue has
 * already been {@link EventQueue#close() closed}. Producers should treat this as a signal
 * to stop their loop — the system is shutting down.
 */
public class QueueClosedException extends RuntimeException {

    public QueueClosedException() {
        super("event queue is closed");
    }

    public QueueClosedException(String message) {
        super(message);
    }
}
