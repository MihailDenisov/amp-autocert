package tech.maicraft.ampautocert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;

/** Read-only structural and permission checks for the fixed AutoModpack credential target. */
final class CredentialTargetInspector {
    private CredentialTargetInspector() { }

    static void inspect(Path serverRoot, boolean allowInsecurePermissions) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        CertificatePaths paths = CertificatePaths.inside(root);
        boolean certificateExists = Files.exists(paths.fullChain(), LinkOption.NOFOLLOW_LINKS);
        boolean keyExists = Files.exists(paths.privateKey(), LinkOption.NOFOLLOW_LINKS);
        if (certificateExists != keyExists) {
            throw new IOException("Existing AutoModpack certificate/key pair is incomplete");
        }
        if (certificateExists && (!Files.isRegularFile(paths.fullChain(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(paths.privateKey(), LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Existing AutoModpack credentials must be regular files");
        }
        if (certificateExists) {
            auditFile(paths.fullChain(), allowInsecurePermissions);
            auditFile(paths.privateKey(), allowInsecurePermissions);
        }

        Path current = root;
        auditDirectory(current, allowInsecurePermissions);
        for (Path part : root.relativize(paths.directory().resolve("amp-autocert/deployment"))) {
            current = current.resolve(part);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(current)) {
                throw new IOException("AutoModpack credential target must not contain symbolic links");
            }
            auditDirectory(current, allowInsecurePermissions);
        }

        Path writable = paths.directory();
        while (writable != null && Files.notExists(writable, LinkOption.NOFOLLOW_LINKS)) {
            writable = writable.getParent();
        }
        if (writable == null || !writable.startsWith(root) || !Files.isDirectory(writable,
                LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(writable)) {
            throw new IOException("AutoModpack credential target is not writable by the server process");
        }
    }

    private static void auditDirectory(Path directory, boolean allowInsecurePermissions) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("AutoModpack credential ancestor must be a directory");
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory,
                    LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("AutoModpack credential directories must not be writable by group or others");
            }
            return;
        }
        auditAcl(directory, allowInsecurePermissions, false);
    }

    private static void auditFile(Path file, boolean allowInsecurePermissions) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file,
                    LinkOption.NOFOLLOW_LINKS);
            boolean sharedRead = permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);
            boolean sharedWrite = permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
            if (sharedWrite || (!allowInsecurePermissions && sharedRead)) {
                throw new IOException("AutoModpack credential file permissions are too broad");
            }
            return;
        }
        auditAcl(file, allowInsecurePermissions, true);
    }

    static void auditAcl(Path path, boolean allowInsecurePermissions, boolean file) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Cannot verify private AutoModpack credential permissions");
        }
        UserPrincipal owner = view.getOwner();
        Set<AclEntryPermission> writePermissions = EnumSet.of(AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA, AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.DELETE,
                AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER,
                AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY,
                AclEntryPermission.DELETE_CHILD);
        for (var entry : view.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || entry.principal().equals(owner)) continue;
            if ((!allowInsecurePermissions && file) || entry.permissions().stream().anyMatch(writePermissions::contains)) {
                throw new IOException("AutoModpack credential ACL grants unsafe non-owner access");
            }
        }
    }
}
