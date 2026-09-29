package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CertificateActivationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final String OLD_FINGERPRINT = "11".repeat(32);

    @Test void commitsOnlyAfterHostReportsExactNewFingerprint() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<AutoModpackHostControl.HostSnapshot> state = new AtomicReference<>(running(OLD_FINGERPRINT));
        AtomicInteger restarts = new AtomicInteger();
        AutoModpackHostControl host = host(allowed -> {
            restarts.incrementAndGet();
            state.set(running(fixture.fingerprint));
            return CompletableFuture.completedFuture(1);
        }, state::get);
        FakeDeployment deployment = new FakeDeployment();

        var result = service(deployment, host, new MutableClock(), duration -> { }, () -> false)
                .deployAndActivate(fixture.material);

        assertFalse(result.cleanupPending());
        assertTrue(deployment.pending.committed);
        assertFalse(deployment.pending.rolledBack);
        assertEquals(1, restarts.get());
    }

    @Test void restartReconcilesFingerprintAfterCrashRecovery() throws Exception {
        String restoredFingerprint = "22".repeat(32);
        AtomicReference<AutoModpackHostControl.HostSnapshot> state =
                new AtomicReference<>(running(OLD_FINGERPRINT));
        AtomicInteger restarts = new AtomicInteger();
        AutoModpackHostControl host = host(allowed -> {
            restarts.incrementAndGet();
            state.set(running(restoredFingerprint));
            return CompletableFuture.completedFuture(1);
        }, state::get);

        service(new FakeDeployment(), host, new MutableClock(), duration -> { }, () -> false)
                .reconcileRecoveredHost(Optional.of(restoredFingerprint), OLD_FINGERPRINT);

        assertEquals(1, restarts.get());
        assertEquals(restoredFingerprint, state.get().certificateFingerprint());
    }

    @Test void crashRecoveryStartsStoppedHostWithRestoredFingerprint() throws Exception {
        String restoredFingerprint = "33".repeat(32);
        AtomicReference<AutoModpackHostControl.HostSnapshot> state = new AtomicReference<>(
                new AutoModpackHostControl.HostSnapshot(true, false, null, "stopped"));
        AutoModpackHostControl host = host(allowed -> {
            state.set(running(restoredFingerprint));
            return CompletableFuture.completedFuture(1);
        }, state::get);

        service(new FakeDeployment(), host, new MutableClock(), duration -> { }, () -> false)
                .reconcileRecoveredHost(Optional.of(restoredFingerprint), null);

        assertTrue(state.get().running());
        assertEquals(restoredFingerprint, state.get().certificateFingerprint());
    }

    @Test void restartCommandInvocationRunsOnDesignatedServerExecutor() throws Exception {
        AtomicReference<Thread> serverThread = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-minecraft-server");
            serverThread.set(thread);
            return thread;
        });
        AtomicReference<Thread> invokedOn = new AtomicReference<>();
        try {
            var dispatcher = AutoModpackHostControl.serverThreadDispatcher(executor::execute,
                    () -> Thread.currentThread() == serverThread.get(), () -> {
                        invokedOn.set(Thread.currentThread());
                        return 1;
                    });

            assertEquals(1, dispatcher.restart(() -> true).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertSame(serverThread.get(), invokedOn.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledQueuedRestartNeverInvokesCommand() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        var dispatcher = AutoModpackHostControl.serverThreadDispatcher(queued::set,
                () -> true, invocations::incrementAndGet);

        var future = dispatcher.restart(() -> true).toCompletableFuture();
        assertTrue(future.cancel(false));
        queued.get().run();

        assertEquals(0, invocations.get());
        assertTrue(future.isCancelled());
    }

    @Test void rollbackWaitsForDelayedAutoModpackTransitionToLeaveItsMonitor() throws Exception {
        Fixture fixture = fixture();
        MutableClock clock = new MutableClock();
        ReentrantLock transition = new ReentrantLock();
        CountDownLatch transitionEntered = new CountDownLatch(1);
        CountDownLatch releaseTransition = new CountDownLatch(1);
        CountDownLatch rollbackLockAttempted = new CountDownLatch(1);
        AtomicBoolean transitionActive = new AtomicBoolean();
        AtomicReference<AutoModpackHostControl.HostSnapshot> state = new AtomicReference<>(
                new AutoModpackHostControl.HostSnapshot(true, false, null, "stopped"));
        AutoModpackHostControl.RestartDispatcher dispatcher = allowed -> {
            Thread.ofPlatform().name("delayed-automodpack-restart").start(() -> {
                transition.lock();
                try {
                    transitionActive.set(true);
                    transitionEntered.countDown();
                    try {
                        releaseTransition.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    state.set(running(fixture.fingerprint));
                    transitionActive.set(false);
                } finally {
                    transition.unlock();
                }
            });
            return CompletableFuture.completedFuture(1);
        };
        AutoModpackHostControl host = new AutoModpackHostControl(dispatcher, state::get, operation -> {
            rollbackLockAttempted.countDown();
            transition.lock();
            try { operation.run(); } finally { transition.unlock(); }
        }, () -> false);
        FakeDeployment deployment = new FakeDeployment();
        deployment.pending.rollbackCheck = () -> assertFalse(transitionActive.get());
        var activationExecutor = Executors.newSingleThreadExecutor();
        try {
            var result = activationExecutor.submit(() -> service(deployment, host, clock, duration -> {
                assertTrue(transitionEntered.await(3, TimeUnit.SECONDS));
                clock.advance(duration);
            }, () -> false).deployAndActivate(fixture.material));

            assertTrue(transitionEntered.await(3, TimeUnit.SECONDS));
            assertTrue(rollbackLockAttempted.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "rollback must wait while AutoModpack start/stop owns its monitor");
            releaseTransition.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(deployment.pending.rolledBack);
        } finally {
            releaseTransition.countDown();
            activationExecutor.shutdownNow();
            assertTrue(activationExecutor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void readinessTimeoutRollsBackThenRestartsAndVerifiesOldHost() throws Exception {
        Fixture fixture = fixture();
        MutableClock clock = new MutableClock();
        AtomicInteger restarts = new AtomicInteger();
        AutoModpackHostControl host = host(allowed -> {
            restarts.incrementAndGet();
            return CompletableFuture.completedFuture(1);
        }, () -> running(OLD_FINGERPRINT));
        FakeDeployment deployment = new FakeDeployment();

        IOException failure = assertThrows(IOException.class, () -> service(deployment, host, clock,
                clock::advance, () -> false).deployAndActivate(fixture.material));

        assertTrue(failure.getMessage().contains("expected certificate"));
        assertTrue(deployment.pending.rolledBack);
        assertEquals(2, restarts.get());
    }

    @Test void rejectedRestartRollsBackAndReconcilesPreviouslyRunningHost() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger restarts = new AtomicInteger();
        AutoModpackHostControl host = host(allowed -> {
            restarts.incrementAndGet();
            return CompletableFuture.completedFuture(0);
        }, () -> running(OLD_FINGERPRINT));
        FakeDeployment deployment = new FakeDeployment();

        IOException failure = assertThrows(IOException.class, () -> service(deployment, host,
                new MutableClock(), duration -> { }, () -> false).deployAndActivate(fixture.material));

        assertTrue(failure.getMessage().contains("rejected"));
        assertTrue(deployment.pending.rolledBack);
        assertEquals(2, restarts.get());
    }

    @Test void absentAutoModpackFailsBeforeFilesystemMutation() throws Exception {
        Fixture fixture = fixture();
        FakeDeployment deployment = new FakeDeployment();
        AutoModpackHostControl host = host(allowed -> CompletableFuture.completedFuture(1),
                AutoModpackHostControl.HostSnapshot::absent);

        IOException failure = assertThrows(IOException.class, () -> service(deployment, host,
                new MutableClock(), duration -> { }, () -> false).deployAndActivate(fixture.material));

        assertTrue(failure.getMessage().contains("absent"));
        assertEquals(0, deployment.prepares);
    }

    @Test void shutdownDuringReadinessRollsBackWithoutSchedulingAnotherRestart() throws Exception {
        Fixture fixture = fixture();
        MutableClock clock = new MutableClock();
        AtomicBoolean stopping = new AtomicBoolean();
        AtomicInteger restarts = new AtomicInteger();
        AutoModpackHostControl host = host(allowed -> {
            restarts.incrementAndGet();
            return CompletableFuture.completedFuture(1);
        }, () -> running(OLD_FINGERPRINT));
        FakeDeployment deployment = new FakeDeployment();

        assertThrows(InterruptedException.class, () -> service(deployment, host, clock, duration -> {
            clock.advance(duration);
            stopping.set(true);
        }, stopping::get).deployAndActivate(fixture.material));

        assertTrue(deployment.pending.rolledBack);
        assertEquals(1, restarts.get());
    }

    @Test void nonShutdownInterruptionReconcilesOldHostAndRestoresInterruptFlag() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger restarts = new AtomicInteger();
        AutoModpackHostControl host = host(allowed -> {
            restarts.incrementAndGet();
            return CompletableFuture.completedFuture(1);
        }, () -> running(OLD_FINGERPRINT));
        FakeDeployment deployment = new FakeDeployment();

        try {
            assertThrows(InterruptedException.class, () -> service(deployment, host,
                    new MutableClock(), duration -> { throw new InterruptedException("injected"); }, () -> false)
                    .deployAndActivate(fixture.material));

            assertTrue(deployment.pending.rolledBack);
            assertEquals(2, restarts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void durableCommitCleanupFailureDoesNotRollBackActiveCertificate() throws Exception {
        Fixture fixture = fixture();
        AutoModpackHostControl host = host(allowed -> CompletableFuture.completedFuture(1),
                () -> running(fixture.fingerprint));
        FakeDeployment deployment = new FakeDeployment();
        deployment.pending.failAfterDurableCommit = true;

        var result = service(deployment, host, new MutableClock(), duration -> { }, () -> false)
                .deployAndActivate(fixture.material);

        assertTrue(result.cleanupPending());
        assertTrue(deployment.pending.committed);
        assertFalse(deployment.pending.rolledBack);
    }

    @Test void refusesToBlockServerThreadBeforeFilesystemMutation() throws Exception {
        Fixture fixture = fixture();
        FakeDeployment deployment = new FakeDeployment();
        AutoModpackHostControl host = new AutoModpackHostControl(
                allowed -> CompletableFuture.completedFuture(1), () -> running(OLD_FINGERPRINT), () -> true);

        IOException failure = assertThrows(IOException.class, () -> service(deployment, host,
                new MutableClock(), duration -> { }, () -> false).deployAndActivate(fixture.material));

        assertTrue(failure.getMessage().contains("server thread"));
        assertEquals(0, deployment.prepares);
    }

    @Test void runningExternalTlsModeIsRejectedBeforeDeployment() throws Exception {
        Fixture fixture = fixture();
        FakeDeployment deployment = new FakeDeployment();
        AutoModpackHostControl host = host(allowed -> CompletableFuture.completedFuture(1),
                () -> new AutoModpackHostControl.HostSnapshot(true, true, null, "running"));

        IOException failure = assertThrows(IOException.class, () -> service(deployment, host,
                new MutableClock(), duration -> { }, () -> false).deployAndActivate(fixture.material));

        assertTrue(failure.getMessage().contains("internal TLS"));
        assertEquals(0, deployment.prepares);
    }

    private static CertificateActivationService service(FakeDeployment deployment,
                                                        AutoModpackHostControl host,
                                                        MutableClock clock, Sleeper sleeper,
                                                        java.util.function.BooleanSupplier stopping) {
        return new CertificateActivationService(deployment, host, clock, sleeper,
                Duration.ofSeconds(3), Duration.ofSeconds(1), stopping);
    }

    private static AutoModpackHostControl host(AutoModpackHostControl.RestartDispatcher dispatcher,
                                               AutoModpackHostControl.HostProbe probe) {
        return new AutoModpackHostControl(dispatcher, probe);
    }

    private static AutoModpackHostControl.HostSnapshot running(String fingerprint) {
        return new AutoModpackHostControl.HostSnapshot(true, true, fingerprint, "running");
    }

    private static Fixture fixture() throws Exception {
        var fixture = IssuedCertificateValidatorTest.Fixture.valid(NOW,
                List.of("maicraft.tech", "*.maicraft.tech"));
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(fixture.leafCertificate.getEncoded()));
        return new Fixture(fixture.material(AcmeEnvironment.PRODUCTION), fingerprint);
    }

    private record Fixture(AcmeIssuanceService.IssuedMaterial material, String fingerprint) { }

    private static final class FakeDeployment implements CertificateActivationService.Deployment {
        private final FakePending pending = new FakePending();
        private int prepares;

        @Override public CertificateActivationService.PendingDeployment prepare(
                AcmeIssuanceService.IssuedMaterial material) {
            prepares++;
            return pending;
        }
    }

    private static final class FakePending implements CertificateActivationService.PendingDeployment {
        private boolean committed;
        private boolean rolledBack;
        private boolean failAfterDurableCommit;
        private Runnable rollbackCheck = () -> { };

        @Override public void commit() throws IOException {
            committed = true;
            if (failAfterDurableCommit) throw new IOException("injected cleanup failure");
        }

        @Override public void rollback() { rollbackCheck.run(); rolledBack = true; }
        @Override public boolean isCommitted() { return committed; }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = NOW;

        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
