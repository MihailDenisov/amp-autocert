package tech.maicraft.ampautocert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Crash-recoverable two-file replacement. Activation is deliberately external: callers must
 * commit only after AutoModpack is confirmed ready; closing an unresolved deployment rolls back.
 */
public final class CertificateDeploymentService {
    private static final int MAX_CERTIFICATE_BYTES = 512 * 1024;
    private static final int MAX_PRIVATE_KEY_BYTES = 16 * 1024;
    private static final int MAX_JOURNAL_BYTES = 2048;
    private static final ConcurrentHashMap<Path, Semaphore> JVM_GATES = new ConcurrentHashMap<>();
    private static final DeploymentFaultInjector NO_FAULTS = point -> { };

    private final Path serverRoot;
    private final CertificatePaths certificatePaths;
    private final Path deploymentRoot;
    private final Path journalPath;
    private final boolean allowInsecurePermissions;
    private final MaterialValidator validator;
    private final Supplier<String> transactionIds;
    private final DeploymentFaultInjector faultInjector;

    public CertificateDeploymentService(Path serverRoot, IssuedCertificateValidator validator,
                                        AutoCertConfig config) throws IOException {
        this(serverRoot, material -> validator.validateForDeployment(material, config),
                config.allowInsecureDeploymentFilePermissions(),
                () -> UUID.randomUUID().toString(), NO_FAULTS);
    }

    CertificateDeploymentService(Path serverRoot, MaterialValidator validator,
                                 boolean allowInsecurePermissions, Supplier<String> transactionIds,
                                 DeploymentFaultInjector faultInjector) throws IOException {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.certificatePaths = CertificatePaths.inside(this.serverRoot);
        this.deploymentRoot = certificatePaths.directory().resolve("amp-autocert/deployment").normalize();
        if (!deploymentRoot.startsWith(this.serverRoot)) {
            throw new IOException("Certificate deployment path escapes the server directory");
        }
        this.journalPath = deploymentRoot.resolve("pending-deployment.state");
        this.allowInsecurePermissions = allowInsecurePermissions;
        this.validator = validator;
        this.transactionIds = transactionIds;
        this.faultInjector = faultInjector;
    }

