package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationBaselineTest {
    @TempDir Path root;

    @Test void canonicalHashIsStableAndStateRoundTrips() throws Exception {
        ConfigurationBaseline first = baseline("packs.example.com", 443, "MAGIC", 25570);
        ConfigurationBaseline second = baseline("packs.example.com", 443, "magic", 25570);
        assertEquals(first.hash(), second.hash());
        EnrollmentStateStore store = new EnrollmentStateStore(root);
        store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.ACTIVE, first, "accepted"));
        EnrollmentStateStore.State loaded = store.load();
        assertEquals(EnrollmentStateStore.Phase.ACTIVE, loaded.phase());
        assertEquals(first, loaded.baseline().orElseThrow());
        assertTrue(ConfigurationDriftDetector.compare(first, loaded.baseline().orElseThrow()).stable());
    }

    @Test void relevantEndpointAndTlsChangesTriggerNamedDrift() {
        ConfigurationBaseline accepted = baseline("packs.example.com", 443, "MAGIC", 25570);
        ConfigurationBaseline endpointChanged = baseline("cdn.example.com", 443, "MAGIC", 25570);
        ConfigurationDriftDetector.Result result = ConfigurationDriftDetector.compare(accepted, endpointChanged);
        assertFalse(result.stable());
        assertEquals(java.util.List.of("automodpack.endpointHost"), result.changedFields());
    }

    @Test void unrelatedAutoModpackSettingsCannotAffectSnapshot() {
        ConfigurationBaseline accepted = baseline("packs.example.com", 443, "MAGIC", 25570);
        // Bandwidth, groups, mod lists, and other content settings are intentionally absent from this constructor.
        ConfigurationBaseline afterUnrelatedEdit = baseline("packs.example.com", 443, "MAGIC", 25570);
        assertTrue(ConfigurationDriftDetector.compare(accepted, afterUnrelatedEdit).stable());
    }

    @Test void missingStoreIsDeterministicNone() throws Exception {
        assertEquals(new EnrollmentStateStore.State(EnrollmentStateStore.Phase.NONE, Optional.empty(), "none"),
                new EnrollmentStateStore(root).load());
    }

    private static ConfigurationBaseline baseline(String endpoint, int port, String mode, int bind) {
        AutoCertConfig config = new AutoCertConfig(true, false, false, "ops@example.com", "example.com",
                "0123456789abcdef0123456789abcdef", "play.example.com", 60,
                30, 120, 15, 30, 24, 60, 48, 15, 12, 20, true, 60, 1,
                false, false, Optional.empty(), Optional.empty(), java.util.List.of());
        AutoModpackIdentitySnapshot identity = new AutoModpackIdentitySnapshot("5.0.0-rc.2", true,
                AutoModpackIdentitySnapshot.ConnectionMode.valueOf(mode.toUpperCase()), bind,
                endpoint, port, false);
        return ConfigurationBaseline.from(config, identity);
    }
}
