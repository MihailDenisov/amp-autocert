package tech.maicraft.ampautocert;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class AmpAutoCertCommands {
    private AmpAutoCertCommands() { }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, AutoCertService service) {
        dispatcher.register(Commands.literal("ampautocert")
                .requires(source -> CommandPermission.isAllowed(source::hasPermission))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource(), service)))
                .then(Commands.literal("doctor").executes(context -> doctor(context.getSource(), service)))
                .then(Commands.literal("check").executes(context -> check(context.getSource(), service)))
                .then(Commands.literal("staging-issue").executes(context -> stagingIssue(context.getSource(), service)))
                .then(Commands.literal("reconcile")
                        .executes(context -> reconcilePrompt(context.getSource()))
                        .then(Commands.literal("confirm")
                                .executes(context -> reconcile(context.getSource(), service))))
                .then(Commands.literal("renew").executes(context -> renew(context.getSource(), service))));
    }

    private static int help(CommandSourceStack source) {
        for (String line : CommandHelp.lines()) source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int status(CommandSourceStack source, AutoCertService service) {
        for (String line : service.status().format(service.clock())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int check(CommandSourceStack source, AutoCertService service) {
        AutoCertService.CheckRequest result = service.requestCheck();
        String message = switch (result) {
            case QUEUED -> "amp-autocert check queued on the background worker.";
            case ALREADY_QUEUED -> "amp-autocert check is already queued or running.";
            case NOT_RUNNING -> "amp-autocert renewal lifecycle is not running; inspect /ampautocert status.";
            case STOPPING -> "amp-autocert is stopping; no new check was queued.";
        };
        source.sendSuccess(() -> Component.literal(message), false);
        return result == AutoCertService.CheckRequest.QUEUED ? 1 : 0;
    }

    private static int doctor(CommandSourceStack source, AutoCertService service) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        AutoCertService.DoctorRequest result = service.requestDoctor(report -> server.execute(() -> {
            for (String line : report.format()) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }));
        String message = switch (result) {
            case QUEUED -> "amp-autocert doctor queued on the background worker; it is read-only.";
            case ALREADY_QUEUED -> "amp-autocert doctor, check, renewal, or staging issuance is already running.";
            case NOT_RUNNING -> "amp-autocert doctor is unavailable because initialization did not complete.";
            case STOPPING -> "amp-autocert is stopping; doctor was not queued.";
        };
        source.sendSuccess(() -> Component.literal(message), false);
        return result == AutoCertService.DoctorRequest.QUEUED ? 1 : 0;
    }

    private static int renew(CommandSourceStack source, AutoCertService service) {
        AutoCertService.RenewRequest result = service.requestRenewal();
        String message = switch (result) {
            case QUEUED -> "amp-autocert production renewal queued on the background worker.";
            case ALREADY_QUEUED -> "amp-autocert check or renewal is already queued or running.";
            case STAGING_BLOCKED -> "amp-autocert renewal rejected: acme.environment must be production. Staging certificates are never deployed.";
            case NOT_RUNNING -> "amp-autocert renewal lifecycle is not running; inspect /ampautocert status.";
            case STOPPING -> "amp-autocert is stopping; no renewal was queued.";
        };
        source.sendSuccess(() -> Component.literal(message), false);
        return result == AutoCertService.RenewRequest.QUEUED ? 1 : 0;
    }

    private static int stagingIssue(CommandSourceStack source, AutoCertService service) {
        AutoCertService.StagingRequest result = service.requestStagingIssuance();
        String message = switch (result) {
            case QUEUED -> "amp-autocert staging issuance queued; the result will be structurally inspected and discarded, never deployed.";
            case ALREADY_QUEUED -> "amp-autocert check, renewal, or staging issuance is already queued or running.";
            case NOT_ALLOWED -> "amp-autocert staging issuance rejected: set acme.environment=staging; an accepted baseline is also required.";
            case NOT_RUNNING -> "amp-autocert renewal lifecycle is not running; set enabled=true and inspect /ampautocert status.";
            case STOPPING -> "amp-autocert is stopping; no staging issuance was queued.";
        };
        source.sendSuccess(() -> Component.literal(message), false);
        return result == AutoCertService.StagingRequest.QUEUED ? 1 : 0;
    }

    private static int reconcilePrompt(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Reconciliation runs full preflight and a real Let's Encrypt staging DNS-01 issuance. "
                        + "If you intend to accept the discovered configuration, run /ampautocert reconcile confirm."),
                false);
        return 0;
    }

    private static int reconcile(CommandSourceStack source, AutoCertService service) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        AutoCertService.ReconcileRequest result = service.requestReconciliation(completed ->
                server.execute(() -> {
                    for (String line : completed.format()) {
                        source.sendSuccess(() -> Component.literal(line), false);
                    }
                }));
        String message = switch (result) {
            case QUEUED -> "amp-autocert reconciliation queued; automation remains blocked until staging validation and baseline persistence succeed.";
            case ALREADY_QUEUED -> "amp-autocert doctor, check, renewal, staging issuance, or reconciliation is already running.";
            case NOT_RUNNING -> "amp-autocert reconciliation is unavailable because the enabled lifecycle did not initialize.";
            case STOPPING -> "amp-autocert is stopping; reconciliation was not queued.";
        };
        source.sendSuccess(() -> Component.literal(message), false);
        return result == AutoCertService.ReconcileRequest.QUEUED ? 1 : 0;
    }
}
