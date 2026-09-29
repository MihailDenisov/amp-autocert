package tech.maicraft.ampautocert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

/**
 * Dedicated-server lifecycle for the single-flight renewal pipeline. All ACME, DNS and
 * certificate work runs on the private background worker; only the restart command is dispatched
 * to the Minecraft server thread by {@link AutoModpackHostControl}.
 */
public final class AutoCertService {
    private static final Logger LOG = LoggerFactory.getLogger("amp-autocert");
    private final Path root;
    private final Clock clock;
    private final DoubleSupplier jitterRandom;
    private final AtomicBoolean stopping = new AtomicBoolean(true);
    private final AtomicBoolean checkQueued = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<OperatorStatus> status = new AtomicReference<>(OperatorStatus.notStarted());
    private volatile RenewalCoordinator coordinator;
    private volatile StagingIssuanceProbe stagingProbe;
    private volatile CertificatePaths certificatePaths;
    private volatile AutoCertConfig config;
    private ScheduledExecutorService timer;
    private RetryQueue retryQueue;
    private boolean retryOperatorForced;

    public AutoCertService(Path root, Clock clock) {
        this(root, clock, () -> ThreadLocalRandom.current().nextDouble());
    }

    AutoCertService(Path root, Clock clock, DoubleSupplier jitterRandom) {
        this.root = root;
        this.clock = clock;
        this.jitterRandom = jitterRandom;
    }

