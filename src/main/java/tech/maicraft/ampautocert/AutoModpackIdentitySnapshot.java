package tech.maicraft.ampautocert;

import java.util.Locale;

/** Security-relevant effective AutoModpack routing and TLS identity. */
record AutoModpackIdentitySnapshot(String autoModpackVersion, boolean modpackHost,
                                   ConnectionMode connectionMode, int bindPort,
                                   String endpointHost, int endpointPort,
                                   boolean disableInternalTls) {
    enum ConnectionMode { HOLEPUNCH, MAGIC, HTTP }

    static AutoModpackIdentitySnapshot resolve(String version, boolean modpackHost, String mode,
                                               int configuredBindPort, String advertisedHost,
                                               int advertisedPort, boolean disableInternalTls,
                                               String originHostname, String zoneName,
                                               int minecraftPort) {
        if (!AutoModpackHostControl.isSupportedVersion(version)) {
            throw new IllegalArgumentException("AutoModpack version is outside "
                    + AutoModpackHostControl.SUPPORTED_VERSION_RANGE);
        }
        if (!modpackHost) {
            throw new IllegalArgumentException("AutoModpack modpack-host=false is an external-host topology");
        }
        if (disableInternalTls) {
            throw new IllegalArgumentException("AutoModpack disable-internal-tls=true delegates TLS elsewhere");
        }
        ConnectionMode parsedMode;
        try { parsedMode = ConnectionMode.valueOf(mode.strip().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Unsupported AutoModpack connection-mode"); }
        if (minecraftPort < 1 || minecraftPort > 65535) {
            throw new IllegalArgumentException("Minecraft server port is unavailable");
        }
        int bindPort = switch (parsedMode) {
            case HOLEPUNCH -> minecraftPort;
            case MAGIC -> configuredBindPort == -1 ? minecraftPort : requirePort(configuredBindPort, "bind-port");
            case HTTP -> {
                if (configuredBindPort == -1) {
                    throw new IllegalArgumentException("AutoModpack HTTP mode requires a dedicated bind-port");
                }
                yield requirePort(configuredBindPort, "bind-port");
            }
        };
        String endpointHost = advertisedHost == null || advertisedHost.isBlank()
                ? originHostname : advertisedHost.strip().toLowerCase(Locale.ROOT);
        if (looksLikeIp(endpointHost)) {
            throw new IllegalArgumentException("AutoModpack advertised endpoint is an IP address");
        }
        endpointHost = AutoCertConfig.normalizedDnsName(endpointHost,
                "AutoModpack advertised-endpoint-host", false);
        if (!AutoCertConfig.covers(originHostname, zoneName)) {
            throw new IllegalArgumentException("origin.hostname is outside the apex/wildcard certificate coverage");
        }
        if (!AutoCertConfig.covers(endpointHost, zoneName)) {
            throw new IllegalArgumentException("AutoModpack endpoint is outside the apex/wildcard certificate coverage");
        }
        int endpointPort = advertisedPort == -1 ? minecraftPort
                : requirePort(advertisedPort, "advertised-endpoint-port");
        return new AutoModpackIdentitySnapshot(version, true, parsedMode, bindPort,
                endpointHost, endpointPort, false);
    }

    String endpoint() { return endpointHost + ":" + endpointPort; }

    private static int requirePort(int value, String name) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("AutoModpack " + name + " is out of range");
        return value;
    }

    private static boolean looksLikeIp(String value) {
        return value.indexOf(':') >= 0 || value.matches("[0-9.]+") || value.startsWith("[");
    }
}
