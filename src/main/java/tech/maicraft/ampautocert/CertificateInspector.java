package tech.maicraft.ampautocert;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Reads just the leaf certificate of AutoModpack's PEM full chain. No mutation. */
public final class CertificateInspector {
    private static final int MAX_CHAIN_BYTES = 512 * 1024;
    private CertificateInspector() { }

    public static Optional<Instant> expiresAt(Path fullChain) throws IOException, CertificateException {
        Optional<X509Certificate> leaf = leaf(fullChain);
        return leaf.map(certificate -> certificate.getNotAfter().toInstant());
    }

    public static Optional<String> fingerprint(Path fullChain) throws IOException, CertificateException {
        Optional<X509Certificate> leaf = leaf(fullChain);
        if (leaf.isEmpty()) return Optional.empty();
        try {
            return Optional.of(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(leaf.get().getEncoded())));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    public static Optional<Summary> summary(Path fullChain) throws IOException, CertificateException {
        Optional<X509Certificate> leaf = leaf(fullChain);
        if (leaf.isEmpty()) return Optional.empty();
        List<String> dnsSans = new ArrayList<>();
        var alternativeNames = leaf.get().getSubjectAlternativeNames();
        if (alternativeNames != null) {
            for (List<?> name : alternativeNames) {
                if (name.size() >= 2 && Integer.valueOf(2).equals(name.get(0)) && name.get(1) instanceof String dns) {
                    dnsSans.add(dns.toLowerCase());
                }
            }
        }
        dnsSans.sort(Comparator.naturalOrder());
        return Optional.of(new Summary(leaf.get().getNotAfter().toInstant(), List.copyOf(dnsSans)));
    }

    public record Summary(Instant expiresAt, List<String> dnsSans) { }

    private static Optional<X509Certificate> leaf(Path fullChain) throws IOException, CertificateException {
        if (!Files.isRegularFile(fullChain, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        byte[] bytes;
        try (InputStream input = Files.newInputStream(fullChain, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_CHAIN_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_CHAIN_BYTES) {
            throw new IOException("AutoModpack certificate chain has an invalid size");
        }
        var certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(bytes));
        return Optional.of(certificate);
    }
}