    /** Validates first, then installs a pending pair. Close without commit to restore the old pair. */
    public PendingDeployment prepare(AcmeIssuanceService.IssuedMaterial material)
            throws IOException, GeneralSecurityException, InterruptedException {
        validator.validate(material); // No filesystem mutation may precede this call.
        DeploymentLock lock = acquireLock();
        Journal journal = null;
        Path transactionDirectory = null;
        try {
            RecoveryResult priorRecovery = recoverLocked();
            if (priorRecovery.credentialsChanged()) {
                throw new IOException("Previous certificate rollback still requires host reconciliation");
            }
            requireConsistentExistingPair();
            boolean hadOldPair = Files.exists(certificatePaths.fullChain(), LinkOption.NOFOLLOW_LINKS);
            String id = requireTransactionId(transactionIds.get());
            transactionDirectory = deploymentRoot.resolve("tx-" + id).normalize();
            requireInsideDeploymentRoot(transactionDirectory);
            rejectSymlinks(transactionDirectory);
            Files.createDirectory(transactionDirectory);
            restrictIfRequired(transactionDirectory, true);

            byte[] certificate = material.fullChainPem().getBytes(StandardCharsets.US_ASCII);
            byte[] privateKey = encodePkcs8Pem(material.privateKeyPkcs8());
            writeAndForce(transactionDirectory.resolve("new-certificate.crt"), certificate);
            writeAndForce(transactionDirectory.resolve("new-private-key.pem"), privateKey);
            if (hadOldPair) {
                copyBoundedAndForce(certificatePaths.fullChain(),
                        transactionDirectory.resolve("old-certificate.crt"), MAX_CERTIFICATE_BYTES);
                copyBoundedAndForce(certificatePaths.privateKey(),
                        transactionDirectory.resolve("old-private-key.pem"), MAX_PRIVATE_KEY_BYTES);
            }

            journal = new Journal(id, Phase.PREPARED, hadOldPair);
            writeJournal(journal);
            atomicReplace(transactionDirectory.resolve("new-private-key.pem"), certificatePaths.privateKey());
            faultInjector.after(ReplacementPoint.PRIVATE_KEY_REPLACED);
            journal = journal.withPhase(Phase.PRIVATE_KEY_REPLACED);
            writeJournal(journal);
            atomicReplace(transactionDirectory.resolve("new-certificate.crt"), certificatePaths.fullChain());
            faultInjector.after(ReplacementPoint.CERTIFICATE_REPLACED);
            journal = journal.withPhase(Phase.CERTIFICATE_REPLACED);
            writeJournal(journal);
            requireExactFile(certificatePaths.privateKey(), privateKey, MAX_PRIVATE_KEY_BYTES);
            requireExactFile(certificatePaths.fullChain(), certificate, MAX_CERTIFICATE_BYTES);
            return new PendingDeployment(journal, lock);
        } catch (SimulatedCrashException simulatedCrash) {
            lock.close();
            throw simulatedCrash;
        } catch (IOException | RuntimeException failure) {
            if (journal != null) {
                try { rollbackLocked(journal, true); }
                catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            } else if (transactionDirectory != null) {
                try { cleanupTransaction(transactionDirectory); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            try { lock.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Conservatively rolls back any transaction that was not explicitly committed. */
    public void recover() throws IOException, InterruptedException {
        recoverAndReport();
    }

    /** Reports whether live credential files changed and therefore need host reconciliation. */
    public RecoveryResult recoverAndReport() throws IOException, InterruptedException {
        try (DeploymentLock ignored = acquireLock()) {
            return recoverLocked();
        }
    }

    /** Clears a durable rollback marker only after the host has loaded the restored credentials. */
    public void completeRecoveryReconciliation() throws IOException, InterruptedException {
        try (DeploymentLock ignored = acquireLock()) {
            Journal journal = readJournal();
            if (journal == null) return;
            if (journal.phase != Phase.ROLLED_BACK) {
                throw new IOException("Certificate recovery is not ready for reconciliation completion");
            }
            cleanupResolvedTransaction(journal);
            clearJournal();
        }
    }

    private RecoveryResult recoverLocked() throws IOException {
        Journal journal = readJournal();
        if (journal == null) return new RecoveryResult(false, false);
        if (journal.phase == Phase.COMMITTED) {
            cleanupResolvedTransaction(journal);
            clearJournal();
            return new RecoveryResult(false, journal.hadOldPair);
        }
        if (journal.phase == Phase.ROLLED_BACK) {
            cleanupResolvedTransaction(journal);
            return new RecoveryResult(true, journal.hadOldPair);
        }
        rollbackLocked(journal, true);
        return new RecoveryResult(true, journal.hadOldPair);
    }

    private void rollbackLocked(Journal journal, boolean requireHostReconciliation) throws IOException {
        Journal rollingBack = journal.withPhase(Phase.ROLLING_BACK);
        writeJournal(rollingBack);
        journal = rollingBack;
        Path transactionDirectory = transactionDirectory(journal);
        if (journal.hadOldPair) {
            Path restoredKey = transactionDirectory.resolve("restore-private-key.pem");
            Path restoredCertificate = transactionDirectory.resolve("restore-certificate.crt");
            Files.deleteIfExists(restoredKey);
            Files.deleteIfExists(restoredCertificate);
            copyBoundedAndForce(transactionDirectory.resolve("old-private-key.pem"),
                    restoredKey, MAX_PRIVATE_KEY_BYTES);
            copyBoundedAndForce(transactionDirectory.resolve("old-certificate.crt"),
                    restoredCertificate, MAX_CERTIFICATE_BYTES);
            atomicReplace(restoredKey, certificatePaths.privateKey());
            atomicReplace(restoredCertificate, certificatePaths.fullChain());
        } else {
            rejectSymlinks(certificatePaths.privateKey());
            rejectSymlinks(certificatePaths.fullChain());
            Files.deleteIfExists(certificatePaths.privateKey());
            Files.deleteIfExists(certificatePaths.fullChain());
            forceDirectory(certificatePaths.directory());
        }
        Journal rolledBack = journal.withPhase(Phase.ROLLED_BACK);
        writeJournal(rolledBack);
        cleanupTransaction(transactionDirectory);
        if (!requireHostReconciliation) clearJournal();
    }

    private void requireConsistentExistingPair() throws IOException {
        rejectSymlinks(certificatePaths.fullChain());
        rejectSymlinks(certificatePaths.privateKey());
        boolean certificateExists = Files.exists(certificatePaths.fullChain(), LinkOption.NOFOLLOW_LINKS);
        boolean keyExists = Files.exists(certificatePaths.privateKey(), LinkOption.NOFOLLOW_LINKS);
        if (certificateExists != keyExists) {
            throw new IOException("Refusing deployment over an incomplete existing certificate/key pair");
        }
        if (certificateExists) {
            requireRegularFile(certificatePaths.fullChain());
            requireRegularFile(certificatePaths.privateKey());
        }
    }

    private DeploymentLock acquireLock() throws IOException, InterruptedException {
        rejectSymlinks(deploymentRoot);
        rejectWritableAncestors(deploymentRoot);
        Files.createDirectories(deploymentRoot);
        rejectSymlinks(deploymentRoot);
        rejectWritableAncestors(deploymentRoot);
        restrictIfRequired(deploymentRoot, true);
        Path lockPath = deploymentRoot.resolve("deployment.lock");
        rejectSymlinks(lockPath);
        Semaphore processGate = JVM_GATES.computeIfAbsent(lockPath, ignored -> new Semaphore(1));
        processGate.acquire();
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            restrictIfRequired(lockPath, false);
            fileLock = channel.lock();
            return new DeploymentLock(processGate, channel, fileLock);
        } catch (IOException | RuntimeException failure) {
            if (fileLock != null) try { fileLock.release(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            if (channel != null) try { channel.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            processGate.release();
            throw failure;
        }
    }

    private void writeAndForce(Path path, byte[] value) throws IOException {
        rejectSymlinks(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            restrictIfRequired(path, false);
            writeFully(channel, value);
            channel.force(true);
        }
        forceDirectory(path.getParent());
    }

    private void copyBoundedAndForce(Path source, Path target, int maximumBytes) throws IOException {
        requireRegularFile(source);
        byte[] value = readBounded(source, maximumBytes);
        writeAndForce(target, value);
    }

    private void atomicReplace(Path source, Path target) throws IOException {
        atomicReplace(source, target, null);
    }

    private void atomicReplace(Path source, Path target, ReplacementPoint afterMove) throws IOException {
        rejectSymlinks(source);
        rejectSymlinks(target);
        requireRegularFile(source);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Certificate deployment requires atomic replacement on the credentials filesystem",
                    unsupported);
        }
        if (afterMove != null) faultInjector.after(afterMove);
        forceDirectory(target.getParent());
    }

    private void writeJournal(Journal journal) throws IOException {
        String value = "version=1\ntransaction=" + journal.transactionId + "\nphase=" + journal.phase
                + "\nhadOldPair=" + journal.hadOldPair + "\n";
        Path temporary = Files.createTempFile(deploymentRoot, ".pending-deployment-", ".tmp");
        try {
            restrictIfRequired(temporary, false);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                writeFully(channel, value.getBytes(StandardCharsets.US_ASCII));
                channel.force(true);
            }
            atomicReplace(temporary, journalPath, journal.phase == Phase.COMMITTED
                    ? ReplacementPoint.COMMIT_JOURNAL_REPLACED : null);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Journal readJournal() throws IOException {
        rejectSymlinks(journalPath);
        if (Files.notExists(journalPath, LinkOption.NOFOLLOW_LINKS)) return null;
        requireRegularFile(journalPath);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(readBounded(journalPath, MAX_JOURNAL_BYTES),
                StandardCharsets.US_ASCII).split("\\R")) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0 || fields.putIfAbsent(line.substring(0, separator),
                    line.substring(separator + 1)) != null) {
                throw new IOException("Malformed certificate deployment journal");
            }
        }
        if (!"1".equals(fields.get("version")) || fields.size() != 4) {
            throw new IOException("Unsupported certificate deployment journal");
        }
        String id = requireTransactionId(fields.get("transaction"));
        Phase phase;
        try { phase = Phase.valueOf(fields.get("phase")); }
        catch (RuntimeException invalid) { throw new IOException("Invalid certificate deployment phase", invalid); }
        String oldPair = fields.get("hadOldPair");
        if (!"true".equals(oldPair) && !"false".equals(oldPair)) {
            throw new IOException("Invalid certificate deployment backup flag");
        }
        return new Journal(id, phase, Boolean.parseBoolean(oldPair));
    }

    private void clearJournal() throws IOException {
        rejectSymlinks(journalPath);
        Files.deleteIfExists(journalPath);
        forceDirectory(deploymentRoot);
    }

    private Path transactionDirectory(Journal journal) throws IOException {
        Path path = deploymentRoot.resolve("tx-" + journal.transactionId).normalize();
        requireInsideDeploymentRoot(path);
        rejectSymlinks(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Certificate deployment transaction directory is missing");
        }
        return path;
    }

    private void cleanupTransaction(Path directory) throws IOException {
        rejectSymlinks(directory);
        for (String name : List.of("new-certificate.crt", "new-private-key.pem",
                "old-certificate.crt", "old-private-key.pem",
                "restore-certificate.crt", "restore-private-key.pem")) {
            Path file = directory.resolve(name);
            rejectSymlinks(file);
            Files.deleteIfExists(file);
        }
        Files.deleteIfExists(directory);
        forceDirectory(deploymentRoot);
    }

    private void cleanupResolvedTransaction(Journal journal) throws IOException {
        Path directory = deploymentRoot.resolve("tx-" + journal.transactionId).normalize();
        requireInsideDeploymentRoot(directory);
        rejectSymlinks(directory);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Certificate deployment transaction path is not a directory");
            }
            cleanupTransaction(directory);
        }
    }

    private void requireExactFile(Path file, byte[] expected, int maximumBytes) throws IOException {
        byte[] actual = readBounded(file, maximumBytes);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new IOException("Installed certificate material does not match the validated staging data");
        }
    }

