package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.Status;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class AcmePollerTest {
    @Test void clampsPastRetryAfterToMinimumDelay() throws Exception {
        MutableClock clock = new MutableClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock);
        AcmePoller poller = new AcmePoller(clock, sleeper);
        Queue<AcmePoller.Snapshot> results = new ArrayDeque<>(List.of(
                new AcmePoller.Snapshot(Status.PENDING, Optional.of(clock.instant().minusSeconds(5))),
                new AcmePoller.Snapshot(Status.VALID, Optional.empty())));

        assertEquals(Status.VALID, poller.await(clock.instant().plusSeconds(10), Set.of(Status.VALID),
                Set.of(Status.PENDING), results::remove));
        assertEquals(List.of(Duration.ofMillis(100)), sleeper.delays);
    }

    @Test void honorsFutureRetryAfter() throws Exception {
        MutableClock clock = new MutableClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock);
        AcmePoller poller = new AcmePoller(clock, sleeper);
        Queue<AcmePoller.Snapshot> results = new ArrayDeque<>(List.of(
                new AcmePoller.Snapshot(Status.PROCESSING, Optional.of(clock.instant().plusSeconds(5))),
                new AcmePoller.Snapshot(Status.READY, Optional.empty())));

        assertEquals(Status.READY, poller.await(clock.instant().plusSeconds(10), Set.of(Status.READY),
                Set.of(Status.PROCESSING), results::remove));
        assertEquals(List.of(Duration.ofSeconds(5)), sleeper.delays);
    }

    @Test void rejectsSuccessfulFetchThatCompletesAtDeadline() {
        MutableClock clock = new MutableClock();
        AcmePoller poller = new AcmePoller(clock, duration -> clock.advance(duration));
        Instant deadline = clock.instant().plusSeconds(2);

        assertThrows(IOException.class, () -> poller.await(deadline, Set.of(Status.VALID), Set.of(Status.PENDING), () -> {
            clock.advance(Duration.ofSeconds(2));
            return new AcmePoller.Snapshot(Status.VALID, Optional.empty());
        }));
    }

    @Test void stalledOperationIsCancelledAtWallClockDeadline() {
        Clock clock = Clock.systemUTC();
        CountDownLatch never = new CountDownLatch(1);
        long started = System.nanoTime();

        assertThrows(IOException.class, () -> AcmePoller.call(clock.instant().plusMillis(200), clock, () -> {
            never.await();
            return "late";
        }));

        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
    }

    private static final class AdvancingSleeper implements Sleeper {
        final MutableClock clock;
        final java.util.ArrayList<Duration> delays = new java.util.ArrayList<>();
        AdvancingSleeper(MutableClock clock) { this.clock = clock; }
        @Override public void sleep(Duration duration) { delays.add(duration); clock.advance(duration); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
