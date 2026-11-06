package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoModpackHostControlVersionTest {
    @Test void acceptsInspectedReleaseCandidates() {
        for (int candidate = 1; candidate <= 7; candidate++) {
            assertTrue(AutoModpackHostControl.isSupportedVersion("5.0.0-rc." + candidate));
        }
    }

    @Test void rejectsUninspectedOlderAndFutureVersions() {
        assertFalse(AutoModpackHostControl.isSupportedVersion("4.0.6"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-beta.1"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.8"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.07"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.1"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.1.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("6.0.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion(null));
    }
}
