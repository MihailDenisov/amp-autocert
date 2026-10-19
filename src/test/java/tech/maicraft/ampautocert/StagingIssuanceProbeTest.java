package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StagingIssuanceProbeTest {
    @TempDir Path root;

    @Test void issuesOnlyStagingAndReturnsValidationWithoutDeployment() throws Exception {
        AutoCertConfig config = AutoCertConfig.load(root);
        AtomicInteger validations = new AtomicInteger();
        var probe = new StagingIssuanceProbe(config, (environment, email) -> {
            assertEquals(AcmeEnvironment.STAGING, environment);
            assertEquals(config.contactEmail(), email);
            return material(environment);
        }, (issued, ignored) -> {
            validations.incrementAndGet();
            return new IssuedCertificateValidator.ValidationResult(
                    Instant.parse("2026-09-29T00:00:00Z"), Instant.parse("2026-12-28T00:00:00Z"),
                    issued.domains());
        }, Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC),
                (now, cooldown) -> java.util.Optional.empty(), new AtomicBoolean());

        StagingIssuanceProbe.Result result = probe.run();

        assertEquals(StagingIssuanceProbe.Outcome.INSPECTED_NOT_DEPLOYED, result.outcome());
        assertEquals(1, validations.get());
        assertEquals(config.domains(), result.validation().dnsSans());
    }

    @Test void stoppingPreventsIssuance() throws Exception {
        AtomicBoolean stopping = new AtomicBoolean(true);
        AtomicInteger issuances = new AtomicInteger();
        var probe = new StagingIssuanceProbe(AutoCertConfig.load(root), (environment, email) -> {
            issuances.incrementAndGet();
            return material(environment);
        }, (issued, config) -> fail("validation must not run"), Clock.systemUTC(),
                (now, cooldown) -> fail("cooldown gate must not run"), stopping);

        assertEquals(StagingIssuanceProbe.Outcome.STOPPING, probe.run().outcome());
        assertEquals(0, issuances.get());
    }

    @Test void persistedCooldownPreventsIssuance() throws Exception {
        Instant next = Instant.parse("2026-09-30T00:00:00Z");
        AtomicInteger issuances = new AtomicInteger();
        var probe = new StagingIssuanceProbe(AutoCertConfig.load(root), (environment, email) -> {
            issuances.incrementAndGet();
            return material(environment);
        }, (issued, config) -> fail("validation must not run"), Clock.systemUTC(),
                (now, cooldown) -> java.util.Optional.of(next), new AtomicBoolean());

        StagingIssuanceProbe.Result result = probe.run();

        assertEquals(StagingIssuanceProbe.Outcome.COOLDOWN, result.outcome());
        assertEquals(java.util.Optional.of(next), result.nextAttempt());
        assertEquals(0, issuances.get());
    }

    @Test void configurationDriftPreventsStagingAttemptReservationAndIssuance() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger issuances = new AtomicInteger();
        var probe = new StagingIssuanceProbe(AutoCertConfig.load(root), (environment, email) -> {
            issuances.incrementAndGet();
            return material(environment);
        }, (issued, config) -> fail("validation must not run"), Clock.systemUTC(),
                (now, cooldown) -> { attempts.incrementAndGet(); return java.util.Optional.empty(); },
                () -> ConfigurationDriftGuard.Result.blocked(ConfigurationDriftGuard.State.DRIFT,
                        List.of("automodpack.disableInternalTls")), new AtomicBoolean());

        StagingIssuanceProbe.Result result = probe.run();

        assertEquals(StagingIssuanceProbe.Outcome.CONFIGURATION_DRIFT, result.outcome());
        assertEquals(List.of("automodpack.disableInternalTls"), result.driftFields());
        assertEquals(0, attempts.get());
        assertEquals(0, issuances.get());
    }

    @Test void driftAfterAttemptReservationStillPreventsStagingIssuance() throws Exception {
        AtomicInteger inspections = new AtomicInteger();
        AtomicInteger issuances = new AtomicInteger();
        var probe = new StagingIssuanceProbe(AutoCertConfig.load(root), (environment, email) -> {
            issuances.incrementAndGet();
            return material(environment);
        }, (issued, config) -> fail("validation must not run"), Clock.systemUTC(),
                (now, cooldown) -> java.util.Optional.empty(),
                () -> inspections.incrementAndGet() == 1
                        ? ConfigurationDriftGuard.Result.ready()
                        : ConfigurationDriftGuard.Result.blocked(ConfigurationDriftGuard.State.DRIFT,
                        List.of("automodpack.connectionMode")), new AtomicBoolean());

        StagingIssuanceProbe.Result result = probe.run();

        assertEquals(StagingIssuanceProbe.Outcome.CONFIGURATION_DRIFT, result.outcome());
        assertEquals(0, issuances.get());
    }

    private static AcmeIssuanceService.IssuedMaterial material(AcmeEnvironment environment) {
        return new AcmeIssuanceService.IssuedMaterial(environment, "redacted fixture", new byte[] {1},
                List.of("mydomain.com", "*.mydomain.com"));
    }
}
