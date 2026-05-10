package com.groupon.sda.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test-only Clock that lets the test advance time deliberately. {@link Clock#fixed} is
 * fine when time doesn't change during the test; this is needed when it does.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advanceBy(Duration d) {
        this.instant = this.instant.plus(d);
    }
}
