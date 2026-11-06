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
import java.util.function.Consumer;
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
    private final AtomicReference<String> lastDriftWarning = new AtomicReference<>();
    private volatile RenewalCoordinator coordinator;
    private volatile StagingIssuanceProbe stagingProbe;
    private volatile PreflightService preflight;
    private volatile ConfigurationDriftGuard driftGuard;
    private volatile ReconciliationCoordinator reconciliation;
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
            JdkHttpTransport preflightTransport = new JdkHttpTransport(
                    cfg.acmeNetworkTimeout(), cfg.acmeNetworkTimeout());
            AutoModpackReflectionConfigSource autoModpackSource = new AutoModpackReflectionConfigSource(
                    root, server.getPort(), AutoCertService.class.getClassLoader());
            PreflightService createdPreflight = new PreflightService(cfg, root, clock,
                    autoModpackSource,
                    preflightTransport,
                    () -> CloudflareCredentialSource.load(root, cfg.allowInsecureTokenFilePermissions()),
                    () -> CredentialTargetInspector.inspect(root,
                            cfg.allowInsecureDeploymentFilePermissions()));
            preflight = createdPreflight;
            EnrollmentStateStore enrollmentStore = new EnrollmentStateStore(root);
            ConfigurationDriftGuard createdDriftGuard = new ConfigurationDriftGuard(cfg, autoModpackSource,
                    enrollmentStore);
            driftGuard = createdDriftGuard;
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "amp-autocert-worker");
                t.setDaemon(true);
                return t;
            });
            stopping.set(false);
            if (!cfg.enabled()) {
                status.set(new OperatorStatus(false, Optional.of(cfg.environment()), "disabled", Optional.empty(),
                        java.util.List.of(), Optional.empty(), "disabled by configuration", Optional.empty()));
                LOG.info("amp-autocert renewal is disabled; the read-only doctor command remains available.");
                return;
            }
            if (cfg.allowInsecureTokenFilePermissions())
                LOG.warn("Insecure Cloudflare token-file permission compatibility mode is enabled; restrict panel and container access.");
            if (cfg.allowInsecureDeploymentFilePermissions())
                LOG.warn("Insecure deployment and ACME state permission compatibility mode is enabled; private files may be readable by other users. Restrict panel and container access.");
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
                    allowHostReconciliation -> recover(cfg, paths, accountState, deployment, host, activation,
                            createdDriftGuard, allowHostReconciliation),
                    () -> inspectExpiry(paths), issuer,
                    activation::preflight,
                    (now, cooldown) -> accountState(paths, accountState)
                            .reserveRenewalAttempt(cfg.environment(), now, cooldown),
                    activation::deployAndActivate, createdDriftGuard::inspect, stopping);
            coordinator = createdCoordinator;
            stagingProbe = new StagingIssuanceProbe(cfg,
                    (environment, email) -> issue(cfg, accountState(paths, accountState), environment, email),
                    validator::inspectStagingProbe, clock,
                    (now, cooldown) -> accountState(paths, accountState)
                            .reserveRenewalAttempt(AcmeEnvironment.STAGING, now, cooldown),
                    createdDriftGuard::inspect, stopping);
            reconciliation = new ReconciliationCoordinator(cfg, createdPreflight::runForReconciliation,
                    autoModpackSource,
                    (environment, email) -> issue(cfg, accountState(paths, accountState), environment, email),
                    validator::inspectStagingProbe,
                    (now, cooldown) -> accountState(paths, accountState)
                            .reserveRenewalAttempt(AcmeEnvironment.STAGING, now, cooldown),
                    createdDriftGuard,
                    candidate -> acceptReconciliation(createdDriftGuard, candidate),
                    clock, stopping);

            status.set(new OperatorStatus(true, Optional.of(cfg.environment()), "running", Optional.empty(),
                    java.util.List.of(), Optional.empty(), "initial check scheduled", Optional.empty()));
            retryQueue = new RetryQueue(timer);
            timer.scheduleWithFixedDelay(() -> runScheduled(createdCoordinator, false), 10,
                    cfg.checkInterval().toSeconds(), TimeUnit.SECONDS);
            timer.scheduleWithFixedDelay(() -> pollConfigurationDrift(createdDriftGuard),
                    cfg.driftPollInterval().toSeconds(), cfg.driftPollInterval().toSeconds(), TimeUnit.SECONDS);
            LOG.info("amp-autocert renewal lifecycle started in {} mode; next check is scheduled in 10 seconds.",
                    cfg.staging() ? "staging-safe" : "production");
        } catch (Exception failure) {
            stopping.set(true);
            if (timer != null) timer.shutdownNow();
            timer = null;
            coordinator = null;
            stagingProbe = null;
            preflight = null;
            driftGuard = null;
            reconciliation = null;
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
            updateStatus(checkedAt, result.outcome().name().toLowerCase(java.util.Locale.ROOT), result.nextAttempt(),
                    result.driftFields());
            switch (result.outcome()) {
                case NOT_DUE -> LOG.info("AutoModpack certificate status: {}; expiry: {}.", result.decision(),
                        result.previousExpiry().map(Object::toString).orElse("not found"));
                case STAGING_BLOCKED -> LOG.warn("AutoModpack certificate status: {}; expiry: {}. Automatic renewal is blocked while acme.environment=staging; staging certificates are never deployed.",
                        result.decision(), result.previousExpiry().map(Object::toString).orElse("not found"));
                case RESTART_DISABLED -> LOG.warn("AutoModpack certificate status: {}; expiry: {}. Automatic renewal is blocked because host.restartAfterRenew=false.",
                        result.decision(), result.previousExpiry().map(Object::toString).orElse("not found"));
                case CONFIGURATION_DRIFT -> logDriftWarning(result.driftFields());
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
        } catch (RenewalCoordinator.BlockedRecoveryException blockedRecovery) {
            updateStatus(checkedAt, "recovery failed while configuration blocked", Optional.empty(),
                    blockedRecovery.driftFields());
            LOG.error("amp-autocert recovery failed while configuration automation was blocked; the host was not restarted and the recovery marker was preserved.");
            scheduleFailureRetry(coordinator, operatorForced);
        } catch (InterruptedException stoppingNow) {
            Thread.currentThread().interrupt();
            updateStatusPreservingDrift(checkedAt,
                    stopping.get() ? "stopped during shutdown" : "interrupted", Optional.empty());
            if (!stopping.get()) LOG.warn("amp-autocert renewal was interrupted.");
        } catch (Exception failure) {
            updateStatusPreservingDrift(checkedAt,
                    "failed (" + failure.getClass().getSimpleName() + ")", Optional.empty());
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
        updateStatus(checkedAt, outcome, nextAttempt, java.util.List.of());
    }

    private void updateStatusPreservingDrift(Instant checkedAt, String outcome, Optional<Instant> nextAttempt) {
        updateStatus(checkedAt, outcome, nextAttempt, status.get().configurationDriftFields());
    }

    private void updateStatus(Instant checkedAt, String outcome, Optional<Instant> nextAttempt,
                              java.util.List<String> driftFields) {
        updateStatus(checkedAt, outcome, nextAttempt, driftFields,
                stopping.get() ? "stopping" : driftFields.isEmpty() ? "running" : "blocked configuration drift");
    }

    private void updateStatus(Instant checkedAt, String outcome, Optional<Instant> nextAttempt,
                              java.util.List<String> driftFields, String lifecycle) {
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
                stopping.get() ? "stopping" : lifecycle, expiry, sans,
                Optional.of(checkedAt), outcome, nextAttempt, driftFields));
    }

    private void pollConfigurationDrift(ConfigurationDriftGuard guard) {
        if (stopping.get()) return;
        ConfigurationDriftGuard.Result result = guard.inspect();
        if (result.allowed()) {
            lastDriftWarning.set(null);
            OperatorStatus previous = status.get();
            if (!previous.configurationDriftFields().isEmpty()) {
                status.set(new OperatorStatus(previous.enabled(), previous.environment(), "running",
                        previous.certificateExpiry(), previous.certificateSans(), previous.lastCheck(),
                        "configuration accepted", previous.nextAttempt(), java.util.List.of()));
            }
            return;
        }
        OperatorStatus previous = status.get();
        status.set(new OperatorStatus(previous.enabled(), previous.environment(),
                "blocked configuration drift", previous.certificateExpiry(), previous.certificateSans(),
                previous.lastCheck(), "configuration_drift", previous.nextAttempt(), result.changedFields()));
        logDriftWarning(result.changedFields());
    }

    private void logDriftWarning(java.util.List<String> fields) {
        String summary = String.join(", ", fields);
        String previous = lastDriftWarning.getAndSet(summary);
        if (!summary.equals(previous)) {
            LOG.warn("amp-autocert automation is blocked by configuration drift or missing acceptance: {}. The deployed certificate/key pair is preserved.",
                    summary);
        }
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

    public synchronized DoctorRequest requestDoctor(Consumer<PreflightReport> completion) {
        if (stopping.get()) return timer == null ? DoctorRequest.NOT_RUNNING : DoctorRequest.STOPPING;
        PreflightService activePreflight = preflight;
        if (timer == null || activePreflight == null) return DoctorRequest.NOT_RUNNING;
        if (!checkQueued.compareAndSet(false, true)) return DoctorRequest.ALREADY_QUEUED;
        try {
            timer.execute(() -> runDoctor(activePreflight, completion));
            return DoctorRequest.QUEUED;
        } catch (RejectedExecutionException rejected) {
            checkQueued.set(false);
            return stopping.get() ? DoctorRequest.STOPPING : DoctorRequest.NOT_RUNNING;
        }
    }

    public synchronized ReconcileRequest requestReconciliation(
            Consumer<ReconciliationCoordinator.Result> completion) {
        if (stopping.get()) return timer == null ? ReconcileRequest.NOT_RUNNING : ReconcileRequest.STOPPING;
        ReconciliationCoordinator activeReconciliation = reconciliation;
        if (timer == null || activeReconciliation == null) return ReconcileRequest.NOT_RUNNING;
        if (!checkQueued.compareAndSet(false, true)) return ReconcileRequest.ALREADY_QUEUED;
        try {
            timer.execute(() -> runReconciliation(activeReconciliation, completion));
            return ReconcileRequest.QUEUED;
        } catch (RejectedExecutionException rejected) {
            checkQueued.set(false);
            return stopping.get() ? ReconcileRequest.STOPPING : ReconcileRequest.NOT_RUNNING;
        }
    }

    private void runReconciliation(ReconciliationCoordinator activeReconciliation,
                                   Consumer<ReconciliationCoordinator.Result> completion) {
        Instant checkedAt = clock.instant();
        ReconciliationCoordinator.Result result;
        try {
            result = activeReconciliation.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = ReconciliationCoordinator.Result.of(ReconciliationCoordinator.Outcome.STOPPING);
        } catch (RuntimeException unexpected) {
            result = new ReconciliationCoordinator.Result(ReconciliationCoordinator.Outcome.STATE_FAILED,
                    Optional.empty(), Optional.empty(), java.util.List.of("enrollment.state"));
        }
        String lifecycle = switch (result.outcome()) {
            case SUCCESS -> "active";
            case COOLDOWN -> "staging validation pending";
            case PREFLIGHT_FAILED -> "blocked preflight";
            case CONFIGURATION_CHANGED -> "blocked configuration drift";
            case STAGING_FAILED -> "staging validation failed";
            case STATE_FAILED -> "error";
            case STOPPING -> "stopping";
        };
        updateStatus(checkedAt, "reconcile_" + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
                result.nextAttempt(), result.blockedFields(), lifecycle);
        if (result.outcome() == ReconciliationCoordinator.Outcome.SUCCESS) {
            lastDriftWarning.set(null);
            LOG.info("amp-autocert reconciliation completed after staging validation; the new baseline is active.");
        } else if (result.outcome() != ReconciliationCoordinator.Outcome.STOPPING) {
            LOG.warn("amp-autocert reconciliation remains blocked ({}); deployed credentials were preserved.",
                    result.outcome().name().toLowerCase(java.util.Locale.ROOT));
        }
        try {
            completion.accept(result);
        } catch (RuntimeException callbackFailure) {
            LOG.warn("amp-autocert reconciliation completed, but its command response could not be delivered.");
        } finally {
            checkQueued.set(false);
        }
    }

    private void runDoctor(PreflightService activePreflight, Consumer<PreflightReport> completion) {
        try {
            PreflightReport report = activePreflight.run();
            LOG.info("amp-autocert doctor completed: {}.", report.passed() ? "pass" : "blocked");
            completion.accept(report);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.info("amp-autocert doctor stopped before completion.");
        } catch (RuntimeException failure) {
            LOG.error("amp-autocert doctor failed unexpectedly without changing DNS or credentials ({}).",
                    failure.getClass().getSimpleName());
        } finally {
            checkQueued.set(false);
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
            if (result.outcome() == StagingIssuanceProbe.Outcome.CONFIGURATION_DRIFT) {
                updateStatus(checkedAt, "configuration_drift", Optional.empty(), result.driftFields());
                logDriftWarning(result.driftFields());
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
    public enum DoctorRequest { QUEUED, ALREADY_QUEUED, NOT_RUNNING, STOPPING }
    public enum ReconcileRequest { QUEUED, ALREADY_QUEUED, NOT_RUNNING, STOPPING }
    private enum QueueRequest { QUEUED, ALREADY_QUEUED, NOT_RUNNING, STOPPING }

    private AcmeIssuanceService.IssuedMaterial issue(AutoCertConfig cfg, FileAcmeAccountState accountState,
                                                      AcmeEnvironment environment, String email)
            throws IOException, InterruptedException {
        return issuance(cfg, accountState).issue(environment, email);
    }

    private void recover(AutoCertConfig cfg, CertificatePaths paths,
                         AtomicReference<FileAcmeAccountState> accountStateReference,
                         CertificateDeploymentService deployment, AutoModpackHostControl host,
                         CertificateActivationService activation, ConfigurationDriftGuard activeDriftGuard,
                         boolean allowHostReconciliation)
            throws IOException, InterruptedException {
        AutoModpackHostControl.HostSnapshot before;
        try {
            before = host.snapshot();
        } catch (IOException unavailable) {
            deployment.recoverAndReport();
            if (!allowHostReconciliation) {
                recoverInterruptedIssuance(cfg, paths, accountStateReference);
                return;
            }
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
            if (!allowHostReconciliation || !activeDriftGuard.inspect().allowed()) {
                recoverInterruptedIssuance(cfg, paths, accountStateReference);
                return;
            }
            Optional<String> restoredFingerprint;
            try {
                restoredFingerprint = CertificateInspector.fingerprint(paths.fullChain());
            } catch (CertificateException invalidCertificate) {
                throw new IOException("Recovered AutoModpack certificate cannot be parsed", invalidCertificate);
            }
            activation.reconcileRecoveredHost(restoredFingerprint, before.certificateFingerprint());
            deployment.completeRecoveryReconciliation();
        } else if (recovered.get().credentialsChanged() && allowHostReconciliation) {
            throw new IOException("Certificate files were recovered, but AutoModpack host reconciliation is pending");
        }
        recoverInterruptedIssuance(cfg, paths, accountStateReference);
    }

    private void recoverInterruptedIssuance(AutoCertConfig cfg, CertificatePaths paths,
                                            AtomicReference<FileAcmeAccountState> accountStateReference)
            throws IOException, InterruptedException {
        FileAcmeAccountState accountState = accountState(paths, accountStateReference);
        if (accountState.loadIssuance(cfg.environment()).isPresent())
            issuance(cfg, accountState).recoverInterruptedIssuance(cfg.environment());
    }

    private synchronized void acceptReconciliation(ConfigurationDriftGuard activeDriftGuard,
                                                   ConfigurationBaseline candidate)
            throws IOException, InterruptedException {
        if (stopping.get()) throw new InterruptedException("Server is stopping");
        activeDriftGuard.acceptAfterReconciliation(candidate);
    }

    private FileAcmeAccountState accountState(CertificatePaths paths,
                                                      AtomicReference<FileAcmeAccountState> reference)
            throws IOException {
        FileAcmeAccountState existing = reference.get();
        if (existing != null) return existing;
        FileAcmeAccountState created = new FileAcmeAccountState(paths.accountState(),
                config.allowInsecureDeploymentFilePermissions(), message -> LOG.warn("{}", message));
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
                previous.lastOutcome(), previous.nextAttempt(), previous.configurationDriftFields()));
        if (timer != null) {
            if (retryQueue != null) retryQueue.cancel();
            retryQueue = null;
            retryOperatorForced = false;
            timer.shutdownNow();
            timer = null;
            coordinator = null;
            stagingProbe = null;
            preflight = null;
            driftGuard = null;
            reconciliation = null;
            config = null;
            checkQueued.set(false);
            LOG.info("amp-autocert background worker stopping; scheduled work cancelled and interrupted.");
        }
    }

    public void stop() {
        beginStop();
    }
}
