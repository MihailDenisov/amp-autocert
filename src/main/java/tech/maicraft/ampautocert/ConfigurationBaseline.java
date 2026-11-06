package tech.maicraft.ampautocert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Canonical, non-secret snapshot of certificate identity and AutoModpack routing/TLS ownership. */
record ConfigurationBaseline(int schemaVersion, String originHostname, String cloudflareZoneName,
                             String cloudflareZoneId, String endpointHost, int endpointPort,
                             String connectionMode, int bindPort, boolean disableInternalTls,
                             boolean modpackHost, String hash) {
    static final int SCHEMA_VERSION = 1;

    static ConfigurationBaseline from(AutoCertConfig config, AutoModpackIdentitySnapshot identity) {
        Map<String, String> fields = fields(config.originHostname(), config.zoneName(),
                config.cloudflareZoneId().toLowerCase(), identity.endpointHost(), identity.endpointPort(),
                identity.connectionMode().name(), identity.bindPort(), identity.disableInternalTls(),
                identity.modpackHost());
        return new ConfigurationBaseline(SCHEMA_VERSION, fields.get("origin.hostname"),
                fields.get("cloudflare.zoneName"), fields.get("cloudflare.zoneId"),
                fields.get("automodpack.endpointHost"), Integer.parseInt(fields.get("automodpack.endpointPort")),
                fields.get("automodpack.connectionMode"), Integer.parseInt(fields.get("automodpack.bindPort")),
                Boolean.parseBoolean(fields.get("automodpack.disableInternalTls")),
                Boolean.parseBoolean(fields.get("automodpack.modpackHost")), hash(fields));
    }

    static ConfigurationBaseline parse(Properties properties, String prefix) {
        int schema = integer(properties, prefix + "schemaVersion");
        if (schema != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported baseline schema version");
        Map<String, String> fields = fields(required(properties, prefix + "origin.hostname"),
                required(properties, prefix + "cloudflare.zoneName"),
                required(properties, prefix + "cloudflare.zoneId"),
                required(properties, prefix + "automodpack.endpointHost"),
                integer(properties, prefix + "automodpack.endpointPort"),
                required(properties, prefix + "automodpack.connectionMode"),
                integer(properties, prefix + "automodpack.bindPort"),
                bool(properties, prefix + "automodpack.disableInternalTls"),
                bool(properties, prefix + "automodpack.modpackHost"));
        String storedHash = required(properties, prefix + "hash").toLowerCase();
        String expectedHash = hash(fields);
        if (!storedHash.equals(expectedHash)) throw new IllegalArgumentException("Configuration baseline hash mismatch");
        return new ConfigurationBaseline(schema, fields.get("origin.hostname"), fields.get("cloudflare.zoneName"),
                fields.get("cloudflare.zoneId"), fields.get("automodpack.endpointHost"),
                Integer.parseInt(fields.get("automodpack.endpointPort")), fields.get("automodpack.connectionMode"),
                Integer.parseInt(fields.get("automodpack.bindPort")),
                Boolean.parseBoolean(fields.get("automodpack.disableInternalTls")),
                Boolean.parseBoolean(fields.get("automodpack.modpackHost")), storedHash);
    }

    void writeTo(StringBuilder output, String prefix) {
        output.append(prefix).append("schemaVersion=").append(schemaVersion).append('\n');
        for (var entry : values().entrySet()) {
            output.append(prefix).append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        output.append(prefix).append("hash=").append(hash).append('\n');
    }

    List<String> differences(ConfigurationBaseline other) {
        Map<String, String> left = values();
        Map<String, String> right = other.values();
        List<String> differences = new ArrayList<>();
        for (String key : left.keySet()) if (!left.get(key).equals(right.get(key))) differences.add(key);
        return List.copyOf(differences);
    }

    private Map<String, String> values() {
        return fields(originHostname, cloudflareZoneName, cloudflareZoneId, endpointHost, endpointPort,
                connectionMode, bindPort, disableInternalTls, modpackHost);
    }

    private static Map<String, String> fields(String origin, String zone, String zoneId, String endpointHost,
                                              int endpointPort, String mode, int bindPort,
                                              boolean disableTls, boolean modpackHost) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("origin.hostname", AutoCertConfig.normalizedDnsName(origin, "origin.hostname", false));
        values.put("cloudflare.zoneName", AutoCertConfig.normalizedDnsName(zone, "cloudflare.zoneName", false));
        if (!zoneId.matches("(?i)[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid baseline Cloudflare zone ID");
        values.put("cloudflare.zoneId", zoneId.toLowerCase());
        values.put("automodpack.endpointHost", AutoCertConfig.normalizedDnsName(endpointHost,
                "automodpack.endpointHost", false));
        values.put("automodpack.endpointPort", port(endpointPort, "endpointPort"));
        values.put("automodpack.connectionMode", AutoModpackIdentitySnapshot.ConnectionMode
                .valueOf(mode.toUpperCase()).name());
        values.put("automodpack.bindPort", port(bindPort, "bindPort"));
        values.put("automodpack.disableInternalTls", Boolean.toString(disableTls));
        values.put("automodpack.modpackHost", Boolean.toString(modpackHost));
        return values;
    }

    private static String hash(Map<String, String> fields) {
        StringBuilder canonical = new StringBuilder("schemaVersion=").append(SCHEMA_VERSION).append('\n');
        fields.forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String port(int value, String key) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Invalid baseline " + key);
        return Integer.toString(value);
    }
    private static String required(Properties values, String key) {
        String value = values.getProperty(key, "").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static int integer(Properties values, String key) {
        try { return Integer.parseInt(required(values, key)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid " + key); }
    }
    private static boolean bool(Properties values, String key) {
        String value = required(values, key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid " + key);
        return Boolean.parseBoolean(value);
    }
}
