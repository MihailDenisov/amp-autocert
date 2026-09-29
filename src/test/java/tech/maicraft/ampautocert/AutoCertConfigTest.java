package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class AutoCertConfigTest {
    @TempDir Path root;

    @Test void createsDisabledStagingDefaultsWithoutSecrets() throws Exception {
        AutoCertConfig cfg = AutoCertConfig.load(root);
        assertFalse(cfg.enabled());
        assertTrue(cfg.staging());
        assertFalse(cfg.allowStagingIssuance());
        assertFalse(cfg.debugMode());
        assertEquals(30, cfg.renewBeforeDays());
        assertTrue(cfg.domains().contains("*.maicraft.tech"));
        assertEquals("maicraft.tech", cfg.zoneName());
        assertEquals("_acme-challenge.maicraft.tech", cfg.challengeRecord());
        assertEquals("hm.maicraft.tech:25598", cfg.minecraftSrvTarget());
        assertEquals("hm.maicraft.tech:25809", cfg.modpackEndpoint());
        assertEquals(30, cfg.acmeNetworkTimeoutSeconds());
        assertEquals(120, cfg.dnsPropagationTimeoutSeconds());
        assertEquals(15, cfg.dnsPropagationInitialDelaySeconds());
        assertEquals(List.of("cloudflare-dns.com", "dns.google"),
                cfg.dnsDohResolvers().stream().map(java.net.URI::getHost).toList());
        assertEquals(48, cfg.failureCooldownHours());
        assertEquals(15, cfg.retryInitialMinutes());
        assertEquals(12, cfg.retryMaxHours());
        assertEquals(20, cfg.retryJitterPercent());
        assertEquals(60, cfg.hostActivationTimeoutSeconds());
        assertEquals(1, cfg.hostReadinessPollSeconds());
        assertEquals("https://api.cloudflare.com/client/v4/", cfg.cloudflareApiRoot().toString());
        assertEquals(60, cfg.cloudflareTtlSeconds());
        assertFalse(cfg.allowInsecureTokenFilePermissions());
        assertFalse(cfg.allowInsecureDeploymentFilePermissions());
        String file = Files.readString(root.resolve("automodpack/amp-autocert.properties"));
        assertFalse(file.contains("AMP_AUTOCERT_CF_API_TOKEN="));
        assertFalse(file.contains("dns_cloudflare_api_token="));
        assertFalse(Files.exists(root.resolve("config/amp-autocert.properties")));
    }

    @Test void rejectsDomainOutsideFixedAllowlist() throws Exception {
        assertConfigRejected(text -> text.replace("maicraft.tech,*.maicraft.tech", "maicraft.tech,evil.example"),
                "acme.domains");
    }

    @Test void requiresWildcardAndApexPair() throws Exception {
        assertConfigRejected(text -> text.replace("maicraft.tech,*.maicraft.tech", "*.maicraft.tech"),
                "acme.domains");
    }

    @Test void rejectsCrossZoneMinecraftHostnameAndEndpoint() throws Exception {
        assertConfigRejected(text -> text.replace("play.maicraft.tech", "play.evil.example"),
                "minecraft.hostname");
        assertConfigRejected(text -> text.replace("hm.maicraft.tech:25809", "host.evil.example:443"),
                "automodpack.endpoint");
    }

    @Test void enabledConfigRequiresCloudflareZoneId() throws Exception {
        assertConfigRejected(text -> text.replace("enabled=false", "enabled=true"), "cloudflare.zoneId");
    }

    @Test void rejectsAmbiguousBooleanValues() throws Exception {
        assertConfigRejected(text -> text.replace("enabled=false", "enabled=yes"), "enabled");
        assertConfigRejected(text -> text.replace("debugmode=false", "debugmode=yes"), "debugmode");
        assertConfigRejected(text -> text.replace("acme.allowStagingIssuance=false",
                        "acme.allowStagingIssuance=yes"),
                "acme.allowStagingIssuance");
        assertConfigRejected(text -> text.replace(
                        "credentials.allowInsecureTokenFilePermissions=false",
                        "credentials.allowInsecureTokenFilePermissions=yes"),
                "credentials.allowInsecureTokenFilePermissions");
        assertConfigRejected(text -> text.replace(
                        "credentials.allowInsecureDeploymentFilePermissions=false",
                        "credentials.allowInsecureDeploymentFilePermissions=yes"),
                "credentials.allowInsecureDeploymentFilePermissions");
        assertConfigRejected(text -> text.replace("host.activationTimeoutSeconds=60",
                        "host.activationTimeoutSeconds=5"),
                "host.activationTimeoutSeconds");
        assertConfigRejected(text -> text.replace("host.activationTimeoutSeconds=60",
                        "host.activationTimeoutSeconds=10")
                        .replace("host.readinessPollSeconds=1", "host.readinessPollSeconds=10"),
                "less than");
    }

    @Test void permitsExplicitPterodactylDeploymentPermissionCompatibilityMode() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        Files.writeString(file, Files.readString(file).replace(
                "credentials.allowInsecureDeploymentFilePermissions=false",
                "credentials.allowInsecureDeploymentFilePermissions=true"));

        assertTrue(AutoCertConfig.load(root).allowInsecureDeploymentFilePermissions());
    }

    @Test void permitsExplicitSafeDiagnosticMode() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        Files.writeString(file, Files.readString(file).replace("debugmode=false", "debugmode=true"));

        assertTrue(AutoCertConfig.load(root).debugMode());
    }

    @Test void permitsExplicitPterodactylTokenFileCompatibilityMode() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        Files.writeString(file, Files.readString(file).replace(
                "credentials.allowInsecureTokenFilePermissions=false",
                "credentials.allowInsecureTokenFilePermissions=true"));

        assertTrue(AutoCertConfig.load(root).allowInsecureTokenFilePermissions());
    }

    @Test void olderConfigGetsSafeLifecycleFallbacks() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        String oldConfig = Files.readString(file)
                .replace("debugmode=false\n", "")
                .replace("renew.failureCooldownHours=48\n", "")
                .replace("retry.initialMinutes=15\n", "")
                .replace("retry.maxHours=12\n", "")
                .replace("retry.jitterPercent=20\n", "")
                .replace("host.activationTimeoutSeconds=60\n", "")
                .replace("host.readinessPollSeconds=1\n", "")
                .replace("dns.propagationInitialDelaySeconds=15\n", "")
                .replace("dns.dohResolvers=https://cloudflare-dns.com/dns-query,https://dns.google/resolve\n", "");
        Files.writeString(file, oldConfig);

        AutoCertConfig migrated = AutoCertConfig.load(root);
        assertEquals(48, migrated.failureCooldownHours());
        assertFalse(migrated.debugMode());
        assertEquals(15, migrated.retryInitialMinutes());
        assertEquals(12, migrated.retryMaxHours());
        assertEquals(20, migrated.retryJitterPercent());
        assertEquals(60, migrated.hostActivationTimeoutSeconds());
        assertEquals(1, migrated.hostReadinessPollSeconds());
        assertEquals(15, migrated.dnsPropagationInitialDelaySeconds());
        assertEquals(2, migrated.dnsDohResolvers().size());
    }

    @Test void rejectsArbitraryOrIncompleteDohResolverConfiguration() throws Exception {
        assertConfigRejected(text -> text.replace(
                        "https://cloudflare-dns.com/dns-query,https://dns.google/resolve",
                        "https://resolver.evil.example/dns-query,https://dns.google/resolve"),
                "dns.dohResolvers");
        assertConfigRejected(text -> text.replace(
                        "https://cloudflare-dns.com/dns-query,https://dns.google/resolve",
                        "https://dns.google/resolve"),
                "dns.dohResolvers");
    }

    @Test void rejectsUnsafeRetryBounds() throws Exception {
        assertConfigRejected(text -> text.replace("retry.initialMinutes=15", "retry.initialMinutes=0"),
                "retry.initialMinutes");
        assertConfigRejected(text -> text.replace("retry.maxHours=12", "retry.maxHours=49"),
                "retry.maxHours");
        assertConfigRejected(text -> text.replace("retry.jitterPercent=20", "retry.jitterPercent=51"),
                "retry.jitterPercent");
    }

    @Test void rejectsSwappedAcmeEnvironmentsAndHostsOutsideWildcardDepth() throws Exception {
        assertConfigRejected(text -> text
                        .replace("https://acme-staging-v02.api.letsencrypt.org/directory", "TEMP_DIRECTORY")
                        .replace("https://acme-v02.api.letsencrypt.org/directory",
                                "https://acme-staging-v02.api.letsencrypt.org/directory")
                        .replace("TEMP_DIRECTORY", "https://acme-v02.api.letsencrypt.org/directory"),
                "staging/production");
        assertConfigRejected(text -> text.replace("play.maicraft.tech", "play.eu.maicraft.tech"),
                "exactly one label");
    }

    @Test void acceptsAConsistentOperatorConfiguredTopology() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        String custom = Files.readString(file)
                .replace("admin@maicraft.tech", "ops@example.net")
                .replace("maicraft.tech", "example.net")
                .replace("hm.example.net:25598", "origin.example.net:25565")
                .replace("hm.example.net:25809", "packs.example.net:24443");
        Files.writeString(file, custom);

        AutoCertConfig cfg = AutoCertConfig.load(root);

        assertEquals("example.net", cfg.zoneName());
        assertEquals(List.of("example.net", "*.example.net"), cfg.domains());
        assertEquals("origin.example.net:25565", cfg.minecraftSrvTarget());
        assertEquals("packs.example.net:24443", cfg.modpackEndpoint());
    }

    private void assertConfigRejected(UnaryOperator<String> mutation, String expectedMessage) throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        String original = Files.readString(file);
        Files.writeString(file, mutation.apply(original));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> AutoCertConfig.load(root));
        assertTrue(failure.getMessage().contains(expectedMessage));
        Files.writeString(file, original);
    }
}
