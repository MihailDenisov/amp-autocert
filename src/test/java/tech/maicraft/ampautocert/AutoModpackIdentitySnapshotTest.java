package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoModpackIdentitySnapshotTest {
    @Test void resolvesDocumentedDefaultSemanticsFromOriginAndConnectedPort() {
        AutoModpackIdentitySnapshot snapshot = AutoModpackIdentitySnapshot.resolve("5.0.0-rc.2", true,
                "holepunch", -1, "", -1, false,
                "play.example.com", "example.com", 25565);
        assertEquals("play.example.com", snapshot.endpointHost());
        assertEquals(25565, snapshot.endpointPort());
        assertEquals(25565, snapshot.bindPort());
        assertEquals(AutoModpackIdentitySnapshot.ConnectionMode.HOLEPUNCH, snapshot.connectionMode());
    }

    @Test void resolvesMagicAndHttpDedicatedListeners() {
        AutoModpackIdentitySnapshot magic = AutoModpackIdentitySnapshot.resolve("5.0.0-rc.1", true,
                "MAGIC", 24443, "packs.example.com", 443, false,
                "play.example.com", "example.com", 25565);
        assertEquals(24443, magic.bindPort());
        assertEquals("packs.example.com:443", magic.endpoint());
        assertThrows(IllegalArgumentException.class, () -> AutoModpackIdentitySnapshot.resolve("5.0.0-rc.2",
                true, "HTTP", -1, "packs.example.com", 443, false,
                "play.example.com", "example.com", 25565));
    }

    @Test void blocksExternalTlsIpsUnsupportedVersionsAndNamesOutsideSans() {
        assertBlocked("disable-internal-tls", () -> AutoModpackIdentitySnapshot.resolve("5.0.0-rc.2", true,
                "MAGIC", -1, "packs.example.com", -1, true,
                "play.example.com", "example.com", 25565));
        assertBlocked("IP address", () -> AutoModpackIdentitySnapshot.resolve("5.0.0-rc.2", true,
                "MAGIC", -1, "192.0.2.1", -1, false,
                "play.example.com", "example.com", 25565));
        assertBlocked("outside", () -> AutoModpackIdentitySnapshot.resolve("5.0.0-rc.2", true,
                "MAGIC", -1, "packs.other.example", -1, false,
                "play.example.com", "example.com", 25565));
        assertBlocked("outside", () -> AutoModpackIdentitySnapshot.resolve("5.0.0", true,
                "MAGIC", -1, "packs.example.com", -1, false,
                "play.example.com", "example.com", 25565));
        assertBlocked("modpack-host=false", () -> AutoModpackIdentitySnapshot.resolve("5.0.0-rc.2", false,
                "MAGIC", -1, "packs.example.com", -1, false,
                "play.example.com", "example.com", 25565));
    }

    private static void assertBlocked(String expected, Runnable operation) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, operation::run);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
