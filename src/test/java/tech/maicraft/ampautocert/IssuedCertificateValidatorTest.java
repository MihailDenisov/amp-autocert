package tech.maicraft.ampautocert;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IssuedCertificateValidatorTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    @TempDir Path root;

    @Test void acceptsTrustedProductionChainExactSansHostsAndMatchingPkcs8Key() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));

        var result = fixture.validator().validateForDeployment(fixture.material(AcmeEnvironment.PRODUCTION), config());

        assertEquals(Instant.parse("2026-12-26T12:00:00Z"), result.notAfter());
        assertEquals(Set.of("maicraft.tech", "*.maicraft.tech"), Set.copyOf(result.dnsSans()));
    }

    @Test void refusesStagingMaterialBeforePkixOrDeployment() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));

        CertificateException failure = assertThrows(CertificateException.class,
                () -> fixture.validator().validateForDeployment(fixture.material(AcmeEnvironment.STAGING), config()));

        assertTrue(failure.getMessage().contains("non-production"));
    }

    @Test void inspectsStagingProbeStructurallyWithoutTrustingItForDeployment() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));
        Fixture unrelated = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));
        var validator = new IssuedCertificateValidator(Clock.fixed(NOW, ZoneOffset.UTC),
                Set.of(new TrustAnchor(unrelated.rootCertificate, null)));

        var result = validator.inspectStagingProbe(
                fixture.material(AcmeEnvironment.STAGING), stagingConfig());

        assertEquals(Set.of("maicraft.tech", "*.maicraft.tech"), Set.copyOf(result.dnsSans()));
        assertThrows(CertificateException.class, () -> validator.inspectStagingProbe(
                fixture.material(AcmeEnvironment.PRODUCTION), stagingConfig()));
    }

    @Test void rejectsUntrustedChain() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));
        Fixture unrelated = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));
        var validator = new IssuedCertificateValidator(Clock.fixed(NOW, ZoneOffset.UTC),
                Set.of(new TrustAnchor(unrelated.rootCertificate, null)));

        assertThrows(java.security.GeneralSecurityException.class,
                () -> validator.validateForDeployment(fixture.material(AcmeEnvironment.PRODUCTION), config()));
    }

    @Test void rejectsUnexpectedSansAndMissingHostnameCoverage() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.evil.example"));
        var material = new AcmeIssuanceService.IssuedMaterial(AcmeEnvironment.PRODUCTION,
                fixture.fullChainPem, fixture.leafKey.getPrivate().getEncoded(),
                List.of("maicraft.tech", "*.maicraft.tech"));

        CertificateException failure = assertThrows(CertificateException.class,
                () -> fixture.validator().validateForDeployment(material, config()));

        assertTrue(failure.getMessage().contains("SANs"));
    }

    @Test void rejectsMismatchedPrivateKey() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));
        KeyPair unrelated = ecKeyPair();
        var material = new AcmeIssuanceService.IssuedMaterial(AcmeEnvironment.PRODUCTION,
                fixture.fullChainPem, unrelated.getPrivate().getEncoded(), fixture.domains);

        java.security.GeneralSecurityException failure = assertThrows(java.security.GeneralSecurityException.class,
                () -> fixture.validator().validateForDeployment(material, config()));

        assertTrue(failure.getMessage().contains("does not match"));
    }

    @Test void rejectsRootFirstPemEvenWhenPkixCouldBuildTheChain() throws Exception {
        Fixture fixture = Fixture.valid(NOW, List.of("maicraft.tech", "*.maicraft.tech"));
        StringWriter reversed = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(reversed)) {
            writer.writeObject(fixture.rootCertificate);
            writer.writeObject(fixture.leafCertificate);
        }
        var material = new AcmeIssuanceService.IssuedMaterial(AcmeEnvironment.PRODUCTION,
                reversed.toString(), fixture.leafKey.getPrivate().getEncoded(), fixture.domains);

        java.security.GeneralSecurityException failure = assertThrows(java.security.GeneralSecurityException.class,
                () -> fixture.validator().validateForDeployment(material, config()));

        assertTrue(failure.getMessage().contains("first certificate"));
    }

    @Test void rejectsExpiredLeafAtInjectedClock() throws Exception {
        Fixture fixture = Fixture.create(NOW.minusSeconds(172800), NOW.minusSeconds(86400),
                List.of("maicraft.tech", "*.maicraft.tech"));

        assertThrows(CertificateException.class,
                () -> fixture.validator().validateForDeployment(fixture.material(AcmeEnvironment.PRODUCTION), config()));
    }

    private AutoCertConfig config() throws Exception {
        AutoCertConfig.load(root);
        Path file = root.resolve("automodpack/amp-autocert.properties");
        String configured = java.nio.file.Files.readString(file)
                .replace("enabled=false", "enabled=true")
                .replace("REPLACE_WITH_YOUR_CLOUDFLARE_ZONE_ID", "0123456789abcdef0123456789abcdef")
                .replace("acme.environment=staging", "acme.environment=production");
        java.nio.file.Files.writeString(file, configured);
        return AutoCertConfig.load(root);
    }

    private AutoCertConfig stagingConfig() throws Exception {
        return AutoCertConfig.load(root);
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static final class Fixture {
        private final X509Certificate rootCertificate;
        final X509Certificate leafCertificate;
        private final String fullChainPem;
        private final KeyPair leafKey;
        private final List<String> domains;

        private Fixture(X509Certificate rootCertificate, X509Certificate leafCertificate, String fullChainPem,
                        KeyPair leafKey, List<String> domains) {
            this.rootCertificate = rootCertificate;
            this.leafCertificate = leafCertificate;
            this.fullChainPem = fullChainPem;
            this.leafKey = leafKey;
            this.domains = List.copyOf(domains);
        }

        static Fixture valid(Instant now, List<String> domains) throws Exception {
            return create(now.minusSeconds(86400), now.plusSeconds(90L * 86400), domains);
        }

        static Fixture create(Instant notBefore, Instant notAfter, List<String> domains) throws Exception {
            KeyPair rootKey = ecKeyPair();
            KeyPair leafKey = ecKeyPair();
            X500Name rootName = new X500Name("CN=amp-autocert deterministic test root");
            var rootBuilder = new JcaX509v3CertificateBuilder(rootName, BigInteger.ONE,
                    Date.from(notBefore.minusSeconds(86400)), Date.from(notAfter.plusSeconds(86400)),
                    rootName, rootKey.getPublic());
            rootBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            rootBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            var rootHolder = rootBuilder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(rootKey.getPrivate()));
            X509Certificate rootCertificate = new JcaX509CertificateConverter().getCertificate(rootHolder);

            X500Name leafName = new X500Name("CN=unused.example");
            var leafBuilder = new JcaX509v3CertificateBuilder(rootName, BigInteger.TWO,
                    Date.from(notBefore), Date.from(notAfter), leafName, leafKey.getPublic());
            var extensions = new JcaX509ExtensionUtils();
            leafBuilder.addExtension(Extension.authorityKeyIdentifier, false,
                    extensions.createAuthorityKeyIdentifier(rootCertificate));
            leafBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensions.createSubjectKeyIdentifier(leafKey.getPublic()));
            leafBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            leafBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            leafBuilder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            leafBuilder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(domains.stream().map(name -> new GeneralName(GeneralName.dNSName, name))
                            .toArray(GeneralName[]::new)));
            var leafHolder = leafBuilder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(rootKey.getPrivate()));
            X509Certificate leafCertificate = new JcaX509CertificateConverter().getCertificate(leafHolder);

            StringWriter pem = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
                writer.writeObject(leafCertificate);
                writer.writeObject(rootCertificate);
            }
            return new Fixture(rootCertificate, leafCertificate, pem.toString(), leafKey, domains);
        }

        IssuedCertificateValidator validator() {
            return new IssuedCertificateValidator(Clock.fixed(NOW, ZoneOffset.UTC),
                    Set.of(new TrustAnchor(rootCertificate, null)));
        }

        AcmeIssuanceService.IssuedMaterial material(AcmeEnvironment environment) {
            return new AcmeIssuanceService.IssuedMaterial(environment, fullChainPem,
                    leafKey.getPrivate().getEncoded(), domains);
        }
    }
}
