package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RenewalCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path root;

    @Test void validCertificateDoesNotIssue() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RenewalCoordinator coordinator = new RenewalCoordinator(productionConfig(), CLOCK, calls::incrementAndGet,
                () -> Optional.of(NOW.plusSeconds(31L * 86400)),
                (environment, email) -> { calls.addAndGet(100); return material(); }, () -> { },
                (now, cooldown) -> Optional.empty(), ignored -> activated(),
                new AtomicBoolean());

        RenewalCoordinator.Result result = coordinator.checkAndRenew();

        assertEquals(RenewalCoordinator.Outcome.NOT_DUE, result.outcome());
        assertEquals(RenewalPolicy.Decision.VALID, result.decision());
        assertEquals(1, calls.get());
    }

    @Test void operatorRenewalOverridesValidityButRetainsProductionGates() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        RenewalCoordinator coordinator = coordinator(productionConfig(), Optional.of(NOW.plusSeconds(90L * 86400)),
                (environment, email) -> { issued.incrementAndGet(); return material(); }, ignored -> activated());

        RenewalCoordinator.Result result = coordinator.checkAndRenew(true);

        assertEquals(RenewalCoordinator.Outcome.RENEWED, result.outcome());
        assertEquals(RenewalPolicy.Decision.DUE, result.decision());
        assertEquals(1, issued.get());
    }

    @Test void operatorRenewalCannotBypassStagingOrCooldown() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        RenewalCoordinator staging = coordinator(AutoCertConfig.load(root), Optional.of(NOW.plusSeconds(90L * 86400)),
                (environment, email) -> { issued.incrementAndGet(); return material(); }, ignored -> activated());
        assertEquals(RenewalCoordinator.Outcome.STAGING_BLOCKED, staging.checkAndRenew(true).outcome());

        RenewalCoordinator cooldown = new RenewalCoordinator(productionConfig(), CLOCK, () -> { },
                () -> Optional.of(NOW.plusSeconds(90L * 86400)),
                (environment, email) -> { issued.incrementAndGet(); return material(); }, () -> { },
                (now, duration) -> Optional.of(NOW.plus(duration)), ignored -> activated(), new AtomicBoolean());
        assertEquals(RenewalCoordinator.Outcome.COOLDOWN, cooldown.checkAndRenew(true).outcome());
        assertEquals(0, issued.get());
    }

    @Test void recoveryFailureStopsDecisionAndReleasesSingleFlight() throws Exception {
        AtomicInteger recoveries = new AtomicInteger();
        AtomicInteger inspections = new AtomicInteger();
        RenewalCoordinator coordinator = new RenewalCoordinator(productionConfig(), CLOCK, () -> {
            if (recoveries.incrementAndGet() == 1) throw new IOException("fixture recovery failure");
        }, () -> { inspections.incrementAndGet(); return Optional.of(NOW.plusSeconds(31L * 86400)); },
                (environment, email) -> material(), () -> { }, (now, cooldown) -> Optional.empty(),
                ignored -> activated(), new AtomicBoolean());

        assertThrows(IOException.class, coordinator::checkAndRenew);
        assertEquals(RenewalCoordinator.Outcome.NOT_DUE, coordinator.checkAndRenew().outcome());
        assertEquals(2, recoveries.get());
        assertEquals(1, inspections.get());
    }

    @Test void stagingNeverIssuesOrDeploysDueCertificate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RenewalCoordinator coordinator = coordinator(AutoCertConfig.load(root), Optional.empty(),
                (environment, email) -> { calls.incrementAndGet(); return material(); },
                ignored -> { calls.incrementAndGet(); return activated(); });

        RenewalCoordinator.Result result = coordinator.checkAndRenew();

        assertEquals(RenewalCoordinator.Outcome.STAGING_BLOCKED, result.outcome());
        assertEquals(RenewalPolicy.Decision.MISSING, result.decision());
        assertEquals(0, calls.get());
    }

    @Test void productionDueCertificateIssuesAndActivatesExactlyOnce() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger activated = new AtomicInteger();
        RenewalCoordinator coordinator = coordinator(productionConfig(), Optional.of(NOW.plusSeconds(86400)),
                (environment, email) -> {
                    assertEquals(AcmeEnvironment.PRODUCTION, environment);
                    assertEquals("admin@mydomain.com", email);
                    issued.incrementAndGet();
                    return material();
                }, ignored -> { activated.incrementAndGet(); return activated(); });

        RenewalCoordinator.Result result = coordinator.checkAndRenew();

        assertEquals(RenewalCoordinator.Outcome.RENEWED, result.outcome());
        assertEquals(RenewalPolicy.Decision.DUE, result.decision());
        assertEquals(1, issued.get());
        assertEquals(1, activated.get());
    }

    @Test void failedActivationPreflightDoesNotCreateAcmeOrder() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        RenewalCoordinator coordinator = new RenewalCoordinator(productionConfig(), CLOCK, () -> { },
                Optional::empty,
                (environment, email) -> { issued.incrementAndGet(); return material(); },
                () -> { throw new IOException("AutoModpack is absent"); },
                (now, cooldown) -> Optional.empty(), ignored -> activated(),
                new AtomicBoolean());

        IOException failure = assertThrows(IOException.class, coordinator::checkAndRenew);
        assertTrue(failure.getMessage().contains("absent"));
        assertEquals(0, issued.get());
    }

    @Test void persistentCooldownPreventsAnotherProductionOrder() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        Instant nextAttempt = NOW.plusSeconds(48L * 3600);
        RenewalCoordinator coordinator = new RenewalCoordinator(productionConfig(), CLOCK, () -> { },
                Optional::empty,
                (environment, email) -> { issued.incrementAndGet(); return material(); }, () -> { },
                (now, cooldown) -> Optional.of(nextAttempt), ignored -> activated(), new AtomicBoolean());

        RenewalCoordinator.Result result = coordinator.checkAndRenew();

        assertEquals(RenewalCoordinator.Outcome.COOLDOWN, result.outcome());
        assertEquals(nextAttempt, result.nextAttempt().orElseThrow());
        assertEquals(0, issued.get());
    }

    @Test void disabledRestartDoesNotCreateAcmeOrder() throws Exception {
        AutoCertConfig config = productionConfig();
        Path file = root.resolve("automodpack/amp-autocert.properties");
        Files.writeString(file, Files.readString(file).replace("host.restartAfterRenew=true",
                "host.restartAfterRenew=false"));
        config = AutoCertConfig.load(root);
        AtomicInteger calls = new AtomicInteger();
        RenewalCoordinator coordinator = new RenewalCoordinator(config, CLOCK, () -> { }, Optional::empty,
                (environment, email) -> { calls.incrementAndGet(); return material(); },
                calls::incrementAndGet, (now, cooldown) -> Optional.empty(),
                ignored -> activated(), new AtomicBoolean());

        assertEquals(RenewalCoordinator.Outcome.RESTART_DISABLED, coordinator.checkAndRenew().outcome());
        assertEquals(0, calls.get());
    }

    @Test void overlappingChecksAreSingleFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RenewalCoordinator coordinator = coordinator(productionConfig(), Optional.empty(),
                (environment, email) -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
                    return material();
                }, ignored -> activated());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> coordinator.checkAndRenew());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            RenewalCoordinator.Result overlapping = coordinator.checkAndRenew();
            assertEquals(RenewalCoordinator.Outcome.ALREADY_RUNNING, overlapping.outcome());
            release.countDown();
            assertEquals(RenewalCoordinator.Outcome.RENEWED, first.get(5, TimeUnit.SECONDS).outcome());
        }
    }

    @Test void failureReleasesSingleFlightAndStoppingPreventsIssuance() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean stopping = new AtomicBoolean();
        AutoCertConfig config = productionConfig();
        RenewalCoordinator coordinator = new RenewalCoordinator(config, CLOCK, () -> { }, Optional::empty,
                (environment, email) -> {
                    if (attempts.incrementAndGet() == 1) throw new IOException("fixture failure");
                    return material();
                }, () -> { }, (now, cooldown) -> Optional.empty(), ignored -> activated(), stopping);

        assertThrows(IOException.class, coordinator::checkAndRenew);
        assertEquals(RenewalCoordinator.Outcome.RENEWED, coordinator.checkAndRenew().outcome());
        stopping.set(true);
        assertEquals(RenewalCoordinator.Outcome.STOPPING, coordinator.checkAndRenew().outcome());
        assertEquals(2, attempts.get());
    }

    @Test void shutdownAfterAttemptReservationStillPreventsAcmeOrder() throws Exception {
        AtomicBoolean stopping = new AtomicBoolean();
        AtomicInteger issued = new AtomicInteger();
        RenewalCoordinator coordinator = new RenewalCoordinator(productionConfig(), CLOCK, () -> { },
                Optional::empty,
                (environment, email) -> { issued.incrementAndGet(); return material(); }, () -> { },
                (now, cooldown) -> { stopping.set(true); return Optional.empty(); },
                ignored -> activated(), stopping);

        assertEquals(RenewalCoordinator.Outcome.STOPPING, coordinator.checkAndRenew().outcome());
        assertEquals(0, issued.get());
    }

    private RenewalCoordinator coordinator(AutoCertConfig config, Optional<Instant> expiry,
                                           RenewalCoordinator.Issuer issuer,
                                           RenewalCoordinator.Activator activator) {
        return new RenewalCoordinator(config, CLOCK, () -> { }, () -> expiry, issuer, () -> { },
                (now, cooldown) -> Optional.empty(), activator,
                new AtomicBoolean());
    }

    private AutoCertConfig productionConfig() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        Files.writeString(file, Files.readString(file).replace("acme.environment=staging",
                "acme.environment=production"));
        return AutoCertConfig.load(root);
    }

    private static AcmeIssuanceService.IssuedMaterial material() {
        return new AcmeIssuanceService.IssuedMaterial(AcmeEnvironment.PRODUCTION,
                "fixture", new byte[]{1}, List.of("mydomain.com", "*.mydomain.com"));
    }

    private static CertificateActivationService.ActivationResult activated() {
        return new CertificateActivationService.ActivationResult(false);
    }
}
