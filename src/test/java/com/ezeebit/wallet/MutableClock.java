package com.ezeebit.wallet;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move forward, e.g. to expire a quote or pass a retry backoff. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(currentMicros());

    private static Instant currentMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void reset() {
        now.set(currentMicros());
    }

    public void advance(Duration duration) {
        now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
