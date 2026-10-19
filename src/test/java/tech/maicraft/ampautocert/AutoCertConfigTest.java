package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class AutoCertConfigTest {
    @TempDir Path root;

    @Test void createsSmallDisabledStagingDefaultsAndDerivesProviderValues() throws Exception {
        AutoCertConfig cfg = AutoCertConfig.load(root);
        assertFalse(cfg.enabled());
        assertTrue(cfg.staging());
        assertEquals("play.mydomain.com", cfg.originHostname());
        assertEquals("mydomain.com", cfg.zoneName());
        assertEquals(List.of("mydomain.com", "*.mydomain.com"), cfg.domains());
        assertEquals("_acme-challenge.mydomain.com", cfg.challengeRecord());
        assertEquals("https://api.cloudflare.com/client/v4/", cfg.cloudflareApiRoot().toString());
        assertEquals(2, cfg.dnsDohResolvers().size());
        assertTrue(cfg.legacyMinecraftSrvTarget().isEmpty());
        assertTrue(cfg.legacyAutoModpackEndpoint().isEmpty());
        assertTrue(cfg.migrationWarnings().isEmpty());
        String file = Files.readString(root.resolve("automodpack/amp-autocert.properties"));
        assertFalse(file.contains("acme.domains="));
        assertFalse(file.contains("automodpack.endpoint="));
        assertFalse(file.contains("minecraft.srvTarget="));
        assertFalse(file.contains("cloudflare.apiRoot="));
        assertFalse(file.contains("AMP_AUTOCERT_CF_API_TOKEN="));
    }

    @Test void migratesV010AliasesWithoutTrustingDuplicatedRouting() throws Exception {
        AutoCertConfig.load(root);
        Path file = configFile();
        String old = Files.readString(file)
                .replace("origin.hostname=play.mydomain.com", "minecraft.hostname=play.mydomain.com")
                .replace("cloudflare.zoneName=mydomain.com", "dns.zoneName=mydomain.com")
                + "\nacme.domains=mydomain.com,*.mydomain.com"
                + "\ndns.challengeRecord=_acme-challenge.mydomain.com"
                + "\nminecraft.srvTarget=host.mydomain.com:25565"
                + "\nautomodpack.endpoint=packs.mydomain.com:25570"
                + "\ncloudflare.apiRoot=https://api.cloudflare.com/client/v4/"
                + "\nacme.stagingDirectory=https://acme-staging-v02.api.letsencrypt.org/directory"
                + "\nacme.productionDirectory=https://acme-v02.api.letsencrypt.org/directory"
                + "\ndns.dohResolvers=https://cloudflare-dns.com/dns-query,https://dns.google/resolve\n";
        Files.writeString(file, old);

        AutoCertConfig migrated = AutoCertConfig.load(root);
        assertEquals("play.mydomain.com", migrated.originHostname());
        assertEquals("packs.mydomain.com:25570", migrated.legacyAutoModpackEndpoint().orElseThrow());
        assertTrue(migrated.migrationWarnings().stream().anyMatch(value -> value.contains("minecraft.hostname")));
        assertTrue(migrated.migrationWarnings().stream().anyMatch(value -> value.contains("automodpack.endpoint")));
    }

    @Test void failsClosedOnConflictingAliasesOrDerivedValues() throws Exception {
        assertConfigRejected(text -> text + "\nminecraft.hostname=other.mydomain.com\n", "conflicts");
        assertConfigRejected(text -> text + "\nacme.domains=mydomain.com,evil.example\n", "conflicts");
        assertConfigRejected(text -> text + "\ncloudflare.apiRoot=https://example.invalid/\n", "pinned provider");
    }

    @Test void acceptsApexOrOneLabelAndRejectsDeeperNames() throws Exception {
        AutoCertConfig.load(root);
        Path file = configFile();
        Files.writeString(file, Files.readString(file).replace("play.mydomain.com", "mydomain.com"));
        assertEquals("mydomain.com", AutoCertConfig.load(root).originHostname());
        Files.writeString(file, Files.readString(file).replace("origin.hostname=mydomain.com",
                "origin.hostname=play.eu.mydomain.com"));
        assertThrows(IllegalArgumentException.class, () -> AutoCertConfig.load(root));
    }

    @Test void validatesEnabledCredentialsAndOperationalBounds() throws Exception {
        assertConfigRejected(text -> text.replace("enabled=false", "enabled=true"), "cloudflare.zoneId");
        assertConfigRejected(text -> text.replace("drift.pollSeconds=60", "drift.pollSeconds=5"),
                "drift.pollSeconds");
        assertConfigRejected(text -> text.replace("debugmode=false", "debugmode=yes"), "debugmode");
        assertConfigRejected(text -> text.replace("host.activationTimeoutSeconds=60",
                        "host.activationTimeoutSeconds=10")
                .replace("host.readinessPollSeconds=1", "host.readinessPollSeconds=10"), "less than");
    }

    private Path configFile() { return root.resolve("automodpack/amp-autocert.properties"); }

    private void assertConfigRejected(UnaryOperator<String> mutation, String expected) throws Exception {
        AutoCertConfig.load(root);
        Path file = configFile();
        String original = Files.readString(file);
        Files.writeString(file, mutation.apply(original));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AutoCertConfig.load(root));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        Files.writeString(file, original);
    }
}
