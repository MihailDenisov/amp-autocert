package tech.maicraft.ampautocert;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/** Prevents third-party protocol loggers from writing ACME payloads or challenge tokens. */
final class SensitiveLibraryLogging {
    private SensitiveLibraryLogging() { }

    static void suppressProtocolPayloads() {
        Configurator.setLevel("org.shredzone.acme4j", Level.INFO);
        Configurator.setLevel("org.shredzone.acme4j.connector.DefaultConnection", Level.WARN);
        Configurator.setLevel("org.shredzone.acme4j.toolbox.JoseUtils", Level.WARN);
        Configurator.setLevel("org.jose4j", Level.INFO);
    }
}
