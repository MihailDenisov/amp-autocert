package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoModpackHostControlVersionTest {
    @Test void acceptsInspectedReleaseCandidates() {
        assertTrue(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.1"));
        assertTrue(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.2"));
    }

    @Test void rejectsUninspectedOlderAndFutureVersions() {
        assertFalse(AutoModpackHostControl.isSupportedVersion("4.0.6"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-beta.1"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0-rc.3"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.0.1"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("5.1.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion("6.0.0"));
        assertFalse(AutoModpackHostControl.isSupportedVersion(null));
    }
}