    public synchronized void start(MinecraftServer server) {
        if (timer != null) return;
        try {
            AutoCertConfig cfg = AutoCertConfig.load(root);
            config = cfg;
            SensitiveLibraryLogging.suppressProtocolPayloads();
            if (!cfg.enabled()) {
                status.set(new OperatorStatus(false, Optional.of(cfg.environment()), "disabled", Optional.empty(),
                        java.util.List.of(), Optional.empty(), "disabled by configuration", Optional.empty()));
                LOG.info("amp-autocert is disabled in automodpack/amp-autocert.properties.");
                return;
            }
            if (cfg.allowInsecureTokenFilePermissions())
                LOG.warn("Insecure Cloudflare token-file permission compatibility mode is enabled; restrict panel and container access.");
            if (cfg.allowInsecureDeploymentFilePermissions())
                LOG.warn("Insecure certificate deployment permission compatibility mode is enabled; deployment will not change file permissions.");
            if (cfg.debugMode())
                LOG.info("amp-autocert safe diagnostic logging is enabled; provider payloads, tokens, account state and key material remain suppressed.");
            CertificatePaths paths = CertificatePaths.inside(root);
            certificatePaths = paths;
            AtomicReference<FileAcmeAccountState> accountState = new AtomicReference<>();
            IssuedCertificateValidator validator = IssuedCertificateValidator.usingSystemTrust(clock);
            CertificateDeploymentService deployment = new CertificateDeploymentService(root, validator, cfg);
            AutoModpackHostControl host = AutoModpackHostControl.forServer(server);
            CertificateActivationService activation = new CertificateActivationService(
                    deployment, host, clock, Sleeper.SYSTEM, cfg, stopping::get);
            RenewalCoordinator.Issuer issuer = (environment, email) -> issue(cfg,
                    accountState(paths, accountState), environment, email);
            RenewalCoordinator createdCoordinator = new RenewalCoordinator(cfg, clock,
                    () -> recover(cfg, paths, accountState, deployment, host, activation),
                    () -> inspectExpiry(paths), issuer,
                    activation::preflight,
                    (now, cooldown) -> accountState(paths, accountState)
                            .reserveRenewalAttempt(cfg.environment(), now, cooldown),
                    activation::deployAndActivate, stopping);
            coordinator = createdCoordinator;
            stagingProbe = new StagingIssuanceProbe(cfg,
                    (environment, email) -> issue(cfg, accountState(paths, accountState), environment, email),
                    validator::inspectStagingProbe, clock,
                    (now, cooldown) -> accountState(paths, accountState)
                            .reserveRenewalAttempt(AcmeEnvironment.STAGING, now, cooldown), stopping);

            stopping.set(false);
            status.set(new OperatorStatus(true, Optional.of(cfg.environment()), "running", Optional.empty(),
                    java.util.List.of(), Optional.empty(), "initial check scheduled", Optional.empty()));
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "amp-autocert-renewal");
                t.setDaemon(true);
                return t;
            });
            retryQueue = new RetryQueue(timer);
            timer.scheduleWithFixedDelay(() -> runScheduled(createdCoordinator, false), 10,
                    cfg.checkInterval().toSeconds(), TimeUnit.SECONDS);
            LOG.info("amp-autocert renewal lifecycle started in {} mode; next check is scheduled in 10 seconds.",
                    cfg.staging() ? "staging-safe" : "production");
        } catch (Exception failure) {
            stopping.set(true);
            coordinator = null;
            stagingProbe = null;
            certificatePaths = null;
            config = null;
            status.set(new OperatorStatus(false, Optional.empty(), "initialization failed", Optional.empty(),
                    java.util.List.of(), Optional.empty(), "failed (" + failure.getClass().getSimpleName() + ")",
                    Optional.empty()));
            LOG.error("Cannot initialize amp-autocert renewal lifecycle: {}", safeFailure(failure));
        }
    }

    private void runScheduled(RenewalCoordinator scheduledCoordinator, boolean operatorForced) {
        if (!checkQueued.compareAndSet(false, true)) return;
        runQueued(scheduledCoordinator, operatorForced);
    }

    private void runQueued(RenewalCoordinator queuedCoordinator, boolean operatorForced) {
        try {
            runSafely(queuedCoordinator, operatorForced);
        } finally {
            checkQueued.set(false);
        }
    }

    private void runSafely(RenewalCoordinator coordinator, boolean operatorForced) {
        Instant checkedAt = clock.instant();
        try {
            RenewalCoordinator.Result result = coordinator.checkAndRenew(operatorForced);
            updateStatus(checkedAt, result.outcome().name().toLowerCase(java.util.Locale.ROOT), result.nextAttempt());
            switch (result.outcome()) {
                case NOT_DUE -> LOG.info("AutoModpack certificate status: {}; expiry: {}.", result.decision(),
                        result.previousExpiry().map(Object::toString).orElse("not found"));
                case STAGING_BLOCKED -> LOG.warn("AutoModpack certificate status: {}; expiry: {}. Automatic renewal is blocked while acme.environment=staging; staging certificates are never deployed.",
                        result.decision(), result.previousExpiry().map(Object::toString).orElse("not found"));
                case RESTART_DISABLED -> LOG.warn("AutoModpack certificate status: {}; expiry: {}. Automatic renewal is blocked because host.restartAfterRenew=false.",
                        result.decision(), result.previousExpiry().map(Object::toString).orElse("not found"));
                case COOLDOWN -> LOG.warn("AutoModpack certificate status: {}; production issuance cooldown is active until {}.",
                        result.decision(), result.nextAttempt().map(Object::toString).orElse("unknown"));
                case RENEWED -> LOG.info("AutoModpack production certificate renewed and activated{}.",
                        result.cleanupPending() ? "; committed cleanup will resume after restart" : "");
                case ALREADY_RUNNING -> LOG.info("Certificate check skipped because a renewal is already running.");
                case STOPPING -> LOG.info("Certificate check stopped because the server is shutting down.");
            }
            if (result.outcome() == RenewalCoordinator.Outcome.COOLDOWN && result.nextAttempt().isPresent()) {
                scheduleCooldownRetry(coordinator, operatorForced, result.nextAttempt().get());
            } else if (result.outcome() != RenewalCoordinator.Outcome.ALREADY_RUNNING) {
                clearRetryState(operatorForced, result.outcome());
            }
        } catch (InterruptedException stoppingNow) {
            Thread.currentThread().interrupt();
            updateStatus(checkedAt, stopping.get() ? "stopped during shutdown" : "interrupted", Optional.empty());
            if (!stopping.get()) LOG.warn("amp-autocert renewal was interrupted.");
        } catch (Exception failure) {
            updateStatus(checkedAt, "failed (" + failure.getClass().getSimpleName() + ")", Optional.empty());
            LOG.error("amp-autocert renewal check failed; the last deployed certificate was preserved: {}",
                    safeFailure(failure));
            scheduleFailureRetry(coordinator, operatorForced);
        }
    }

    private void scheduleFailureRetry(RenewalCoordinator failedCoordinator, boolean operatorForced) {
        AutoCertConfig activeConfig = config;
        if (activeConfig == null || stopping.get()) return;
        int failures = consecutiveFailures.updateAndGet(value -> value == Integer.MAX_VALUE ? value : value + 1);
        RetryPolicy policy = new RetryPolicy(activeConfig.retryInitialDelay(), activeConfig.retryMaxDelay(),
                activeConfig.retryJitterPercent());
        Duration delay = policy.delay(failures, jitterRandom);
        scheduleRetry(failedCoordinator, operatorForced, clock.instant().plus(delay), delay);
    }

    private void scheduleCooldownRetry(RenewalCoordinator blockedCoordinator, boolean operatorForced,
                                       Instant cooldownUntil) {
        AutoCertConfig activeConfig = config;
        if (activeConfig == null || stopping.get()) return;
        consecutiveFailures.set(0);
        RetryPolicy policy = new RetryPolicy(activeConfig.retryInitialDelay(), activeConfig.retryMaxDelay(),
                activeConfig.retryJitterPercent());
        Duration guardJitter = policy.delay(1, jitterRandom);
        Instant target = cooldownUntil.plus(guardJitter);
        Duration delay = Duration.between(clock.instant(), target);
        if (delay.isNegative() || delay.isZero()) delay = guardJitter;
        scheduleRetry(blockedCoordinator, operatorForced, clock.instant().plus(delay), delay);
    }

    private synchronized void scheduleRetry(RenewalCoordinator retryCoordinator, boolean operatorForced,
                                            Instant target, Duration delay) {
        RetryQueue queue = retryQueue;
        if (timer == null || queue == null || stopping.get()) return;
        try {
            if (queue.schedule(target, delay, () -> runScheduled(retryCoordinator, operatorForced))) {
                retryOperatorForced = operatorForced;
                LOG.warn("amp-autocert scheduled a guarded background retry for {} after a failed or cooldown-blocked check.",
                        target);
            }
        } catch (RejectedExecutionException rejected) {
            queue.cancel();
            retryOperatorForced = false;
        }
    }

    private synchronized void clearRetryState(boolean operatorForced, RenewalCoordinator.Outcome outcome) {
        if (retryQueue != null && retryQueue.pending() && retryOperatorForced && !operatorForced
                && outcome == RenewalCoordinator.Outcome.NOT_DUE) return;
        consecutiveFailures.set(0);
        if (retryQueue != null) retryQueue.cancel();
        retryOperatorForced = false;
    }

    private void updateStatus(Instant checkedAt, String outcome, Optional<Instant> nextAttempt) {
        OperatorStatus previous = status.get();
        Optional<Instant> expiry = previous.certificateExpiry();
        java.util.List<String> sans = previous.certificateSans();
        CertificatePaths paths = certificatePaths;
        if (paths != null) {
            try {
                Optional<CertificateInspector.Summary> summary = CertificateInspector.summary(paths.fullChain());
                expiry = summary.map(CertificateInspector.Summary::expiresAt);
                sans = summary.map(CertificateInspector.Summary::dnsSans).orElse(java.util.List.of());
            } catch (IOException | CertificateException invalid) {
                outcome = outcome + "; certificate metadata unavailable";
            }
        }
        status.set(new OperatorStatus(previous.enabled(), previous.environment(),
                stopping.get() ? "stopping" : "running", expiry, sans,
                Optional.of(checkedAt), outcome, nextAttempt));
    }

    OperatorStatus status() {
        return status.get();
    }

    Clock clock() {
        return clock;
    }

    public synchronized CheckRequest requestCheck() {
        QueueRequest queued = queue(false);
        return switch (queued) {
            case QUEUED -> CheckRequest.QUEUED;
            case ALREADY_QUEUED -> CheckRequest.ALREADY_QUEUED;
            case NOT_RUNNING -> CheckRequest.NOT_RUNNING;
            case STOPPING -> CheckRequest.STOPPING;
        };
    }

    public synchronized RenewRequest requestRenewal() {
        if (stopping.get()) return timer == null ? RenewRequest.NOT_RUNNING : RenewRequest.STOPPING;
        if (timer == null || coordinator == null) return RenewRequest.NOT_RUNNING;
        OperatorStatus current = status.get();
        if (current.environment().orElse(AcmeEnvironment.STAGING) != AcmeEnvironment.PRODUCTION) {
            return RenewRequest.STAGING_BLOCKED;
        }
        QueueRequest queued = queue(true);
        return switch (queued) {
            case QUEUED -> RenewRequest.QUEUED;
            case ALREADY_QUEUED -> RenewRequest.ALREADY_QUEUED;
            case NOT_RUNNING -> RenewRequest.NOT_RUNNING;
            case STOPPING -> RenewRequest.STOPPING;
        };
    }

    public synchronized StagingRequest requestStagingIssuance() {
        if (stopping.get()) return timer == null ? StagingRequest.NOT_RUNNING : StagingRequest.STOPPING;
        AutoCertConfig activeConfig = config;
        StagingIssuanceProbe activeProbe = stagingProbe;
        if (timer == null || activeConfig == null || activeProbe == null) return StagingRequest.NOT_RUNNING;
        if (!activeConfig.staging() || !activeConfig.allowStagingIssuance()) return StagingRequest.NOT_ALLOWED;
        if (!checkQueued.compareAndSet(false, true)) return StagingRequest.ALREADY_QUEUED;
        try {
            timer.execute(() -> runStagingProbe(activeProbe));
            return StagingRequest.QUEUED;
        } catch (RejectedExecutionException rejected) {
            checkQueued.set(false);
            return stopping.get() ? StagingRequest.STOPPING : StagingRequest.NOT_RUNNING;
        }
    }

    private void runStagingProbe(StagingIssuanceProbe probe) {
        Instant checkedAt = clock.instant();
        try {
            StagingIssuanceProbe.Result result = probe.run();
            if (result.outcome() == StagingIssuanceProbe.Outcome.STOPPING) {
                updateStatus(checkedAt, "staging issuance stopped during shutdown", Optional.empty());
                return;
            }
            if (result.outcome() == StagingIssuanceProbe.Outcome.COOLDOWN) {
                updateStatus(checkedAt, "staging issuance cooldown", result.nextAttempt());
                LOG.warn("amp-autocert staging issuance was not started because its persisted cooldown is active until {}.",
                        result.nextAttempt().map(Object::toString).orElse("unknown"));
                return;
            }
            IssuedCertificateValidator.ValidationResult validation = result.validation();
            updateStatus(checkedAt, "staging issuance structurally inspected (not deployed)", Optional.empty());
            LOG.info("amp-autocert staging certificate structure inspected and discarded without deployment; expiry: {}; DNS SANs: {}.",
                    validation.notAfter(), String.join(", ", validation.dnsSans()));
        } catch (InterruptedException stoppingNow) {
            Thread.currentThread().interrupt();
            updateStatus(checkedAt, stopping.get() ? "staging issuance stopped during shutdown" : "staging issuance interrupted",
                    Optional.empty());
            if (!stopping.get()) LOG.warn("amp-autocert staging issuance was interrupted.");
        } catch (Exception failure) {
            updateStatus(checkedAt, "staging issuance failed (" + failure.getClass().getSimpleName() + ")",
                    Optional.empty());
            LOG.error("amp-autocert staging issuance failed; no certificate files were changed: {}", safeFailure(failure));
        } finally {
            checkQueued.set(false);
        }
    }

    private QueueRequest queue(boolean operatorForced) {
        if (stopping.get()) return timer == null ? QueueRequest.NOT_RUNNING : QueueRequest.STOPPING;
        RenewalCoordinator activeCoordinator = coordinator;
        if (timer == null || activeCoordinator == null) return QueueRequest.NOT_RUNNING;
        if (!checkQueued.compareAndSet(false, true)) return QueueRequest.ALREADY_QUEUED;
        try {
            timer.execute(() -> runQueued(activeCoordinator, operatorForced));
            return QueueRequest.QUEUED;
        } catch (RejectedExecutionException rejected) {
            checkQueued.set(false);
            return stopping.get() ? QueueRequest.STOPPING : QueueRequest.NOT_RUNNING;
        }
    }

    public enum CheckRequest { QUEUED, ALREADY_QUEUED, NOT_RUNNING, STOPPING }
    public enum RenewRequest { QUEUED, ALREADY_QUEUED, STAGING_BLOCKED, NOT_RUNNING, STOPPING }
    public enum StagingRequest { QUEUED, ALREADY_QUEUED, NOT_ALLOWED, NOT_RUNNING, STOPPING }
    private enum QueueRequest { QUEUED, ALREADY_QUEUED, NOT_RUNNING, STOPPING }

    private AcmeIssuanceService.IssuedMaterial issue(AutoCertConfig cfg, FileAcmeAccountState accountState,
                                                      AcmeEnvironment environment, String email)
            throws IOException, InterruptedException {
        return issuance(cfg, accountState).issue(environment, email);
    }

    private void recover(AutoCertConfig cfg, CertificatePaths paths,
                         AtomicReference<FileAcmeAccountState> accountStateReference,
                         CertificateDeploymentService deployment, AutoModpackHostControl host,
                         CertificateActivationService activation) throws IOException, InterruptedException {
        AutoModpackHostControl.HostSnapshot before;
        try {
            before = host.snapshot();
        } catch (IOException unavailable) {
            deployment.recoverAndReport();
            throw unavailable;
        }
        AtomicReference<CertificateDeploymentService.RecoveryResult> recovered = new AtomicReference<>();
        if (before.compatible() && before.initialized()) {
            host.withTransitionLock(() -> {
                try {
                    recovered.set(deployment.recoverAndReport());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while recovering certificate deployment", interrupted);
                }
            });
        } else {
            recovered.set(deployment.recoverAndReport());
        }
        if (recovered.get().credentialsChanged() && before.compatible() && before.initialized()) {
            Optional<String> restoredFingerprint;
            try {
                restoredFingerprint = CertificateInspector.fingerprint(paths.fullChain());
            } catch (CertificateException invalidCertificate) {
                throw new IOException("Recovered AutoModpack certificate cannot be parsed", invalidCertificate);
            }
            activation.reconcileRecoveredHost(restoredFingerprint, before.certificateFingerprint());
            deployment.completeRecoveryReconciliation();
        } else if (recovered.get().credentialsChanged()) {
            throw new IOException("Certificate files were recovered, but AutoModpack host reconciliation is pending");
        }
        FileAcmeAccountState accountState = accountState(paths, accountStateReference);
        if (accountState.loadIssuance(cfg.environment()).isPresent()) {
            issuance(cfg, accountState).recoverInterruptedIssuance(cfg.environment());
        }
    }

    private static FileAcmeAccountState accountState(CertificatePaths paths,
                                                      AtomicReference<FileAcmeAccountState> reference)
            throws IOException {
        FileAcmeAccountState existing = reference.get();
        if (existing != null) return existing;
        FileAcmeAccountState created = new FileAcmeAccountState(paths.accountState());
        if (reference.compareAndSet(null, created)) return created;
        return reference.get();
    }

    private AcmeIssuanceService issuance(AutoCertConfig cfg, FileAcmeAccountState accountState)
            throws IOException {
        String token = CloudflareCredentialSource.load(root, cfg.allowInsecureTokenFilePermissions());
        JdkHttpTransport transport = new JdkHttpTransport(cfg.acmeNetworkTimeout(), cfg.acmeNetworkTimeout());
        CloudflareDnsClient dns = new CloudflareDnsClient(transport, cfg, token);
        DohDnsTxtResolver resolver = new DohDnsTxtResolver(transport, cfg.dnsDohResolvers(), clock,
                message -> { if (cfg.debugMode()) LOG.debug("DNS diagnostic: {}", message); });
        return new AcmeIssuanceService(accountState, accountState,
                new Acme4jGateway(cfg, clock), dns, resolver, clock, Sleeper.SYSTEM, cfg,
                message -> LOG.debug("ACME diagnostic: {}", message));
    }

    private static Optional<Instant> inspectExpiry(CertificatePaths paths) throws IOException {
        try {
            return CertificateInspector.expiresAt(paths.fullChain());
        } catch (CertificateException invalidCertificate) {
            throw new IOException("Existing AutoModpack certificate cannot be parsed", invalidCertificate);
        }
    }

    private static String safeFailure(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public synchronized void beginStop() {
        stopping.set(true);
        OperatorStatus previous = status.get();
        status.set(new OperatorStatus(previous.enabled(), previous.environment(), "stopping",
                previous.certificateExpiry(), previous.certificateSans(), previous.lastCheck(),
                previous.lastOutcome(), previous.nextAttempt()));
        if (timer != null) {
            if (retryQueue != null) retryQueue.cancel();
            retryQueue = null;
            retryOperatorForced = false;
            timer.shutdownNow();
            timer = null;
            coordinator = null;
            stagingProbe = null;
            config = null;
            checkQueued.set(false);
            LOG.info("amp-autocert renewal lifecycle stopping; scheduled checks cancelled and background work interrupted.");
        }
    }

    public void stop() {
        beginStop();
    }
}
