package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CertificatePathsTest {
    @TempDir Path root;

    @Test void usesVerifiedAutoModpackFivePaths() throws Exception {
        CertificatePaths paths = CertificatePaths.inside(root);
        Path credentials = root.toAbsolutePath().normalize().resolve("automodpack/credentials");
        assertEquals(credentials.resolve("certificate.crt"), paths.fullChain());
        assertEquals(credentials.resolve("private-key.pem"), paths.privateKey());
        assertEquals(credentials.resolve("amp-autocert/state"), paths.accountState());
    }

    @Test void rejectsSymlinkedCredentialsDirectoryWhenSupported() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path automodpack = Files.createDirectory(root.resolve("automodpack"));
        try {
            Files.createSymbolicLink(automodpack.resolve("credentials"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        IOException failure = assertThrows(IOException.class, () -> CertificatePaths.inside(root));
        assertTrue(failure.getMessage().contains("symbolic links"));
    }

    @Test void rejectsSymlinkedCertificateLeafWhenSupported() throws Exception {
        Path credentials = Files.createDirectories(root.resolve("automodpack/credentials"));
        Path outside = Files.writeString(root.resolve("outside.crt"), "not a certificate");
        try {
            Files.createSymbolicLink(credentials.resolve("certificate.crt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        assertThrows(IOException.class, () -> CertificatePaths.inside(root));
    }

    @Test void rejectsSymlinkedNestedStateWhenSupported() throws Exception {
        Path privateRoot = Files.createDirectories(root.resolve("automodpack/credentials/amp-autocert"));
        Path outside = Files.createDirectory(root.resolve("outside-state"));
        try {
            Files.createSymbolicLink(privateRoot.resolve("state"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        assertThrows(IOException.class, () -> CertificatePaths.inside(root));
    }
}
