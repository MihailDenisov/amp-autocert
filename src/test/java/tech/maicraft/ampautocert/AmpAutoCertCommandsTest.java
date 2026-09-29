package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmpAutoCertCommandsTest {
    @Test
    void permissionLevelTwoIsRejectedAndLevelThreeIsAllowed() {
        assertFalse(CommandPermission.isAllowed(required -> 2 >= required));
        assertTrue(CommandPermission.isAllowed(required -> 3 >= required));
    }
}
