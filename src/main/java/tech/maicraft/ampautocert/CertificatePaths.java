package tech.maicraft.ampautocert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Current AutoModpack documentation: fullchain PEM and private key in credentials/. */
public record CertificatePaths(Path directory, Path fullChain, Path privateKey, Path accountState) {
    public static CertificatePaths inside(Path serverRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        Path dir = root.resolve("automodpack/credentials").normalize();
        if (!dir.startsWith(root)) throw new IllegalArgumentException("Credential path escapes the server directory");
        Path fullChain = dir.resolve("certificate.crt");
        Path privateKey = dir.resolve("private-key.pem");
        Path accountState = dir.resolve("amp-autocert/state");
        rejectSymbolicLinks(root, fullChain);
        rejectSymbolicLinks(root, privateKey);
        rejectSymbolicLinks(root, accountState);
        return new CertificatePaths(dir, fullChain, privateKey, accountState);
    }

    private static void rejectSymbolicLinks(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("AutoModpack credential path must not contain symbolic links");
            }
        }
    }
}
