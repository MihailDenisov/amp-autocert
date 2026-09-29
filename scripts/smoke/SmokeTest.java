import tech.maicraft.ampautocert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.Optional;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        var due = RenewalPolicy.decide(Optional.of(now.plus(Duration.ofDays(30))), now, Duration.ofDays(30));
        if (due != RenewalPolicy.Decision.DUE) throw new AssertionError("renewal boundary");
        if (RenewalPolicy.decide(Optional.empty(), now, Duration.ofDays(30)) != RenewalPolicy.Decision.MISSING)
            throw new AssertionError("missing cert");
        var root = Files.createTempDirectory("amp-autocert-smoke-");
        try {
            var cfg = AutoCertConfig.load(root);
            if (cfg.enabled() || !cfg.staging()) throw new AssertionError("safe defaults");
            Path expectedCertificate = root.resolve("automodpack").resolve("credentials").resolve("certificate.crt");
            if (!CertificatePaths.inside(root).fullChain().equals(expectedCertificate))
                throw new AssertionError("wrong AutoModpack path");
            if (CertificateInspector.expiresAt(CertificatePaths.inside(root).fullChain()).isPresent())
                throw new AssertionError("should not invent certificate");
        } finally {
            Files.deleteIfExists(root.resolve("automodpack/amp-autocert.properties"));
            Files.deleteIfExists(root.resolve("automodpack"));
            Files.deleteIfExists(root);
        }
        System.out.println("PASS: pure Java 21 compilation, renewal boundaries, safe default config, credentials paths");
    }
}
