package tech.maicraft.ampautocert;

import java.util.function.IntPredicate;

final class CommandPermission {
    static final int REQUIRED_LEVEL = 3;

    private CommandPermission() { }

    static boolean isAllowed(IntPredicate permissionCheck) {
        return permissionCheck.test(REQUIRED_LEVEL);
    }
}
