package tech.maicraft.ampautocert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Credentials are resolved only at use time and NEVER logged or written into synced config. */
public final class CloudflareCredentialSource {
    public static final String ENV_NAME = "AMP_AUTOCERT_CF_API_TOKEN";
    private static final int MAX_TOKEN_BYTES = 8 * 1024;
    private CloudflareCredentialSource() { }

    public static boolean available(Path serverRoot) {
        return available(serverRoot, false);
    }

    public static boolean available(Path serverRoot, boolean allowInsecureFilePermissions) {
        String env = System.getenv(ENV_NAME);
        if (env != null && !env.isBlank()) return true;
        try {
            Path file = secretPath(serverRoot);
            validateSecretFile(serverRoot, file, allowInsecureFilePermissions);
            return Files.size(file) > 0;
        } catch (IOException unsafeOrMissing) {
            return false;
        }
    }

    public static String load(Path serverRoot) throws IOException {
        return load(serverRoot, false);
    }

    public static String load(Path serverRoot, boolean allowInsecureFilePermissions) throws IOException {
        String env = System.getenv(ENV_NAME);
        if (env != null && !env.isBlank()) return env.trim();
        Path path = secretPath(serverRoot);
        validateSecretFile(serverRoot, path, allowInsecureFilePermissions);
        String token = readWithoutFollowingLinks(path);
        if (token.isBlank()) throw new IOException("Empty Cloudflare API token file");
        return token;
    }

    public static Path secretPath(Path serverRoot) {
        Path root = serverRoot.toAbsolutePath().normalize();
        return root.resolve("automodpack/credentials/amp-autocert-cloudflare-token.txt").normalize();
    }

    private static void validateSecretFile(Path serverRoot, Path file,
                                           boolean allowInsecureFilePermissions) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        if (!file.startsWith(root)) throw new IOException("Cloudflare credential path escapes the server directory");
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Cloudflare credential path must not contain symbolic links");
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Set " + ENV_NAME + " or provide a private credential file");
        }
        // Compatibility mode permits panel-managed read bits (commonly 0644), but never
        // permits another UID to replace the token or a credential-path component.
        try {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            boolean sharedRead = mode.contains(PosixFilePermission.GROUP_READ)
                    || mode.contains(PosixFilePermission.OTHERS_READ);
            boolean sharedWrite = mode.contains(PosixFilePermission.GROUP_WRITE)
                    || mode.contains(PosixFilePermission.OTHERS_WRITE);
            if (sharedWrite || (!allowInsecureFilePermissions && sharedRead)) {
                throw new IOException("Cloudflare token file permissions are too broad; require chmod 600");
            }
            Path parent = file.getParent();
            while (parent != null && parent.startsWith(root)) {
                if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                    Set<PosixFilePermission> parentMode = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS);
                    if (parentMode.contains(PosixFilePermission.GROUP_WRITE)
                            || parentMode.contains(PosixFilePermission.OTHERS_WRITE)) {
                        throw new IOException("Cloudflare credential directories must not be writable by group or others");
                    }
                }
                if (parent.equals(root)) break;
                parent = parent.getParent();
            }
        } catch (UnsupportedOperationException windowsFilesystem) {
            if (!allowInsecureFilePermissions) {
                throw new IOException("Cannot verify token file permissions on this filesystem; use " + ENV_NAME);
            }
        }
    }

    private static String readWithoutFollowingLinks(Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > MAX_TOKEN_BYTES) throw new IOException("Cloudflare API token file is unexpectedly large");
            ByteBuffer bytes = ByteBuffer.allocate(MAX_TOKEN_BYTES + 1);
            while (channel.read(bytes) >= 0 && bytes.hasRemaining()) { }
            if (!bytes.hasRemaining()) throw new IOException("Cloudflare API token file is unexpectedly large");
            bytes.flip();
            return StandardCharsets.UTF_8.decode(bytes).toString().strip();
        }
    }
}
