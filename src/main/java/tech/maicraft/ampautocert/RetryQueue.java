package tech.maicraft.ampautocert;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns at most one delayed retry and releases its slot before the retry callback runs. */
final class RetryQueue {
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private Instant target;
    private long generation;

    RetryQueue(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    synchronized boolean schedule(Instant requestedTarget, Duration delay, Runnable action)
            throws RejectedExecutionException {
        if (future != null && !future.isDone() && target != null && !requestedTarget.isBefore(target)) return false;
        if (future != null) future.cancel(false);
        long ticket = ++generation;
        target = requestedTarget;
        future = executor.schedule(() -> run(ticket, action), Math.max(1L, delay.toMillis()), TimeUnit.MILLISECONDS);
        return true;
    }

    private void run(long ticket, Runnable action) {
        synchronized (this) {
            if (ticket != generation) return;
            future = null;
            target = null;
        }
        action.run();
    }

    synchronized boolean pending() {
        return future != null && !future.isDone();
    }

    synchronized void cancel() {
        generation++;
        if (future != null) future.cancel(false);
        future = null;
        target = null;
    }
}
