package tech.maicraft.ampautocert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Activates a validated pending certificate pair and commits it only after AutoModpack reports
 * the exact leaf fingerprint. This class must be invoked from a background worker.
 */
final class CertificateActivationService {
    private static final int MAX_CHAIN_BYTES = 512 * 1024;

    private final Deployment deployment;
    private final AutoModpackHostControl host;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration timeout;
    private final Duration pollInterval;
    private final BooleanSupplier stopping;

    CertificateActivationService(CertificateDeploymentService deployment,
                                 AutoModpackHostControl host,
                                 Clock clock, Sleeper sleeper,
                                 AutoCertConfig config, BooleanSupplier stopping) {
        this(material -> {
            CertificateDeploymentService.PendingDeployment pending;
            try {
                pending = deployment.prepare(material);
            } catch (GeneralSecurityException validationFailure) {
                throw new IOException("Certificate deployment validation failed", validationFailure);
            }
            return new PendingDeployment() {
                @Override public void commit() throws IOException { pending.commit(); }
                @Override public void rollback() throws IOException { pending.rollback(); }
                @Override public void completeRollbackReconciliation() throws IOException, InterruptedException {
                    pending.completeRollbackReconciliation();
                }
                @Override public boolean isCommitted() { return pending.isCommitted(); }
            };
        }, host, clock, sleeper, config.hostActivationTimeout(), config.hostReadinessPoll(), stopping);
    }

    CertificateActivationService(Deployment deployment, AutoModpackHostControl host,
                                 Clock clock, Sleeper sleeper, Duration timeout,
                                 Duration pollInterval, BooleanSupplier stopping) {
        this.deployment = deployment;
        this.host = host;
        this.clock = clock;
        this.sleeper = sleeper;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
        this.stopping = stopping;
    }

    ActivationResult deployAndActivate(AcmeIssuanceService.IssuedMaterial material)
            throws IOException, InterruptedException {
        requireBackgroundThread();
        AutoModpackHostControl.HostSnapshot previous = requireAvailableHost();
        if (stopping.getAsBoolean()) throw new InterruptedException("Server is stopping");

        String expectedFingerprint = leafFingerprint(material.fullChainPem());
        PendingDeployment pending = deployment.prepare(material);
        try {
            Instant deadline = clock.instant().plus(timeout);
            restart(deadline);
            awaitReady(deadline, expectedFingerprint);
            try {
                pending.commit();
                return new ActivationResult(false);
            } catch (IOException cleanupFailure) {
                if (pending.isCommitted()) return new ActivationResult(true);
                throw cleanupFailure;
            }
        } catch (IOException | InterruptedException failure) {
            if (pending.isCommitted()) throw failure;
            try {
                host.withTransitionLock(pending::rollback);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw failure;
            }
            boolean restoreInterrupt = failure instanceof InterruptedException;
            if (restoreInterrupt) Thread.interrupted();
            if (!stopping.getAsBoolean()) {
                try {
                    reconcileRecoveredHost(Optional.ofNullable(previous.certificateFingerprint()),
                            expectedFingerprint);
                    pending.completeRollbackReconciliation();
                } catch (IOException | InterruptedException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                    if (restoreFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                } finally {
                    if (restoreInterrupt) Thread.currentThread().interrupt();
                }
            } else if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        }
    }

    void preflight() throws IOException, InterruptedException {
        requireBackgroundThread();
        if (stopping.getAsBoolean()) throw new InterruptedException("Server is stopping");
        requireAvailableHost();
    }

    void reconcileRecoveredHost(Optional<String> restoredFingerprint, String previouslyLoadedFingerprint)
            throws IOException, InterruptedException {
        requireBackgroundThread();
        if (stopping.getAsBoolean()) throw new InterruptedException("Server is stopping");
        Instant deadline = clock.instant().plus(timeout);
        restart(deadline);
        while (clock.instant().isBefore(deadline)) {
            if (stopping.getAsBoolean()) throw new InterruptedException("Server is stopping");
            AutoModpackHostControl.HostSnapshot state = host.snapshot();
            if (!state.compatible()) throw new IOException(state.detail());
            if (state.running()) {
                if (restoredFingerprint.isPresent()
                        && restoredFingerprint.get().equals(state.certificateFingerprint())) return;
                if (restoredFingerprint.isEmpty()
                        && !Objects.equals(previouslyLoadedFingerprint, state.certificateFingerprint())) return;
            }
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isZero() || remaining.isNegative()) break;
            sleeper.sleep(pollInterval.compareTo(remaining) < 0 ? pollInterval : remaining);
        }
        throw new IOException("AutoModpack did not reconcile the recovered certificate state");
    }

