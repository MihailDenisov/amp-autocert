package tech.maicraft.ampautocert;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit operator reconciliation. Staging material is validated and discarded, never deployed. */
final class ReconciliationCoordinator {
    private static final List<String> PREFLIGHT_FIELD = List.of("enrollment.preflight");
    private static final List<String> STAGING_FIELD = List.of("enrollment.stagingValidation");
    private static final List<String> STATE_FIELD = List.of("enrollment.state");

    private final AutoCertConfig config;
    private final PreflightRunner preflight;
    private final AutoModpackConfigSource autoModpack;
    private final Issuer issuer;
    private final Validator validator;
    private final AttemptGate attemptGate;
    private final ConfigurationDriftGuard driftGuard;
    private final Acceptance acceptance;
    private final Clock clock;
    private final AtomicBoolean stopping;

    ReconciliationCoordinator(AutoCertConfig config, PreflightRunner preflight,
                              AutoModpackConfigSource autoModpack, Issuer issuer, Validator validator,
                              AttemptGate attemptGate, ConfigurationDriftGuard driftGuard,
                              Clock clock, AtomicBoolean stopping) {
        this(config, preflight, autoModpack, issuer, validator, attemptGate, driftGuard,
                driftGuard::acceptAfterReconciliation, clock, stopping);
    }

    ReconciliationCoordinator(AutoCertConfig config, PreflightRunner preflight,
                              AutoModpackConfigSource autoModpack, Issuer issuer, Validator validator,
                              AttemptGate attemptGate, ConfigurationDriftGuard driftGuard,
                              Acceptance acceptance, Clock clock, AtomicBoolean stopping) {
        this.config = config;
        this.preflight = preflight;
        this.autoModpack = autoModpack;
        this.issuer = issuer;
        this.validator = validator;
        this.attemptGate = attemptGate;
        this.driftGuard = driftGuard;
        this.acceptance = acceptance;
        this.clock = clock;
        this.stopping = stopping;
    }

