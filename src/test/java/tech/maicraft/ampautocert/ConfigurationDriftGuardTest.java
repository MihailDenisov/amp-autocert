package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigurationDriftGuardTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    @TempDir Path root;

    @Test
    void missingBaselineFailsClosedWithoutCreatingState() throws Exception {
        EnrollmentStateStore store = new EnrollmentStateStore(root);
        ConfigurationDriftGuard.Result result = guard(store, source(identity("packs.example.com"),
                identity("packs.example.com"))).inspect();

        assertFalse(result.allowed());
        assertEquals(ConfigurationDriftGuard.State.BASELINE_MISSING, result.state());
        assertEquals(List.of("enrollment.baseline"), result.changedFields());
        assertEquals(EnrollmentStateStore.Phase.NONE, store.load().phase());
    }

    @Test
    void relevantDriftPersistsBlockAndCannotAutoClear() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        ConfigurationDriftGuard changed = guard(store, source(identity("cdn.example.com"),
                identity("cdn.example.com")));

        ConfigurationDriftGuard.Result first = changed.inspect();
        assertFalse(first.allowed());
        assertTrue(first.changedFields().contains("automodpack.endpointHost"));
        assertEquals(EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT, store.load().phase());

        ConfigurationDriftGuard reverted = guard(store, source(identity("packs.example.com"),
                identity("packs.example.com")));
        assertFalse(reverted.inspect().allowed(), "explicit reconciliation is required after a drift block");
    }

    @Test
    void stableAcceptedConfigurationPassesAndUnreadableConfigBlocks() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        assertTrue(guard(store, source(identity("packs.example.com"), identity("packs.example.com")))
                .inspect().allowed());

        AutoModpackConfigSource unreadable = new AutoModpackConfigSource() {
            @Override public AutoModpackIdentitySnapshot effective(AutoCertConfig ignored) throws IOException {
                throw new IOException("fixture unavailable");
            }
            @Override public AutoModpackIdentitySnapshot onDisk(AutoCertConfig ignored) throws IOException {
                throw new IOException("fixture unavailable");
            }
        };
        ConfigurationDriftGuard.Result blocked = guard(store, unreadable).inspect();
        assertEquals(ConfigurationDriftGuard.State.UNREADABLE, blocked.state());
        assertEquals(List.of("automodpack.configuration"), blocked.changedFields());
    }

    @Test
    void pendingOrFailedEnrollmentPhaseBlocksProductionGate() throws Exception {
        EnrollmentStateStore store = new EnrollmentStateStore(root);
        ConfigurationBaseline baseline = ConfigurationBaseline.from(config(), identity("packs.example.com"));
        store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.MIGRATED_PENDING_STAGING,
                baseline, "pending staging"));

        ConfigurationDriftGuard.Result pending = guard(store, source(identity("packs.example.com"),
                identity("packs.example.com"))).inspect();

        assertEquals(ConfigurationDriftGuard.State.ENROLLMENT_BLOCKED, pending.state());
        assertEquals(List.of("enrollment.stagingValidation"), pending.changedFields());
    }

    @Test
    void failedBlockPersistenceLatchesClosedAndReportsStateField() throws Exception {
        EnrollmentStateStore delegate = acceptedStore(identity("packs.example.com"));
        ConfigurationDriftGuard.StateAccess failingStore = new ConfigurationDriftGuard.StateAccess() {
            @Override public EnrollmentStateStore.State load() throws IOException { return delegate.load(); }
            @Override public void save(EnrollmentStateStore.State state) throws IOException {
                throw new IOException("fixture persistence failure");
            }
        };
        AtomicReference<AutoModpackIdentitySnapshot> current =
                new AtomicReference<>(identity("cdn.example.com"));
        AutoModpackConfigSource mutableSource = new AutoModpackConfigSource() {
            @Override public AutoModpackIdentitySnapshot effective(AutoCertConfig ignored) {
                return current.get();
            }
            @Override public AutoModpackIdentitySnapshot onDisk(AutoCertConfig ignored) {
                return current.get();
            }
        };
        ConfigurationDriftGuard guard = new ConfigurationDriftGuard(config(), mutableSource, failingStore);

        ConfigurationDriftGuard.Result first = guard.inspect();
        current.set(identity("packs.example.com"));
        ConfigurationDriftGuard.Result reverted = guard.inspect();

        assertEquals(ConfigurationDriftGuard.State.UNREADABLE, first.state());
        assertTrue(first.changedFields().contains("automodpack.endpointHost"));
        assertTrue(first.changedFields().contains("enrollment.state"));
        assertFalse(reverted.allowed(), "a failed durable block must remain latched for this process");
        assertEquals(first, reverted);
    }

    @Test
    void successfulReconciliationAtomicallyActivatesCandidateAndClearsBlock() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        AutoModpackIdentitySnapshot changedIdentity = identity("cdn.example.com");
        ConfigurationDriftGuard guard = guard(store, source(changedIdentity, changedIdentity));
        assertFalse(guard.inspect().allowed());

        ConfigurationBaseline candidate = ConfigurationBaseline.from(config(), changedIdentity);
        guard.acceptAfterReconciliation(candidate);

        assertTrue(guard.inspect().allowed());
        assertEquals(EnrollmentStateStore.Phase.ACTIVE, store.load().phase());
        assertEquals(candidate, store.load().baseline().orElseThrow());
    }

    @Test
    void reconciliationAcceptanceRejectsLoadedAndDiskRaceWithoutReplacingBaseline() throws Exception {
        EnrollmentStateStore store = acceptedStore(identity("packs.example.com"));
        ConfigurationBaseline previous = store.load().baseline().orElseThrow();
        AutoModpackIdentitySnapshot candidateIdentity = identity("cdn.example.com");
        ConfigurationDriftGuard guard = guard(store,
                source(candidateIdentity, identity("other.example.com")));

        ConfigurationDriftGuard.ReconciliationConfigurationChangedException failure = assertThrows(
                ConfigurationDriftGuard.ReconciliationConfigurationChangedException.class,
                () -> guard.acceptAfterReconciliation(ConfigurationBaseline.from(config(), candidateIdentity)));

        assertTrue(failure.changedFields().contains("automodpack.endpointHost"));
        assertEquals(previous, store.load().baseline().orElseThrow());
    }

    private EnrollmentStateStore acceptedStore(AutoModpackIdentitySnapshot identity) throws Exception {
        EnrollmentStateStore store = new EnrollmentStateStore(root);
        store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.ACTIVE,
                ConfigurationBaseline.from(config(), identity), "accepted"));
        return store;
    }

    private ConfigurationDriftGuard guard(EnrollmentStateStore store, AutoModpackConfigSource source) {
        return new ConfigurationDriftGuard(config(), source, store);
    }

    private static AutoModpackConfigSource source(AutoModpackIdentitySnapshot effective,
                                                   AutoModpackIdentitySnapshot onDisk) {
        return new AutoModpackConfigSource() {
            @Override public AutoModpackIdentitySnapshot effective(AutoCertConfig ignored) { return effective; }
            @Override public AutoModpackIdentitySnapshot onDisk(AutoCertConfig ignored) { return onDisk; }
        };
    }

    private static AutoModpackIdentitySnapshot identity(String endpoint) {
        return new AutoModpackIdentitySnapshot("5.0.0-rc.2", true,
                AutoModpackIdentitySnapshot.ConnectionMode.MAGIC, 25570, endpoint, 443, false);
    }

    private static AutoCertConfig config() {
        return new AutoCertConfig(true, false, false, "ops@example.com", "example.com", ID,
                "play.example.com", 60, 30, 120, 15, 30, 24, 60, 48, 15, 12, 20,
                true, 60, 1, false, false, Optional.empty(), Optional.empty(), List.of());
    }
}
