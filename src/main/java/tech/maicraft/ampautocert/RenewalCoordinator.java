package tech.maicraft.ampautocert;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final AtomicBoolean stopping;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    RenewalCoordinator(AutoCertConfig config, Clock clock, Recovery recovery, ExpirySource expirySource,
                       Issuer issuer, ActivationPreflight activationPreflight,
                       AttemptGate attemptGate, Activator activator, AtomicBoolean stopping) {
        this.config = config;
        this.clock = clock;
        this.recovery = recovery;
        this.expirySource = expirySource;
        this.issuer = issuer;
        this.activationPreflight = activationPreflight;
        this.attemptGate = attemptGate;
        this.activator = activator;
        this.stopping = stopping;
    }

    Result checkAndRenew() throws IOException, InterruptedException {
        return checkAndRenew(false);
    }

    Result checkAndRenew(boolean operatorForced) throws IOException, InterruptedException {
        if (!inFlight.compareAndSet(false, true)) return result(Outcome.ALREADY_RUNNING, null, Optional.empty(), false);
        try {
            if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty(), false);
            recovery.recover();
            if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty(), false);
            Optional<Instant> expiry = expirySource.expiresAt();
            RenewalPolicy.Decision decision = RenewalPolicy.decide(expiry, clock.instant(),
                    Duration.ofDays(config.renewBeforeDays()));
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
            Optional<Instant> nextAttempt = attemptGate.reserve(clock.instant(), config.failureCooldown());
            if (nextAttempt.isPresent()) {
                return new Result(Outcome.COOLDOWN, decision, expiry, false, nextAttempt);
            }
            if (stopping.get()) return result(Outcome.STOPPING, decision, expiry, false);
            AcmeIssuanceService.IssuedMaterial material = issuer.issue(config.environment(), config.contactEmail());
            if (stopping.get()) throw new InterruptedException("Server is stopping");
            CertificateActivationService.ActivationResult activated = activator.activate(material);
            return result(Outcome.RENEWED, decision, expiry, activated.cleanupPending());
        } finally {
            inFlight.set(false);
        }
    }

    private static Result result(Outcome outcome, RenewalPolicy.Decision decision,
                                 Optional<Instant> expiry, boolean cleanupPending) {
        return new Result(outcome, decision, expiry, cleanupPending, Optional.empty());
    }

    enum Outcome { NOT_DUE, STAGING_BLOCKED, RESTART_DISABLED, COOLDOWN, RENEWED, ALREADY_RUNNING, STOPPING }

    record Result(Outcome outcome, RenewalPolicy.Decision decision,
                  Optional<Instant> previousExpiry, boolean cleanupPending, Optional<Instant> nextAttempt) { }

    @FunctionalInterface interface Recovery {
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
}