    Result run() throws InterruptedException {
        if (stopping.get()) return Result.of(Outcome.STOPPING);

        PreflightService.ReconciliationPreflight inspected = preflight.run();
        if (stopping.get()) return Result.of(Outcome.STOPPING);
        if (!inspected.report().passed() || inspected.candidate().isEmpty()) {
            driftGuard.recordReconciliationFailure(EnrollmentStateStore.Phase.BLOCKED_PREFLIGHT,
                    PREFLIGHT_FIELD);
            return new Result(Outcome.PREFLIGHT_FAILED, Optional.of(inspected.report()), Optional.empty(),
                    PREFLIGHT_FIELD);
        }

        ConfigurationBaseline candidate = inspected.candidate().orElseThrow();
        List<String> changed = changedFields(candidate);
        if (!changed.isEmpty()) return configurationChanged(inspected.report(), changed);

        Optional<Instant> nextAttempt;
        try {
            nextAttempt = attemptGate.reserve(clock.instant(), config.failureCooldown());
        } catch (IOException | RuntimeException failure) {
            driftGuard.recordReconciliationFailure(EnrollmentStateStore.Phase.STAGING_FAILED, STAGING_FIELD);
            return new Result(Outcome.STAGING_FAILED, Optional.of(inspected.report()), Optional.empty(),
                    STAGING_FIELD);
        }
        if (nextAttempt.isPresent()) {
            return new Result(Outcome.COOLDOWN, Optional.of(inspected.report()), nextAttempt, STAGING_FIELD);
        }
        if (stopping.get()) return Result.of(Outcome.STOPPING);

        changed = changedFields(candidate);
        if (!changed.isEmpty()) return configurationChanged(inspected.report(), changed);

        try {
            AcmeIssuanceService.IssuedMaterial material = issuer.issue(
                    AcmeEnvironment.STAGING, config.contactEmail());
            if (stopping.get()) return Result.of(Outcome.STOPPING);
            validator.validate(material, config);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            driftGuard.recordReconciliationFailure(EnrollmentStateStore.Phase.STAGING_FAILED, STAGING_FIELD);
            return new Result(Outcome.STAGING_FAILED, Optional.of(inspected.report()), Optional.empty(),
                    STAGING_FIELD);
        }
        if (stopping.get()) return Result.of(Outcome.STOPPING);

        changed = changedFields(candidate);
        if (!changed.isEmpty()) return configurationChanged(inspected.report(), changed);
        try {
            acceptance.accept(candidate);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (ConfigurationDriftGuard.ReconciliationConfigurationChangedException changedDuringAcceptance) {
            return configurationChanged(inspected.report(), changedDuringAcceptance.changedFields());
        } catch (IOException | RuntimeException persistenceFailure) {
            driftGuard.recordReconciliationFailure(EnrollmentStateStore.Phase.BLOCKED_PREFLIGHT, STATE_FIELD);
            return new Result(Outcome.STATE_FAILED, Optional.of(inspected.report()), Optional.empty(), STATE_FIELD);
        }
        return new Result(Outcome.SUCCESS, Optional.of(inspected.report()), Optional.empty(), List.of());
    }

    private Result configurationChanged(PreflightReport report, List<String> changed) {
        List<String> safe = ConfigurationDriftGuard.safeFields(changed);
        driftGuard.recordReconciliationFailure(EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT, safe);
        return new Result(Outcome.CONFIGURATION_CHANGED, Optional.of(report), Optional.empty(), safe);
    }

    private List<String> changedFields(ConfigurationBaseline candidate) {
        try {
            ConfigurationBaseline effective = ConfigurationBaseline.from(config, autoModpack.effective(config));
            ConfigurationBaseline onDisk = ConfigurationBaseline.from(config, autoModpack.onDisk(config));
            ArrayList<String> changed = new ArrayList<>(effective.differences(onDisk));
            changed.addAll(candidate.differences(effective));
            return ConfigurationDriftGuard.safeFields(changed);
        } catch (IOException | RuntimeException unavailable) {
            return List.of("automodpack.configuration");
        }
    }

    enum Outcome { SUCCESS, PREFLIGHT_FAILED, COOLDOWN, CONFIGURATION_CHANGED,
        STAGING_FAILED, STATE_FAILED, STOPPING }

    record Result(Outcome outcome, Optional<PreflightReport> preflight, Optional<Instant> nextAttempt,
                  List<String> blockedFields) {
        Result {
            preflight = preflight == null ? Optional.empty() : preflight;
            nextAttempt = nextAttempt == null ? Optional.empty() : nextAttempt;
            blockedFields = ConfigurationDriftGuard.safeFields(blockedFields);
        }

        static Result of(Outcome outcome) {
            return new Result(outcome, Optional.empty(), Optional.empty(), List.of());
        }

        List<String> format() {
            ArrayList<String> lines = new ArrayList<>();
            lines.add("amp-autocert reconcile: " + switch (outcome) {
                case SUCCESS -> "SUCCESS";
                case COOLDOWN -> "COOLDOWN";
                case STOPPING -> "STOPPED";
                default -> "BLOCKED";
            });
            switch (outcome) {
                case SUCCESS -> lines.add("Staging validation passed; the new configuration baseline is active. Production remains subject to normal gates.");
                case PREFLIGHT_FAILED -> lines.add("Full preflight failed; the previous baseline and deployed credentials were preserved.");
                case COOLDOWN -> lines.add("Staging issuance cooldown is active until "
                        + nextAttempt.map(Object::toString).orElse("unknown") + ".");
                case CONFIGURATION_CHANGED -> lines.add("Configuration changed during reconciliation: "
                        + String.join(", ", blockedFields) + ".");
                case STAGING_FAILED -> lines.add("Staging issuance or validation failed; no certificate was deployed.");
                case STATE_FAILED -> lines.add("The accepted baseline could not be persisted and verified; automation remains blocked.");
                case STOPPING -> lines.add("The server is stopping; reconciliation did not accept a new baseline.");
            }
            if (outcome == Outcome.PREFLIGHT_FAILED) {
                preflight.ifPresent(value -> lines.addAll(value.format()));
            }
            return List.copyOf(lines);
        }
    }

    @FunctionalInterface interface PreflightRunner {
        PreflightService.ReconciliationPreflight run() throws InterruptedException;
    }

    @FunctionalInterface interface Issuer {
        AcmeIssuanceService.IssuedMaterial issue(AcmeEnvironment environment, String contactEmail)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface interface Validator {
        IssuedCertificateValidator.ValidationResult validate(AcmeIssuanceService.IssuedMaterial material,
                                                             AutoCertConfig config)
                throws IOException, GeneralSecurityException;
    }

    @FunctionalInterface interface AttemptGate {
        Optional<Instant> reserve(Instant now, java.time.Duration cooldown)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface interface Acceptance {
        void accept(ConfigurationBaseline candidate) throws IOException, InterruptedException;
    }
}
