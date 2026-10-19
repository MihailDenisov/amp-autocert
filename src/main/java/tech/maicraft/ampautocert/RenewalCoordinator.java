package tech.maicraft.ampautocert;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-flight decision and renewal pipeline. All methods run on a background worker. */
final class RenewalCoordinator {
    private final AutoCertConfig config;
    private final Clock clock;
    private final Recovery recovery;
    private final ExpirySource expirySource;
    private final Issuer issuer;
    private final AttemptGate attemptGate;
    private final ActivationPreflight activationPreflight;
    private final Activator activator;
    private final DriftGate driftGate;
    private final AtomicBoolean stopping;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    RenewalCoordinator(AutoCertConfig config, Clock clock, SimpleRecovery recovery, ExpirySource expirySource,
                       Issuer issuer, ActivationPreflight activationPreflight,
                       AttemptGate attemptGate, Activator activator, AtomicBoolean stopping) {
        this(config, clock, ignored -> recovery.recover(), expirySource, issuer, activationPreflight, attemptGate, activator,
                ConfigurationDriftGuard.Result::ready, stopping);
    }

    RenewalCoordinator(AutoCertConfig config, Clock clock, Recovery recovery, ExpirySource expirySource,
                       Issuer issuer, ActivationPreflight activationPreflight,
                       AttemptGate attemptGate, Activator activator, DriftGate driftGate,
                       AtomicBoolean stopping) {
        this.config = config;
        this.clock = clock;
        this.recovery = recovery;
        this.expirySource = expirySource;
        this.issuer = issuer;
        this.activationPreflight = activationPreflight;
        this.attemptGate = attemptGate;
        this.activator = activator;
        this.driftGate = driftGate;
        this.stopping = stopping;
    }

    Result checkAndRenew() throws IOException, InterruptedException {
        return checkAndRenew(false);
    }

    Result checkAndRenew(boolean operatorForced) throws IOException, InterruptedException {
        if (!inFlight.compareAndSet(false, true)) return result(Outcome.ALREADY_RUNNING, null, Optional.empty(), false);
        try {
            if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty(), false);
            ConfigurationDriftGuard.Result initialDrift = driftGate.inspect();
            try {
                recovery.recover(initialDrift.allowed());
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException | RuntimeException recoveryFailure) {
                if (!initialDrift.allowed()) {
                    throw new BlockedRecoveryException(initialDrift.changedFields(), recoveryFailure);
                }
                throw recoveryFailure;
            }
            if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty(), false);
            if (!initialDrift.allowed()) return driftBlocked(initialDrift, null, Optional.empty());
            Optional<Instant> expiry = expirySource.expiresAt();
            RenewalPolicy.Decision decision = RenewalPolicy.decide(expiry, clock.instant(),
                    Duration.ofDays(config.renewBeforeDays()));
            Result driftBlocked = driftBlocked(decision, expiry);
            if (driftBlocked != null) return driftBlocked;
            if (decision == RenewalPolicy.Decision.VALID && !operatorForced) {
                return result(Outcome.NOT_DUE, decision, expiry, false);
            }
            if (operatorForced) decision = RenewalPolicy.Decision.DUE;
            // Staging certificates must never replace the public certificate used by AutoModpack.
            if (config.staging()) return result(Outcome.STAGING_BLOCKED, decision, expiry, false);
            if (!config.restartHostAfterRenew()) {
                return result(Outcome.RESTART_DISABLED, decision, expiry, false);
            }
            if (stopping.get()) return result(Outcome.STOPPING, decision, expiry, false);
            activationPreflight.verify();
            driftBlocked = driftBlocked(decision, expiry);
            if (driftBlocked != null) return driftBlocked;
            Optional<Instant> nextAttempt = attemptGate.reserve(clock.instant(), config.failureCooldown());
            if (nextAttempt.isPresent()) {
                return new Result(Outcome.COOLDOWN, decision, expiry, false, nextAttempt, List.of());
            }
            if (stopping.get()) return result(Outcome.STOPPING, decision, expiry, false);
            driftBlocked = driftBlocked(decision, expiry);
            if (driftBlocked != null) return driftBlocked;
            AcmeIssuanceService.IssuedMaterial material = issuer.issue(config.environment(), config.contactEmail());
            if (stopping.get()) throw new InterruptedException("Server is stopping");
            driftBlocked = driftBlocked(decision, expiry);
            if (driftBlocked != null) return driftBlocked;
            CertificateActivationService.ActivationResult activated = activator.activate(material);
            return result(Outcome.RENEWED, decision, expiry, activated.cleanupPending());
        } finally {
            inFlight.set(false);
        }
    }

    private static Result result(Outcome outcome, RenewalPolicy.Decision decision,
                                 Optional<Instant> expiry, boolean cleanupPending) {
        return new Result(outcome, decision, expiry, cleanupPending, Optional.empty(), List.of());
    }

    private Result driftBlocked(RenewalPolicy.Decision decision, Optional<Instant> expiry) {
        ConfigurationDriftGuard.Result drift = driftGate.inspect();
        return drift.allowed() ? null : driftBlocked(drift, decision, expiry);
    }

    private static Result driftBlocked(ConfigurationDriftGuard.Result drift,
                                       RenewalPolicy.Decision decision, Optional<Instant> expiry) {
        return new Result(Outcome.CONFIGURATION_DRIFT, decision, expiry,
                false, Optional.empty(), drift.changedFields());
    }

    enum Outcome { NOT_DUE, STAGING_BLOCKED, RESTART_DISABLED, CONFIGURATION_DRIFT,
        COOLDOWN, RENEWED, ALREADY_RUNNING, STOPPING }

    record Result(Outcome outcome, RenewalPolicy.Decision decision,
                  Optional<Instant> previousExpiry, boolean cleanupPending, Optional<Instant> nextAttempt,
                  List<String> driftFields) {
        Result { driftFields = List.copyOf(driftFields); }
    }

    static final class BlockedRecoveryException extends IOException {
        private final List<String> driftFields;

        BlockedRecoveryException(List<String> driftFields, Throwable cause) {
            super("Recovery failed while configuration automation was blocked", cause);
            this.driftFields = ConfigurationDriftGuard.safeFields(driftFields);
        }

        List<String> driftFields() { return driftFields; }
    }

    @FunctionalInterface interface Recovery {
        /** Always repairs durable files; host reconciliation is allowed only when the drift gate is open. */
        void recover(boolean allowHostReconciliation) throws IOException, InterruptedException;
    }

    @FunctionalInterface interface SimpleRecovery {
        void recover() throws IOException, InterruptedException;
    }

    @FunctionalInterface interface ExpirySource {
        Optional<Instant> expiresAt() throws IOException;
    }

    @FunctionalInterface interface Issuer {
        AcmeIssuanceService.IssuedMaterial issue(AcmeEnvironment environment, String contactEmail)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface interface ActivationPreflight {
        void verify() throws IOException, InterruptedException;
    }

    @FunctionalInterface interface AttemptGate {
        Optional<Instant> reserve(Instant now, Duration cooldown) throws IOException, InterruptedException;
    }

    @FunctionalInterface interface Activator {
        CertificateActivationService.ActivationResult activate(AcmeIssuanceService.IssuedMaterial material)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface interface DriftGate {
        ConfigurationDriftGuard.Result inspect();
    }
}
