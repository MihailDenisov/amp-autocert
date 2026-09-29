package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryQueueTest {
    @Test void executingRetryCanScheduleItsSuccessor() throws Exception {
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            RetryQueue queue = new RetryQueue(executor);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Runnable second = () -> { calls.incrementAndGet(); completed.countDown(); };
            Runnable first = () -> {
                calls.incrementAndGet();
                assertTrue(queue.schedule(Instant.now().plusMillis(2), Duration.ofMillis(2), second));
            };

            assertTrue(queue.schedule(Instant.now().plusMillis(2), Duration.ofMillis(2), first));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertFalse(queue.pending());
        }
    }

    @Test void keepsEarlierPendingRetryAndCancellationInvalidatesCallback() throws Exception {
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            RetryQueue queue = new RetryQueue(executor);
            AtomicInteger calls = new AtomicInteger();
            Instant early = Instant.now().plusSeconds(1);
            assertTrue(queue.schedule(early, Duration.ofSeconds(1), calls::incrementAndGet));
            assertFalse(queue.schedule(early.plusSeconds(1), Duration.ofSeconds(2), calls::incrementAndGet));
            queue.cancel();
            Thread.sleep(20);
            assertEquals(0, calls.get());
        }
    }
}
