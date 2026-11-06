package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AmpAutoCertCommandsTest {
    @Test
    void permissionLevelTwoIsRejectedAndLevelThreeIsAllowed() {
        assertFalse(CommandPermission.isAllowed(required -> 2 >= required));
        assertTrue(CommandPermission.isAllowed(required -> 3 >= required));
    }

    @Test
    void helpListsEveryRegisteredActionAndDistinguishesProductionFromStaging() {
        var help = CommandHelp.lines();
        assertEquals(8, help.size());
        assertTrue(help.stream().anyMatch(line -> line.contains("reconcile confirm") && line.contains("staging")));
        assertTrue(help.stream().anyMatch(line -> line.contains("/ampautocert renew") && line.contains("production")));
    }
}
