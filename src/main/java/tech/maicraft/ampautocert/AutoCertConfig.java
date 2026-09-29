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
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Public operational options only. API tokens and private keys must never enter this file. */
public record AutoCertConfig(boolean enabled, boolean staging, boolean allowStagingIssuance, boolean debugMode,
                             String contactEmail,
                             String zoneName, List<String> domains, String challengeRecord,
                             String cloudflareZoneId, URI cloudflareApiRoot, int cloudflareTtlSeconds,
                             String minecraftHostname, String minecraftSrvTarget,
                             String modpackEndpoint, URI stagingDirectory, URI productionDirectory,
                             int acmeNetworkTimeoutSeconds, int dnsPropagationTimeoutSeconds,
                             int dnsPropagationInitialDelaySeconds, List<URI> dnsDohResolvers,
                             int renewBeforeDays, int checkIntervalHours, int failureCooldownHours,
                             int retryInitialMinutes, int retryMaxHours, int retryJitterPercent,
                             boolean restartHostAfterRenew, int hostActivationTimeoutSeconds,
                             int hostReadinessPollSeconds,
                             boolean allowInsecureTokenFilePermissions,
                             boolean allowInsecureDeploymentFilePermissions) {
    private static final String DEFAULT_RESOURCE = "/amp-autocert.properties.example";

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

        boolean enabled = strictBoolean(p, "enabled", false);
        String environment = required(p, "acme.environment");
        if (!environment.equals("staging") && !environment.equals("production")) {
            throw new IllegalArgumentException("acme.environment must be staging or production");
        }
        String zone = normalizedDnsName(required(p, "dns.zoneName"), "dns.zoneName", false);
        List<String> domains = Arrays.stream(required(p, "acme.domains").split(","))
                .map(String::trim).map(String::toLowerCase).filter(value -> !value.isEmpty()).distinct().toList();
        Set<String> requiredDomains = Set.of(zone, "*." + zone);
        if (domains.size() != 2 || !Set.copyOf(domains).equals(requiredDomains)) {
            throw new IllegalArgumentException("acme.domains must be the apex/wildcard pair for dns.zoneName");
        }
        String challenge = normalizedDnsName(required(p, "dns.challengeRecord"), "dns.challengeRecord", true);
        if (!challenge.equals("_acme-challenge." + zone)) {
            throw new IllegalArgumentException("dns.challengeRecord must be _acme-challenge under dns.zoneName");
        }
        String minecraftHost = normalizedDnsName(required(p, "minecraft.hostname"), "minecraft.hostname", false);
        requireSubdomain(minecraftHost, zone, "minecraft.hostname");
        String srvTarget = validatedEndpoint(required(p, "minecraft.srvTarget"), zone, "minecraft.srvTarget");
        String modpackEndpoint = validatedEndpoint(required(p, "automodpack.endpoint"), zone, "automodpack.endpoint");
        URI stagingDirectory = validatedDirectory(required(p, "acme.stagingDirectory"), "acme.stagingDirectory");
        URI productionDirectory = validatedDirectory(required(p, "acme.productionDirectory"), "acme.productionDirectory");
        if (stagingDirectory.equals(productionDirectory)) {
            throw new IllegalArgumentException("ACME staging and production directories must differ");
        }
        if (!stagingDirectory.getHost().toLowerCase().contains("staging")
                || productionDirectory.getHost().toLowerCase().contains("staging")) {
            throw new IllegalArgumentException("ACME directory URLs do not match their staging/production roles");
        }

        int networkTimeout = boundedInt(p, "acme.networkTimeoutSeconds", 5, 120);
        int propagationTimeout = boundedInt(p, "dns.propagationTimeoutSeconds", 30, 1800);
        int propagationInitialDelay = boundedInt(p, "dns.propagationInitialDelaySeconds", 0, 60, 15);
        List<URI> dohResolvers = validatedDohResolvers(p.getProperty("dns.dohResolvers",
                "https://cloudflare-dns.com/dns-query,https://dns.google/resolve"));
        URI cloudflareApiRoot = validatedCloudflareApiRoot(required(p, "cloudflare.apiRoot"));
        int cloudflareTtl = boundedInt(p, "cloudflare.ttlSeconds", 60, 300);
        int renewDays = boundedInt(p, "renew.beforeDays", 1, 90);
        int intervalHours = boundedInt(p, "check.intervalHours", 1, 168);
        // Migration fallback for configurations created before this safety control existed.
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
        return new AutoCertConfig(enabled, environment.equals("staging"),
                strictBoolean(p, "acme.allowStagingIssuance", false),
                strictBoolean(p, "debugmode", false), email, zone, List.copyOf(domains),
                challenge, zoneId, cloudflareApiRoot, cloudflareTtl, minecraftHost, srvTarget, modpackEndpoint,
                stagingDirectory, productionDirectory,
                networkTimeout, propagationTimeout, propagationInitialDelay, dohResolvers,
                renewDays, intervalHours, failureCooldownHours,
                retryInitialMinutes, retryMaxHours, retryJitterPercent,
                strictBoolean(p, "host.restartAfterRenew", true),
                hostActivationTimeout, hostReadinessPoll,
                strictBoolean(p, "credentials.allowInsecureTokenFilePermissions", false),
                strictBoolean(p, "credentials.allowInsecureDeploymentFilePermissions", false));
    }

    public AcmeEnvironment environment() { return staging ? AcmeEnvironment.STAGING : AcmeEnvironment.PRODUCTION; }
    public URI directory(AcmeEnvironment value) { return value == AcmeEnvironment.STAGING ? stagingDirectory : productionDirectory; }
    public Duration acmeNetworkTimeout() { return Duration.ofSeconds(acmeNetworkTimeoutSeconds); }
    public Duration dnsPropagationTimeout() { return Duration.ofSeconds(dnsPropagationTimeoutSeconds); }
    public Duration dnsPropagationInitialDelay() { return Duration.ofSeconds(dnsPropagationInitialDelaySeconds); }
    public Duration checkInterval() { return Duration.ofHours(checkIntervalHours); }
    public Duration failureCooldown() { return Duration.ofHours(failureCooldownHours); }
    public Duration retryInitialDelay() { return Duration.ofMinutes(retryInitialMinutes); }
    public Duration retryMaxDelay() { return Duration.ofHours(retryMaxHours); }
    public Duration hostActivationTimeout() { return Duration.ofSeconds(hostActivationTimeoutSeconds); }
    public Duration hostReadinessPoll() { return Duration.ofSeconds(hostReadinessPollSeconds); }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int boundedInt(Properties properties, String key, int minimum, int maximum) {
        int value;
        try { value = Integer.parseInt(required(properties, key)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer"); }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
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

    private static String normalizedDnsName(String value, String key, boolean allowUnderscore) {
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

    private static void requireSubdomain(String hostname, String zone, String key) {
        if (hostname.equals(zone) || !hostname.endsWith("." + zone)) {
            throw new IllegalArgumentException(key + " must be a subdomain of dns.zoneName");
        }
        String wildcardLabel = hostname.substring(0, hostname.length() - zone.length() - 1);
        if (wildcardLabel.isEmpty() || wildcardLabel.contains(".")) {
            throw new IllegalArgumentException(key + " must be exactly one label below dns.zoneName");
        }
    }

    private static String validatedEndpoint(String value, String zone, String key) {
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) throw new IllegalArgumentException(key + " must use host:port");
        String host = normalizedDnsName(value.substring(0, separator), key, false);
        requireSubdomain(host, zone, key);
        int port;
        try { port = Integer.parseInt(value.substring(separator + 1)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " port must be numeric"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException(key + " port is out of range");
        return host + ":" + port;
    }

    private static URI validatedDirectory(String value, String key) {
        URI uri;
        try { uri = URI.create(value).normalize(); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(key + " must be an HTTPS URI"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().endsWith("/directory")
                || !uri.getHost().toLowerCase().endsWith(".letsencrypt.org")) {
            throw new IllegalArgumentException(key + " must be an HTTPS ACME directory URI without credentials or query");
        }
        return uri;
    }

    private static URI validatedCloudflareApiRoot(String value) {
        URI uri = URI.create(value).normalize();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.cloudflare.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !"/client/v4/".equals(uri.getPath())) {
            throw new IllegalArgumentException("cloudflare.apiRoot must be Cloudflare's HTTPS v4 API root");
        }
        return uri;
    }

    private static List<URI> validatedDohResolvers(String value) {
        List<URI> resolvers = Arrays.stream(value.split(","))
                .map(String::trim).filter(entry -> !entry.isEmpty())
                .map(AutoCertConfig::validatedDohResolver).distinct().toList();
        if (resolvers.size() != 2) {
            throw new IllegalArgumentException("dns.dohResolvers must contain both supported public resolvers");
        }
        Set<URI> required = Set.of(URI.create("https://cloudflare-dns.com/dns-query"),
                URI.create("https://dns.google/resolve"));
        if (!Set.copyOf(resolvers).equals(required)) {
            throw new IllegalArgumentException("dns.dohResolvers must contain only the supported HTTPS resolver endpoints");
        }
        return List.copyOf(resolvers);
    }

    private static URI validatedDohResolver(String value) {
        URI uri;
        try { uri = URI.create(value).normalize(); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("dns.dohResolvers contains an invalid HTTPS URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("dns.dohResolvers must use supported HTTPS resolver endpoints");
        }
        return uri;
    }
}
