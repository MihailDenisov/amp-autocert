package tech.maicraft.ampautocert;

import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.exception.AcmeException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Deadline-safe polling that does not rely on acme4j 5.1's pre-fetch sleep calculation. */
final class AcmePoller {
    private static final Duration DEFAULT_DELAY = Duration.ofSeconds(3);
    private static final Duration MINIMUM_DELAY = Duration.ofMillis(100);
    private final Clock clock;
    private final Sleeper sleeper;

    AcmePoller(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    Status await(Instant deadline, Set<Status> successful, Set<Status> pending, Fetch fetch)
            throws AcmeException, IOException, InterruptedException {
        while (true) {
            Snapshot snapshot = call(deadline, clock, fetch::fetch);
            Instant now = clock.instant();
            if (!now.isBefore(deadline)) throw new IOException("ACME operation deadline expired");
            if (successful.contains(snapshot.status())) return snapshot.status();
            if (!pending.contains(snapshot.status())) throw new AcmeException("ACME resource entered a failure state");

            Duration delay = snapshot.retryAfter().map(next -> Duration.between(now, next)).orElse(DEFAULT_DELAY);
            if (delay.isNegative() || delay.isZero()) delay = MINIMUM_DELAY;
            Duration remaining = Duration.between(now, deadline);
            sleeper.sleep(delay.compareTo(remaining) < 0 ? delay : remaining);
        }
    }

    static <T> T call(Instant deadline, Clock clock, Callable<T> operation)
            throws AcmeException, IOException, InterruptedException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) throw new IOException("ACME operation deadline expired");
        FutureTask<T> task = new FutureTask<>(operation);
        Thread worker = Thread.ofVirtual().name("amp-autocert-acme-io").start(task);
        try {
            return task.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            worker.interrupt();
            throw new IOException("ACME operation deadline expired");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            worker.interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof AcmeException acme) throw acme;
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("ACME operation failed");
        }
    }

    @FunctionalInterface interface Fetch {
        Snapshot fetch() throws AcmeException, IOException;
    }

    record Snapshot(Status status, Optional<Instant> retryAfter) {
        Snapshot {
            if (status == null || retryAfter == null) throw new IllegalArgumentException("Invalid ACME poll result");
        }
    }
}
