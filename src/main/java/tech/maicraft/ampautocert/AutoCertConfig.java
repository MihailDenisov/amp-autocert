package tech.maicraft.ampautocert;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Public operational options only. API tokens and private keys must never enter this file. */
public record AutoCertConfig(boolean enabled, boolean staging, boolean debugMode,
                             String contactEmail, String zoneName, String cloudflareZoneId,
                             String originHostname, int cloudflareTtlSeconds,
                             int acmeNetworkTimeoutSeconds, int dnsPropagationTimeoutSeconds,
                             int dnsPropagationInitialDelaySeconds,
                             int renewBeforeDays, int checkIntervalHours, int driftPollSeconds,
                             int failureCooldownHours, int retryInitialMinutes, int retryMaxHours,
                             int retryJitterPercent, boolean restartHostAfterRenew,
                             int hostActivationTimeoutSeconds, int hostReadinessPollSeconds,
                             boolean allowInsecureTokenFilePermissions,
                             boolean allowInsecureDeploymentFilePermissions,
                             Optional<String> legacyMinecraftSrvTarget,
                             Optional<String> legacyAutoModpackEndpoint,
                             List<String> migrationWarnings) {
    private static final String DEFAULT_RESOURCE = "/amp-autocert.properties.example";
    static final URI CLOUDFLARE_API_ROOT = URI.create("https://api.cloudflare.com/client/v4/");
    static final URI STAGING_DIRECTORY = URI.create("https://acme-staging-v02.api.letsencrypt.org/directory");
    static final URI PRODUCTION_DIRECTORY = URI.create("https://acme-v02.api.letsencrypt.org/directory");
    static final List<URI> DOH_RESOLVERS = List.of(
            URI.create("https://cloudflare-dns.com/dns-query"), URI.create("https://dns.google/resolve"));

    public AutoCertConfig {
        legacyMinecraftSrvTarget = legacyMinecraftSrvTarget == null ? Optional.empty() : legacyMinecraftSrvTarget;
        legacyAutoModpackEndpoint = legacyAutoModpackEndpoint == null ? Optional.empty() : legacyAutoModpackEndpoint;
        migrationWarnings = migrationWarnings == null ? List.of() : List.copyOf(migrationWarnings);
    }

    public static AutoCertConfig load(Path serverRoot) throws IOException {
        Path file = serverRoot.resolve("automodpack/amp-autocert.properties");
        Files.createDirectories(file.getParent());
        if (Files.notExists(file)) {
            try (InputStream defaults = AutoCertConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (defaults == null) throw new IOException("Missing packaged amp-autocert default configuration");
                try {
                    Files.write(file, defaults.readAllBytes(), StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException concurrentCreation) {
                    // Another process created the file first; load that file below.
                }
            }
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        return parse(p);
    }

    static AutoCertConfig parse(Properties p) {
        List<String> warnings = new ArrayList<>();
        boolean enabled = strictBoolean(p, "enabled", false);
        String environment = p.getProperty("acme.environment", "staging").trim().toLowerCase();
        if (!environment.equals("staging") && !environment.equals("production")) {
            throw new IllegalArgumentException("acme.environment must be staging or production");
        }
        String zone = normalizedDnsName(aliasedRequired(p, "cloudflare.zoneName", "dns.zoneName", warnings),
                "cloudflare.zoneName", false);
        String origin = normalizedDnsName(aliasedRequired(p, "origin.hostname", "minecraft.hostname", warnings),
                "origin.hostname", false);
        requireCoveredName(origin, zone, "origin.hostname");
        requireDerivedListIfPresent(p, "acme.domains", Set.of(zone, "*." + zone), warnings);
        requireDerivedIfPresent(p, "dns.challengeRecord", "_acme-challenge." + zone, warnings);
        requireConstantIfPresent(p, "acme.stagingDirectory", STAGING_DIRECTORY.toString(), warnings);
        requireConstantIfPresent(p, "acme.productionDirectory", PRODUCTION_DIRECTORY.toString(), warnings);
        requireConstantIfPresent(p, "cloudflare.apiRoot", CLOUDFLARE_API_ROOT.toString(), warnings);
        requireResolverConstantsIfPresent(p, warnings);
        Optional<String> legacySrv = legacyEndpoint(p, "minecraft.srvTarget", warnings);
        Optional<String> legacyEndpoint = legacyEndpoint(p, "automodpack.endpoint", warnings);
        if (p.containsKey("acme.allowStagingIssuance")) warnings.add(
                "acme.allowStagingIssuance is deprecated; staging validation is automatic and never deployed");

        int networkTimeout = boundedInt(p, "acme.networkTimeoutSeconds", 5, 120, 30);
        int propagationTimeout = boundedInt(p, "dns.propagationTimeoutSeconds", 30, 1800, 120);
        int propagationInitialDelay = boundedInt(p, "dns.propagationInitialDelaySeconds", 0, 60, 15);
        int cloudflareTtl = boundedInt(p, "cloudflare.ttlSeconds", 60, 300, 60);
        int renewDays = boundedInt(p, "renew.beforeDays", 1, 90, 30);
        int intervalHours = boundedInt(p, "check.intervalHours", 1, 168, 24);
        int driftPoll = boundedInt(p, "drift.pollSeconds", 10, 3600, 60);
        int failureCooldownHours = boundedInt(p, "renew.failureCooldownHours", 1, 168, 48);
        int retryInitialMinutes = boundedInt(p, "retry.initialMinutes", 1, 60, 15);
        int retryMaxHours = boundedInt(p, "retry.maxHours", 1, 48, 12);
        int retryJitterPercent = boundedInt(p, "retry.jitterPercent", 0, 50, 20);
        if (Duration.ofMinutes(retryInitialMinutes).compareTo(Duration.ofHours(retryMaxHours)) > 0) {
            throw new IllegalArgumentException("retry.initialMinutes must not exceed retry.maxHours");
        }
        int hostActivationTimeout = boundedInt(p, "host.activationTimeoutSeconds", 10, 300, 60);
        int hostReadinessPoll = boundedInt(p, "host.readinessPollSeconds", 1, 30, 1);
        if (hostReadinessPoll >= hostActivationTimeout) {
            throw new IllegalArgumentException("host.readinessPollSeconds must be less than host.activationTimeoutSeconds");
        }
        String email = required(p, "acme.email");
        String zoneId = required(p, "cloudflare.zoneId");
        if (enabled && !zoneId.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("cloudflare.zoneId must be a 32-character hexadecimal zone ID when enabled");
        }
        if (enabled && (!email.matches("[^@\\s]+@[^@\\s]+") || email.length() > 254)) {
            throw new IllegalArgumentException("acme.email must be a valid contact address when enabled");
        }
        return new AutoCertConfig(enabled, environment.equals("staging"), strictBoolean(p, "debugmode", false),
                email, zone, zoneId, origin, cloudflareTtl,
                networkTimeout, propagationTimeout, propagationInitialDelay,
                renewDays, intervalHours, driftPoll, failureCooldownHours,
                retryInitialMinutes, retryMaxHours, retryJitterPercent,
                strictBoolean(p, "host.restartAfterRenew", true), hostActivationTimeout, hostReadinessPoll,
                strictBoolean(p, "credentials.allowInsecureTokenFilePermissions", false),
                strictBoolean(p, "credentials.allowInsecureDeploymentFilePermissions", false),
                legacySrv, legacyEndpoint, warnings);
    }

    public List<String> domains() { return List.of(zoneName, "*." + zoneName); }
    public String challengeRecord() { return "_acme-challenge." + zoneName; }
    public URI cloudflareApiRoot() { return CLOUDFLARE_API_ROOT; }
    public URI stagingDirectory() { return STAGING_DIRECTORY; }
    public URI productionDirectory() { return PRODUCTION_DIRECTORY; }
    public List<URI> dnsDohResolvers() { return DOH_RESOLVERS; }
    public AcmeEnvironment environment() { return staging ? AcmeEnvironment.STAGING : AcmeEnvironment.PRODUCTION; }
    public URI directory(AcmeEnvironment value) { return value == AcmeEnvironment.STAGING ? STAGING_DIRECTORY : PRODUCTION_DIRECTORY; }
    public Duration acmeNetworkTimeout() { return Duration.ofSeconds(acmeNetworkTimeoutSeconds); }
    public Duration dnsPropagationTimeout() { return Duration.ofSeconds(dnsPropagationTimeoutSeconds); }
    public Duration dnsPropagationInitialDelay() { return Duration.ofSeconds(dnsPropagationInitialDelaySeconds); }
    public Duration checkInterval() { return Duration.ofHours(checkIntervalHours); }
    public Duration driftPollInterval() { return Duration.ofSeconds(driftPollSeconds); }
    public Duration failureCooldown() { return Duration.ofHours(failureCooldownHours); }
    public Duration retryInitialDelay() { return Duration.ofMinutes(retryInitialMinutes); }
    public Duration retryMaxDelay() { return Duration.ofHours(retryMaxHours); }
    public Duration hostActivationTimeout() { return Duration.ofSeconds(hostActivationTimeoutSeconds); }
    public Duration hostReadinessPoll() { return Duration.ofSeconds(hostReadinessPollSeconds); }

    /** Compatibility accessors for the validated v0.1.0 core while identity becomes adapter-owned. */
    public boolean allowStagingIssuance() { return staging; }
    public String minecraftHostname() { return originHostname; }
    public String minecraftSrvTarget() { return legacyMinecraftSrvTarget.orElse(""); }
    public String modpackEndpoint() { return legacyAutoModpackEndpoint.orElse(originHostname + ":25565"); }

    static boolean covers(String hostname, String zone) {
        if (hostname == null) return false;
        String normalized = hostname.toLowerCase();
        if (normalized.equals(zone)) return true;
        if (!normalized.endsWith("." + zone)) return false;
        String label = normalized.substring(0, normalized.length() - zone.length() - 1);
        return !label.isEmpty() && !label.contains(".");
    }

    private static String aliasedRequired(Properties p, String current, String legacy, List<String> warnings) {
        String currentValue = p.getProperty(current, "").trim();
        String legacyValue = p.getProperty(legacy, "").trim();
        if (!currentValue.isEmpty() && !legacyValue.isEmpty() && !currentValue.equalsIgnoreCase(legacyValue)) {
            throw new IllegalArgumentException(current + " conflicts with deprecated " + legacy);
        }
        if (!currentValue.isEmpty()) return currentValue;
        if (!legacyValue.isEmpty()) {
            warnings.add(legacy + " is deprecated; rename it to " + current);
            return legacyValue;
        }
        throw new IllegalArgumentException(current + " is required");
    }

    private static Optional<String> legacyEndpoint(Properties p, String key, List<String> warnings) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) return Optional.empty();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException(key + " must use host:port");
        }
        String host = normalizedDnsName(value.substring(0, separator), key, false);
        int port;
        try { port = Integer.parseInt(value.substring(separator + 1)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " port must be numeric"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException(key + " port is out of range");
        warnings.add(key + " is deprecated and used only as a migration conflict guard");
        return Optional.of(host + ":" + port);
    }

    private static void requireDerivedIfPresent(Properties p, String key, String expected, List<String> warnings) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) return;
        if (!value.equalsIgnoreCase(expected)) throw new IllegalArgumentException(key + " conflicts with its derived value");
        warnings.add(key + " is deprecated; remove it because it is derived");
    }

    private static void requireDerivedListIfPresent(Properties p, String key, Set<String> expected,
                                                    List<String> warnings) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) return;
        Set<String> actual = Set.copyOf(Arrays.stream(value.split(",")).map(String::trim)
                .map(String::toLowerCase).filter(entry -> !entry.isEmpty()).toList());
        if (!actual.equals(expected)) throw new IllegalArgumentException(key + " conflicts with its derived value");
        warnings.add(key + " is deprecated; remove it because it is derived");
    }

    private static void requireConstantIfPresent(Properties p, String key, String expected, List<String> warnings) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) return;
        if (!value.equals(expected)) throw new IllegalArgumentException(key + " conflicts with the pinned provider constant");
        warnings.add(key + " is deprecated; remove it because it is an implementation constant");
    }

    private static void requireResolverConstantsIfPresent(Properties p, List<String> warnings) {
        String value = p.getProperty("dns.dohResolvers", "").trim();
        if (value.isEmpty()) return;
        Set<String> actual = Set.copyOf(Arrays.stream(value.split(",")).map(String::trim)
                .filter(entry -> !entry.isEmpty()).toList());
        Set<String> expected = Set.of(DOH_RESOLVERS.get(0).toString(), DOH_RESOLVERS.get(1).toString());
        if (!actual.equals(expected)) throw new IllegalArgumentException(
                "dns.dohResolvers conflicts with the pinned independent resolver set");
        warnings.add("dns.dohResolvers is deprecated; remove it because resolvers are implementation constants");
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int boundedInt(Properties properties, String key, int minimum, int maximum, int fallback) {
        String value = properties.getProperty(key, Integer.toString(fallback)).trim();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean strictBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    static String normalizedDnsName(String value, String key, boolean allowUnderscore) {
        String normalized = value.toLowerCase();
        String labelStart = allowUnderscore ? "[a-z0-9_]" : "[a-z0-9]";
        String labelBody = allowUnderscore ? "[a-z0-9_-]" : "[a-z0-9-]";
        if (normalized.endsWith(".") || normalized.length() > 253
                || !normalized.matches("(?=.{1,253}$)(?:" + labelStart + "(?:" + labelBody + "{0,61}" + labelStart
                + ")?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException(key + " must be a normalized DNS name");
        }
        return normalized;
    }

    private static void requireCoveredName(String hostname, String zone, String key) {
        if (!covers(hostname, zone)) {
            throw new IllegalArgumentException(key + " must be the zone apex or exactly one label below cloudflare.zoneName");
        }
    }
}