    private static byte[] encodePkcs8Pem(byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN PRIVATE KEY-----\n" + body
                + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] readBounded(Path file, int maximumBytes) throws IOException {
        requireRegularFile(file);
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] value = input.readNBytes(maximumBytes + 1);
            if (value.length > maximumBytes) throw new IOException("Certificate deployment file is unexpectedly large");
            return value;
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Certificate deployment path must be a regular file");
        }
    }

    private void rejectSymlinks(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(serverRoot)) throw new IOException("Certificate deployment path escapes server root");
        Path current = serverRoot;
        for (Path part : serverRoot.relativize(absolute)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Certificate deployment path must not contain symbolic links");
            }
        }
    }

    private void requireInsideDeploymentRoot(Path path) throws IOException {
        if (!path.startsWith(deploymentRoot) || path.equals(deploymentRoot)) {
            throw new IOException("Certificate transaction path escapes deployment state directory");
        }
    }

    private void rejectWritableAncestors(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(serverRoot)) throw new IOException("Certificate deployment path escapes server root");
        Path current = serverRoot;
        auditDirectoryWriteAccess(current);
        for (Path part : serverRoot.relativize(absolute)) {
            current = current.resolve(part);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            auditDirectoryWriteAccess(current);
        }
    }

    private static void auditDirectoryWriteAccess(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Certificate deployment ancestor must be a directory");
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("Certificate deployment directories must not be writable by group or others");
            }
        }
    }

    private static String requireTransactionId(String value) throws IOException {
        if (value == null) throw new IOException("Missing certificate deployment transaction ID");
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException("Non-canonical UUID");
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid certificate deployment transaction ID", invalid);
        }
    }

    private void restrictIfRequired(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            if (allowInsecurePermissions) {
                var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException("Certificate deployment path must not be writable by group or others");
                }
                return;
            }
            Files.setPosixFilePermissions(path, directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        if (allowInsecurePermissions) {
            CredentialTargetInspector.auditAcl(path, true, !directory);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Cannot enforce private certificate deployment permissions");
        AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(ownerOnly));
    }

    private static void writeFully(FileChannel channel, byte[] value) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException unsupported) {
            // Some filesystems/JDKs cannot open directories. File contents are still forced.
        }
    }

    public final class PendingDeployment implements AutoCloseable {
        private Journal journal;
        private DeploymentLock lock;
        private boolean resolved;

        private PendingDeployment(Journal journal, DeploymentLock lock) {
            this.journal = journal;
            this.lock = lock;
        }

        /** Call only after AutoModpack activation and readiness have succeeded. */
        public synchronized void commit() throws IOException {
            if (resolved) return;
            Journal committed = journal.withPhase(Phase.COMMITTED);
            try {
                faultInjector.after(ReplacementPoint.BEFORE_COMMIT_JOURNAL);
                writeJournal(committed);
            } catch (IOException commitPersistenceFailure) {
                try { rollbackLocked(journal, true); }
                catch (IOException rollbackFailure) { commitPersistenceFailure.addSuppressed(rollbackFailure); }
                resolved = true;
                try { lock.close(); } catch (IOException closeFailure) {
                    commitPersistenceFailure.addSuppressed(closeFailure);
                }
                lock = null;
                throw commitPersistenceFailure;
            }

            journal = committed;
            resolved = true;
            IOException failure = null;
            try {
                cleanupTransaction(transactionDirectory(journal));
                faultInjector.after(ReplacementPoint.COMMIT_CLEANUP_COMPLETE);
                clearJournal();
            } catch (IOException commitFailure) {
                failure = commitFailure;
            } finally {
                try { lock.close(); } catch (IOException closeFailure) {
                    if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
                }
                lock = null;
            }
            if (failure != null) throw failure;
        }

        /** True once the durable COMMITTED journal was written, even if later cleanup reported an error. */
        public synchronized boolean isCommitted() {
            return journal.phase == Phase.COMMITTED;
        }

        public synchronized void rollback() throws IOException {
            if (resolved) return;
            resolved = true;
            IOException failure = null;
            try { rollbackLocked(journal, true); }
            catch (IOException rollbackFailure) { failure = rollbackFailure; }
            finally {
                try { lock.close(); } catch (IOException closeFailure) {
                    if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
                }
                lock = null;
            }
            if (failure != null) throw failure;
        }

        public synchronized void completeRollbackReconciliation() throws IOException, InterruptedException {
            CertificateDeploymentService.this.completeRecoveryReconciliation();
        }

        @Override public synchronized void close() throws IOException { rollback(); }
    }

    @FunctionalInterface
    interface MaterialValidator {
        IssuedCertificateValidator.ValidationResult validate(AcmeIssuanceService.IssuedMaterial material)
                throws IOException, GeneralSecurityException;
    }

    public record RecoveryResult(boolean credentialsChanged, boolean hasCertificatePair) { }

    @FunctionalInterface
    interface DeploymentFaultInjector {
        void after(ReplacementPoint point) throws IOException;
    }

    enum ReplacementPoint {
        PRIVATE_KEY_REPLACED, CERTIFICATE_REPLACED, BEFORE_COMMIT_JOURNAL,
        COMMIT_JOURNAL_REPLACED, COMMIT_CLEANUP_COMPLETE
    }

    static final class SimulatedCrashException extends RuntimeException {
        SimulatedCrashException(String message) { super(message); }
    }

    private enum Phase {
        PREPARED, PRIVATE_KEY_REPLACED, CERTIFICATE_REPLACED, ROLLING_BACK, COMMITTED, ROLLED_BACK
    }

    private record Journal(String transactionId, Phase phase, boolean hadOldPair) {
        Journal withPhase(Phase value) { return new Journal(transactionId, value, hadOldPair); }
    }

    private static final class DeploymentLock implements AutoCloseable {
        private final Semaphore processGate;
        private final FileChannel channel;
        private final FileLock fileLock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DeploymentLock(Semaphore processGate, FileChannel channel, FileLock fileLock) {
            this.processGate = processGate;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            IOException failure = null;
            try { fileLock.release(); } catch (IOException releaseFailure) { failure = releaseFailure; }
            try { channel.close(); } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            } finally {
                processGate.release();
            }
            if (failure != null) throw failure;
        }
    }
}
