package tech.maicraft.ampautocert;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** Server-only NeoForge entrypoint. */
@Mod(value = AmpAutoCertMod.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class AmpAutoCertMod {
    public static final String MOD_ID = "amp_autocert";
    public static final Logger LOGGER = LoggerFactory.getLogger("amp-autocert");
    private final AutoCertService service;

    public AmpAutoCertMod() {
        service = new AutoCertService(FMLPaths.GAMEDIR.get(), Clock.systemUTC());
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
        NeoForge.EVENT_BUS.addListener(this::serverStopped);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        AmpAutoCertCommands.register(event.getDispatcher(), service);
    }

    private void serverStarted(ServerStartedEvent event) {
        service.start(event.getServer());
    }

    private void serverStopping(ServerStoppingEvent ignored) {
        service.beginStop();
    }

    private void serverStopped(ServerStoppedEvent ignored) {
        service.stop();
    }
}
