package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareCredentialSourceTest {
    @TempDir Path root;

    @Test void credentialPathIsPrivateAndInsideServerRoot() {
        Path expected = root.toAbsolutePath().normalize()
                .resolve("automodpack/credentials/amp-autocert-cloudflare-token.txt");
        assertEquals(expected, CloudflareCredentialSource.secretPath(root));
    }

    @Test void missingCredentialDoesNotExposeAPathOrSecret() {
        if (System.getenv(CloudflareCredentialSource.ENV_NAME) != null) return;
        IOException failure = assertThrows(IOException.class, () -> CloudflareCredentialSource.load(root));
        assertTrue(failure.getMessage().contains(CloudflareCredentialSource.ENV_NAME));
        assertFalse(CloudflareCredentialSource.available(root));
    }

    @Test void rejectsSymbolicLinkInCredentialPathWhenSupported() throws Exception {
        if (System.getenv(CloudflareCredentialSource.ENV_NAME) != null) return;
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path automodpack = Files.createDirectory(root.resolve("automodpack"));
        try {
            Files.createSymbolicLink(automodpack.resolve("credentials"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        Files.writeString(outside.resolve("amp-autocert-cloudflare-token.txt"), "not-a-real-token");
        IOException failure = assertThrows(IOException.class, () -> CloudflareCredentialSource.load(root));
        assertTrue(failure.getMessage().contains("symbolic links"));
        assertFalse(failure.getMessage().contains("not-a-real-token"));
    }

    @Test void explicitCompatibilityModeReadsPanelManagedRegularFile() throws Exception {
        if (System.getenv(CloudflareCredentialSource.ENV_NAME) != null) return;
        Path token = CloudflareCredentialSource.secretPath(root);
        Files.createDirectories(token.getParent());
        Files.writeString(token, "not-a-real-token\n");

        assertTrue(CloudflareCredentialSource.available(root, true));
        assertEquals("not-a-real-token", CloudflareCredentialSource.load(root, true));
    }

    @Test void compatibilityModeStillRejectsSymbolicLinkCredentialFileWhenSupported() throws Exception {
        if (System.getenv(CloudflareCredentialSource.ENV_NAME) != null) return;
        Path token = CloudflareCredentialSource.secretPath(root);
        Files.createDirectories(token.getParent());
        Path outside = Files.writeString(root.resolve("outside-token.txt"), "not-a-real-token");
        try {
            Files.createSymbolicLink(token, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        IOException failure = assertThrows(IOException.class,
                () -> CloudflareCredentialSource.load(root, true));
        assertTrue(failure.getMessage().contains("symbolic links"));
        assertFalse(failure.getMessage().contains("not-a-real-token"));
    }

    @Test void compatibilityModePermitsSharedReadButNotSharedWriteOnPosix() throws Exception {
        if (System.getenv(CloudflareCredentialSource.ENV_NAME) != null) return;
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) == null) return;
        Path token = CloudflareCredentialSource.secretPath(root);
        Files.createDirectories(token.getParent());
        Files.writeString(token, "not-a-real-token");
        Files.setPosixFilePermissions(token, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        assertEquals("not-a-real-token", CloudflareCredentialSource.load(root, true));

        Files.setPosixFilePermissions(token, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE));
        IOException failure = assertThrows(IOException.class,
                () -> CloudflareCredentialSource.load(root, true));
        assertTrue(failure.getMessage().contains("permissions are too broad"));
        assertFalse(failure.getMessage().contains("not-a-real-token"));

        Files.setPosixFilePermissions(token, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.setPosixFilePermissions(token.getParent(), Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_WRITE));
        IOException directoryFailure = assertThrows(IOException.class,
                () -> CloudflareCredentialSource.load(root, true));
        assertTrue(directoryFailure.getMessage().contains("directories"));
    }
}
