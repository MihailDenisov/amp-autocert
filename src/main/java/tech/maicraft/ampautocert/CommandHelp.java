package tech.maicraft.ampautocert;

import java.util.List;

/** Operator-facing command summary without a Minecraft runtime dependency. */
final class CommandHelp {
    private CommandHelp() { }

    static List<String> lines() {
        return List.of(
                "/ampautocert help — show this command summary.",
                "/ampautocert status — show cached state, certificate expiry and last result.",
                "/ampautocert doctor — run a read-only configuration and provider preflight.",
                "/ampautocert check — check whether production renewal is due.",
                "/ampautocert staging-issue — test staging issuance when staging mode and baseline are active; never deploy it.",
                "/ampautocert reconcile — explain the configuration acceptance step.",
                "/ampautocert reconcile confirm — validate staging and accept the current configuration baseline.",
                "/ampautocert renew — request early production renewal; safety gates and cooldown still apply.");
    }
}
