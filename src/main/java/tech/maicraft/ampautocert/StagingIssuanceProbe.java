package tech.maicraft.ampautocert;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, background-only staging issuance that never writes or activates certificate material. */
final class StagingIssuanceProbe {
    private final AutoCertConfig config;
    private final Issuer issuer;
    private final Validator validator;
    private final Clock clock;
    private final AttemptGate attemptGate;
    private final AtomicBoolean stopping;
    private final DriftGate driftGate;

    StagingIssuanceProbe(AutoCertConfig config, Issuer issuer, Validator validator, Clock clock,
                         AttemptGate attemptGate, AtomicBoolean stopping) {
        this(config, issuer, validator, clock, attemptGate, ConfigurationDriftGuard.Result::ready, stopping);
    }

    StagingIssuanceProbe(AutoCertConfig config, Issuer issuer, Validator validator, Clock clock,
                         AttemptGate attemptGate, DriftGate driftGate, AtomicBoolean stopping) {
        this.config = config;
        this.issuer = issuer;
        this.validator = validator;
        this.clock = clock;
        this.attemptGate = attemptGate;
        this.stopping = stopping;
        this.driftGate = driftGate;
    }

    Result run() throws IOException, InterruptedException, GeneralSecurityException {
        if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty());
        ConfigurationDriftGuard.Result drift = driftGate.inspect();
        if (!drift.allowed()) return new Result(Outcome.CONFIGURATION_DRIFT, null, Optional.empty(),
                drift.changedFields());
        Optional<java.time.Instant> nextAttempt = attemptGate.reserve(clock.instant(), config.failureCooldown());
        if (nextAttempt.isPresent()) return result(Outcome.COOLDOWN, null, nextAttempt);
        if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty());
        drift = driftGate.inspect();
        if (!drift.allowed()) return new Result(Outcome.CONFIGURATION_DRIFT, null, Optional.empty(),
                drift.changedFields());
        AcmeIssuanceService.IssuedMaterial material = issuer.issue(AcmeEnvironment.STAGING, config.contactEmail());
        if (stopping.get()) return result(Outcome.STOPPING, null, Optional.empty());
        IssuedCertificateValidator.ValidationResult validation = validator.validate(material, config);
        return result(Outcome.INSPECTED_NOT_DEPLOYED, validation, Optional.empty());
    }

    private static Result result(Outcome outcome, IssuedCertificateValidator.ValidationResult validation,
                                 Optional<java.time.Instant> nextAttempt) {
        return new Result(outcome, validation, nextAttempt, List.of());
    }

    enum Outcome { INSPECTED_NOT_DEPLOYED, CONFIGURATION_DRIFT, COOLDOWN, STOPPING }

    record Result(Outcome outcome, IssuedCertificateValidator.ValidationResult validation,
                  Optional<java.time.Instant> nextAttempt, List<String> driftFields) {
        Result { driftFields = List.copyOf(driftFields); }
    }

    @FunctionalInterface
    interface Issuer {
        AcmeIssuanceService.IssuedMaterial issue(AcmeEnvironment environment, String contactEmail)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Validator {
        IssuedCertificateValidator.ValidationResult validate(AcmeIssuanceService.IssuedMaterial material,
                                                             AutoCertConfig config)
                throws IOException, GeneralSecurityException;
    }

    @FunctionalInterface
    interface AttemptGate {
        Optional<java.time.Instant> reserve(java.time.Instant now, java.time.Duration cooldown)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface DriftGate {
        ConfigurationDriftGuard.Result inspect();
    }
}
