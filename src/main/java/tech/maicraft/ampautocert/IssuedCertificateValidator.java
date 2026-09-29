package tech.maicraft.ampautocert;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure validation boundary. This class never writes or activates certificate material. */
public final class IssuedCertificateValidator {
    private static final int MAX_CHAIN_BYTES = 512 * 1024;
    private static final int MAX_PRIVATE_KEY_BYTES = 16 * 1024;
    private static final String TLS_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final byte[] KEY_MATCH_CHALLENGE =
            "amp-autocert certificate key match".getBytes(StandardCharsets.US_ASCII);

    private final Clock clock;
    private final Set<TrustAnchor> trustAnchors;

    IssuedCertificateValidator(Clock clock, Set<TrustAnchor> trustAnchors) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.trustAnchors = Set.copyOf(trustAnchors);
        if (this.trustAnchors.isEmpty()) throw new IllegalArgumentException("At least one trust anchor is required");
    }

    /** Uses the same default trust roots as the running JVM. */
    public static IssuedCertificateValidator usingSystemTrust(Clock clock) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        LinkedHashSet<TrustAnchor> anchors = new LinkedHashSet<>();
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                    anchors.add(new TrustAnchor(issuer, null));
                }
            }
        }
        if (anchors.isEmpty()) throw new GeneralSecurityException("JVM default trust store has no X.509 roots");
        return new IssuedCertificateValidator(clock, anchors);
    }

    /** Validates material intended for the live AutoModpack credential files. */
    public ValidationResult validateForDeployment(AcmeIssuanceService.IssuedMaterial material,
                                                  AutoCertConfig config)
            throws IOException, GeneralSecurityException {
        if (material.environment() != AcmeEnvironment.PRODUCTION) {
            throw new CertificateException("Refusing to deploy a non-production ACME certificate");
        }
        if (material.domains().size() != config.domains().size()
                || !Set.copyOf(material.domains()).equals(Set.copyOf(config.domains()))) {
            throw new CertificateException("Issued material domain set does not match configuration");
        }
        return validateConfiguredMaterial(material, config, true);
    }

    /** Structurally inspects a staging probe in memory; this result is never trusted for deployment. */
    public ValidationResult inspectStagingProbe(AcmeIssuanceService.IssuedMaterial material,
                                               AutoCertConfig config)
            throws IOException, GeneralSecurityException {
        if (material.environment() != AcmeEnvironment.STAGING) {
            throw new CertificateException("Staging probe returned non-staging ACME material");
        }
        return validateConfiguredMaterial(material, config, false);
    }

    private ValidationResult validateConfiguredMaterial(AcmeIssuanceService.IssuedMaterial material,
                                                        AutoCertConfig config, boolean requirePkix)
            throws IOException, GeneralSecurityException {
        if (material.domains().size() != config.domains().size()
                || !Set.copyOf(material.domains()).equals(Set.copyOf(config.domains()))) {
            throw new CertificateException("Issued material domain set does not match configuration");
        }
        String endpointHost = config.modpackEndpoint().substring(0, config.modpackEndpoint().lastIndexOf(':'));
        return validate(material, Set.copyOf(config.domains()),
                List.of(config.minecraftHostname(), endpointHost), requirePkix);
    }

    private ValidationResult validate(AcmeIssuanceService.IssuedMaterial material,
                                      Set<String> expectedDnsSans, List<String> requiredHosts,
                                      boolean requirePkix)
            throws IOException, GeneralSecurityException {
        byte[] chainBytes = material.fullChainPem().getBytes(StandardCharsets.US_ASCII);
        if (chainBytes.length == 0 || chainBytes.length > MAX_CHAIN_BYTES) {
            throw new CertificateException("Issued certificate chain has an invalid size");
        }
        byte[] keyBytes = material.privateKeyPkcs8();
        if (keyBytes.length == 0 || keyBytes.length > MAX_PRIVATE_KEY_BYTES) {
            throw new GeneralSecurityException("Issued private key has an invalid size");
        }

        List<X509Certificate> chain = parseChain(chainBytes);
        PrivateKey privateKey;
        try {
            privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException invalidKey) {
            throw new GeneralSecurityException("Issued private key is not a valid EC PKCS#8 key", invalidKey);
        }
        if (!(privateKey instanceof ECKey ecKey) || ecKey.getParams().getOrder().bitLength() < 256) {
            throw new GeneralSecurityException("Issued private key does not meet the EC security requirement");
        }

        X509Certificate leaf = requireMatchingLeafFirst(chain, privateKey);
        validateChainOrder(chain);
        Instant now = clock.instant();
        leaf.checkValidity(Date.from(now));
        if (leaf.getBasicConstraints() >= 0) throw new CertificateException("Issued leaf is a CA certificate");
        boolean[] keyUsage = leaf.getKeyUsage();
        if (keyUsage != null && (keyUsage.length == 0 || !keyUsage[0])) {
            throw new CertificateException("Issued leaf does not permit digital signatures");
        }
        List<String> extendedUsage = leaf.getExtendedKeyUsage();
        if (extendedUsage != null && !extendedUsage.contains(TLS_SERVER_AUTH)) {
            throw new CertificateException("Issued leaf is not valid for TLS server authentication");
        }

        Set<String> dnsSans = dnsSans(leaf);
        if (!dnsSans.equals(expectedDnsSans)) {
            throw new CertificateException("Issued certificate DNS SANs do not match configuration");
        }
        for (String host : requiredHosts) {
            if (!coversHostname(dnsSans, normalizeDnsName(host))) {
                throw new CertificateException("Issued certificate does not cover a required server hostname");
            }
        }

        if (requirePkix) validatePkix(leaf, chain, now);
        return new ValidationResult(leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant(),
                List.copyOf(dnsSans));
    }

    private static List<X509Certificate> parseChain(byte[] pem) throws CertificateException {
        Collection<? extends java.security.cert.Certificate> parsed = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem));
        if (parsed.isEmpty() || parsed.size() > 10) {
            throw new CertificateException("Issued certificate chain has an invalid certificate count");
        }
        ArrayList<X509Certificate> result = new ArrayList<>(parsed.size());
        for (var certificate : parsed) {
            if (!(certificate instanceof X509Certificate x509)) {
                throw new CertificateException("Issued chain contains a non-X.509 certificate");
            }
            result.add(x509);
        }
        return List.copyOf(result);
    }

    private static X509Certificate requireMatchingLeafFirst(List<X509Certificate> chain, PrivateKey privateKey)
            throws GeneralSecurityException {
        if (!keyMatches(chain.get(0), privateKey)) {
            throw new GeneralSecurityException("Issued private key does not match the first certificate");
        }
        for (int index = 1; index < chain.size(); index++) {
            if (keyMatches(chain.get(index), privateKey)) {
                throw new GeneralSecurityException("Issued private key matches more than one certificate");
            }
        }
        return chain.get(0);
    }

    private static boolean keyMatches(X509Certificate certificate, PrivateKey privateKey)
            throws GeneralSecurityException {
        if (!"EC".equalsIgnoreCase(certificate.getPublicKey().getAlgorithm())) return false;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(KEY_MATCH_CHALLENGE);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(KEY_MATCH_CHALLENGE);
        return verifier.verify(signature);
    }

    private static void validateChainOrder(List<X509Certificate> chain) throws GeneralSecurityException {
        for (int index = 0; index + 1 < chain.size(); index++) {
            X509Certificate certificate = chain.get(index);
            X509Certificate issuer = chain.get(index + 1);
            if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                throw new CertificateException("Issued certificate chain is not leaf-first issuer order");
            }
            certificate.verify(issuer.getPublicKey());
        }
    }

    private static Set<String> dnsSans(X509Certificate leaf) throws CertificateException {
        Collection<List<?>> values = leaf.getSubjectAlternativeNames();
        if (values == null) throw new CertificateException("Issued certificate has no subject alternative names");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (List<?> value : values) {
            if (value.size() >= 2 && Integer.valueOf(2).equals(value.get(0)) && value.get(1) instanceof String dns) {
                names.add(normalizeDnsName(dns));
            }
        }
        if (names.isEmpty()) throw new CertificateException("Issued certificate has no DNS subject alternative names");
        return Set.copyOf(names);
    }

    private static String normalizeDnsName(String value) throws CertificateException {
        try {
            String candidate = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
            boolean wildcard = candidate.startsWith("*.");
            if (wildcard) candidate = candidate.substring(2);
            if (candidate.isEmpty() || candidate.indexOf('*') >= 0) {
                throw new IllegalArgumentException("Invalid wildcard placement");
            }
            String normalized = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            return wildcard ? "*." + normalized : normalized;
        } catch (IllegalArgumentException invalid) {
            throw new CertificateException("Issued certificate contains an invalid DNS name", invalid);
        }
    }

    private static boolean coversHostname(Set<String> dnsSans, String hostname) {
        if (dnsSans.contains(hostname)) return true;
        int firstDot = hostname.indexOf('.');
        if (firstDot <= 0) return false;
        return dnsSans.contains("*." + hostname.substring(firstDot + 1));
    }

    private void validatePkix(X509Certificate leaf, List<X509Certificate> chain, Instant now)
            throws GeneralSecurityException {
        X509CertSelector selector = new X509CertSelector();
        selector.setCertificate(leaf);
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(trustAnchors, selector);
        parameters.setDate(Date.from(now));
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
        CertPathBuilder.getInstance("PKIX").build(parameters);
    }

    public record ValidationResult(Instant notBefore, Instant notAfter, List<String> dnsSans) {
        public ValidationResult { dnsSans = List.copyOf(dnsSans); }
    }
}