    private AutoModpackHostControl.HostSnapshot requireAvailableHost() throws IOException {
        AutoModpackHostControl.HostSnapshot state = host.snapshot();
        if (!state.compatible()) throw new IOException(state.detail());
        if (state.running() && state.certificateFingerprint() == null) {
            throw new IOException("AutoModpack internal TLS is disabled; automatic certificate activation is unavailable");
        }
        return state;
    }

    private boolean restart(Instant deadline) throws IOException, InterruptedException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) throw new IOException("AutoModpack activation timed out");
        var future = host.restart(() -> !stopping.getAsBoolean() && clock.instant().isBefore(deadline))
                .toCompletableFuture();
        try {
            int result = future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            if (result <= 0) throw new IOException("AutoModpack host restart command was rejected");
            return true;
        } catch (TimeoutException timeoutFailure) {
            future.cancel(false);
            throw new IOException("AutoModpack host restart command timed out");
        } catch (CancellationException cancelled) {
            throw new IOException("AutoModpack host restart command was cancelled");
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            throw interrupted;
        } catch (ExecutionException commandFailure) {
            throw new IOException("AutoModpack host restart command failed");
        }
    }

    private void awaitReady(Instant deadline, String expectedFingerprint)
            throws IOException, InterruptedException {
        while (clock.instant().isBefore(deadline)) {
            if (stopping.getAsBoolean()) throw new InterruptedException("Server is stopping");
            AutoModpackHostControl.HostSnapshot state = host.snapshot();
            if (!state.compatible()) throw new IOException(state.detail());
            if (state.running() && expectedFingerprint != null
                    && expectedFingerprint.equals(state.certificateFingerprint())) return;

            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isZero() || remaining.isNegative()) break;
            sleeper.sleep(pollInterval.compareTo(remaining) < 0 ? pollInterval : remaining);
        }
        throw new IOException("AutoModpack did not become ready with the expected certificate");
    }

    private static String leafFingerprint(String pem) throws IOException {
        byte[] bytes = pem.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0 || bytes.length > MAX_CHAIN_BYTES) {
            throw new IOException("Issued certificate chain has an invalid size");
        }
        try {
            Collection<? extends java.security.cert.Certificate> certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(bytes));
            if (certificates.isEmpty() || !(certificates.iterator().next() instanceof X509Certificate leaf)) {
                throw new CertificateException("Issued certificate chain has no X.509 leaf");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        } catch (CertificateException | NoSuchAlgorithmException invalid) {
            throw new IOException("Issued certificate fingerprint is unavailable", invalid);
        }
    }

    private void requireBackgroundThread() throws IOException {
        if (host.isServerThread()) {
            throw new IOException("Certificate activation must not block the Minecraft server thread");
        }
    }

    record ActivationResult(boolean cleanupPending) { }

    @FunctionalInterface
    interface Deployment {
        PendingDeployment prepare(AcmeIssuanceService.IssuedMaterial material)
                throws IOException, InterruptedException;
    }

    interface PendingDeployment {
        void commit() throws IOException;
        void rollback() throws IOException;
        default void completeRollbackReconciliation() throws IOException, InterruptedException { }
        boolean isCommitted();
    }
}
