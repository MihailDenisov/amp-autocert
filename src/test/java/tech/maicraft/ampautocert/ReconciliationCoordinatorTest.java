package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ReconciliationCoordinatorTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path root;

    @Test
    void successfulReconciliationRequiresStagingThenActivatesCandidate() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource source = mutableSource(current);
        ConfigurationBaseline candidate = ConfigurationBaseline.from(config(), current.get());
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), source, store);
        assertFalse(guard.inspect().allowed());
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger validated = new AtomicInteger();

        ReconciliationCoordinator.Result result = coordinator(pass(candidate), source, guard,
                (environment, email) -> {
                    assertEquals(AcmeEnvironment.STAGING, environment);
                    issued.incrementAndGet();
                    return material();
                }, (material, config) -> {
                    validated.incrementAndGet();
                    return validation();
                }, (now, cooldown) -> Optional.empty()).run();

        assertEquals(ReconciliationCoordinator.Outcome.SUCCESS, result.outcome());
        assertEquals(1, issued.get());
        assertEquals(1, validated.get());
        assertEquals(EnrollmentStateStore.Phase.ACTIVE, store.load().phase());
        assertEquals(candidate, store.load().baseline().orElseThrow());
        assertTrue(guard.inspect().allowed());
    }

    @Test
    void failedPreflightPreservesOldBaselineAndNeverReservesOrIssues() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        ConfigurationBaseline old = store.load().baseline().orElseThrow();
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource source = mutableSource(current);
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), source, store);
        AtomicInteger calls = new AtomicInteger();
        PreflightReport failed = new PreflightReport(List.of(new PreflightReport.Check(
                PreflightReport.Level.FAIL, "Cloudflare zone", "configured zone access could not be verified")));

        ReconciliationCoordinator.Result result = coordinator(
                new PreflightService.ReconciliationPreflight(failed,
                        Optional.of(ConfigurationBaseline.from(config(), current.get()))),
                source, guard, (environment, email) -> { calls.incrementAndGet(); return material(); },
                (material, config) -> { calls.incrementAndGet(); return validation(); },
                (now, cooldown) -> { calls.incrementAndGet(); return Optional.empty(); }).run();

        assertEquals(ReconciliationCoordinator.Outcome.PREFLIGHT_FAILED, result.outcome());
        assertEquals(0, calls.get());
        assertEquals(EnrollmentStateStore.Phase.BLOCKED_PREFLIGHT, store.load().phase());
        assertEquals(old, store.load().baseline().orElseThrow());
    }

    @Test
    void configurationRaceAfterStagingNeverAcceptsCandidate() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        ConfigurationBaseline old = store.load().baseline().orElseThrow();
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource source = mutableSource(current);
        ConfigurationBaseline candidate = ConfigurationBaseline.from(config(), current.get());
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), source, store);

        ReconciliationCoordinator.Result result = coordinator(pass(candidate), source, guard,
                (environment, email) -> material(), (material, config) -> {
                    current.set(identity("other.example.com"));
                    return validation();
                }, (now, cooldown) -> Optional.empty()).run();

        assertEquals(ReconciliationCoordinator.Outcome.CONFIGURATION_CHANGED, result.outcome());
        assertTrue(result.blockedFields().contains("automodpack.endpointHost"));
        assertEquals(EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT, store.load().phase());
        assertEquals(old, store.load().baseline().orElseThrow());
    }

    @Test
    void stagingCooldownAndFailureCannotAcceptBaseline() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource source = mutableSource(current);
        ConfigurationBaseline candidate = ConfigurationBaseline.from(config(), current.get());
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), source, store);
        Instant next = NOW.plusSeconds(3600);

        ReconciliationCoordinator.Result cooldown = coordinator(pass(candidate), source, guard,
                (environment, email) -> fail("issuance must not run"),
                (material, config) -> fail("validation must not run"),
                (now, duration) -> Optional.of(next)).run();
        assertEquals(ReconciliationCoordinator.Outcome.COOLDOWN, cooldown.outcome());
        assertEquals(Optional.of(next), cooldown.nextAttempt());

        ReconciliationCoordinator.Result failed = coordinator(pass(candidate), source, guard,
                (environment, email) -> { throw new IOException("fixture staging failure"); },
                (material, config) -> fail("validation must not run"),
                (now, duration) -> Optional.empty()).run();
        assertEquals(ReconciliationCoordinator.Outcome.STAGING_FAILED, failed.outcome());
        assertEquals(EnrollmentStateStore.Phase.STAGING_FAILED, store.load().phase());
        assertFalse(guard.inspect().allowed());
    }

    @Test
    void failedAcceptedStateWriteRemainsLatchedClosed() throws Exception {
        EnrollmentStateStore delegate = acceptedStore(identity("packs.example.com"));
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource source = mutableSource(current);
        ConfigurationBaseline candidate = ConfigurationBaseline.from(config(), current.get());
        ConfigurationDriftGuard.StateAccess failing = new ConfigurationDriftGuard.StateAccess() {
            @Override public EnrollmentStateStore.State load() throws IOException { return delegate.load(); }
            @Override public void save(EnrollmentStateStore.State state) throws IOException {
                throw new IOException("fixture state failure");
            }
        };
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), source, failing);

        ReconciliationCoordinator.Result result = coordinator(pass(candidate), source, guard,
                (environment, email) -> material(), (material, config) -> validation(),
                (now, cooldown) -> Optional.empty()).run();

        assertEquals(ReconciliationCoordinator.Outcome.STATE_FAILED, result.outcome());
        assertFalse(guard.inspect().allowed());
        assertTrue(guard.inspect().changedFields().contains("enrollment.state"));
    }

    @Test
    void shutdownAtSerializedAcceptanceBoundaryCannotCommitActiveState() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        ConfigurationBaseline old = store.load().baseline().orElseThrow();
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource source = mutableSource(current);
        ConfigurationBaseline candidate = ConfigurationBaseline.from(config(), current.get());
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), source, store);
        AtomicBoolean stopping = new AtomicBoolean();
        ReconciliationCoordinator coordinator = new ReconciliationCoordinator(config(), () -> pass(candidate),
                source, (environment, email) -> material(), (material, config) -> validation(),
                (now, cooldown) -> Optional.empty(), guard, accepted -> {
                    stopping.set(true);
                    throw new InterruptedException("fixture shutdown");
                }, CLOCK, stopping);

        assertThrows(InterruptedException.class, coordinator::run);
        assertEquals(old, store.load().baseline().orElseThrow());
        assertEquals(EnrollmentStateStore.Phase.ACTIVE, store.load().phase());
    }

    private ReconciliationCoordinator coordinator(PreflightService.ReconciliationPreflight preflight,
                                                   AutoModpackConfigSource source,
                                                   ConfigurationDriftGuard guard,
                                                   ReconciliationCoordinator.Issuer issuer,
                                                   ReconciliationCoordinator.Validator validator,
                                                   ReconciliationCoordinator.AttemptGate attemptGate) {
        return new ReconciliationCoordinator(config(), () -> preflight, source, issuer, validator,
                attemptGate, guard, CLOCK, new AtomicBoolean());
    }

    private EnrollmentStateStore acceptedStore(AutoModpackIdentitySnapshot identity) throws Exception {
        EnrollmentStateStore store = new EnrollmentStateStore(root);
        store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.ACTIVE,
                ConfigurationBaseline.from(config(), identity), "accepted"));
        return store;
    }

    private static PreflightService.ReconciliationPreflight pass(ConfigurationBaseline candidate) {
        PreflightReport report = new PreflightReport(List.of(new PreflightReport.Check(
                PreflightReport.Level.PASS, "preflight", "all required checks passed")));
        return new PreflightService.ReconciliationPreflight(report, Optional.of(candidate));
    }

    private AutoCertConfig config() {
        return new AutoCertConfig(true, false, false, "ops@example.com", "example.com", ID,
                "play.example.com", 60, 30, 120, 15, 30, 24, 60, 48, 15, 12, 20,
                true, 60, 1, false, false, Optional.empty(), Optional.empty(), List.of());
    }

    private static AutoModpackConfigSource mutableSource(
            AtomicReference<AutoModpackIdentitySnapshot> current) {
        return new AutoModpackConfigSource() {
            @Override public AutoModpackIdentitySnapshot effective(AutoCertConfig ignored) {
                return current.get();
            }
            @Override public AutoModpackIdentitySnapshot onDisk(AutoCertConfig ignored) {
                return current.get();
            }
        };
    }

    private static AutoModpackIdentitySnapshot identity(String endpoint) {
        return new AutoModpackIdentitySnapshot("5.0.0-rc.2", true,
                AutoModpackIdentitySnapshot.ConnectionMode.MAGIC, 25570, endpoint, 443, false);
    }

    private static AcmeIssuanceService.IssuedMaterial material() {
        return new AcmeIssuanceService.IssuedMaterial(AcmeEnvironment.STAGING,
                "redacted fixture", new byte[] {1}, List.of("example.com", "*.example.com"));
    }

    private static IssuedCertificateValidator.ValidationResult validation() {
        return new IssuedCertificateValidator.ValidationResult(NOW, NOW.plusSeconds(86400),
                List.of("example.com", "*.example.com"));
    }
}
