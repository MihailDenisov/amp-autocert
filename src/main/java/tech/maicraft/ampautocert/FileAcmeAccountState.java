package tech.maicraft.ampautocert;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Duration;
import java.time.Instant;

/** Private, restart-safe ACME account identity separated by CA environment. */
public final class FileAcmeAccountState implements AcmeAccountState, IssuanceStateStore {
    private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
    private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_END = "-----END PUBLIC KEY-----";
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_FILE_LOCKS = new ConcurrentHashMap<>();
    private final Path root;

    public FileAcmeAccountState(Path stateRoot) throws IOException {
        root = stateRoot.toAbsolutePath().normalize();
        rejectSymlinks(root);
        Files.createDirectories(root);
        rejectSymlinks(root);
        restrict(root, true);
    }

    @Override
    public synchronized KeyPair loadOrCreateKeyPair(AcmeEnvironment environment) throws IOException {
        Path directory = environmentDirectory(environment);
        Path keyFile = directory.resolve("account-key.pem");
        ensurePrivateDirectory(directory);
        Path lockFile = directory.resolve("account-key.lock");
        rejectSymlinks(lockFile);
        ReentrantLock processLock = JVM_FILE_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
        processLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                restrict(lockFile, false);
                try (FileLock ignored = channel.lock()) {
                    rejectSymlinks(keyFile);
                    if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                        requirePrivateRegularFile(keyFile);
                        return readKeyPair(keyFile);
                    }
                    KeyPair generated = generateAccountKey();
                    writePrivateAtomic(keyFile, encodeKeyPair(generated), false);
                    return generated;
                }
            }
        } finally {
            processLock.unlock();
        }
    }

    @Override
    public IssuanceLock acquireIssuanceLock(AcmeEnvironment environment) throws IOException, InterruptedException {
        Path directory = environmentDirectory(environment);
        ensurePrivateDirectory(directory);
        Path lockFile = directory.resolve("issuance.lock");
        rejectSymlinks(lockFile);
        ReentrantLock processLock = JVM_FILE_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
        processLock.lockInterruptibly();
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            restrict(lockFile, false);
            fileLock = channel.lock();
            FileChannel heldChannel = channel;
            FileLock heldLock = fileLock;
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                IOException failure = null;
                try { heldLock.release(); } catch (IOException releaseFailure) { failure = releaseFailure; }
                try { heldChannel.close(); } catch (IOException closeFailure) {
                    if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
                } finally {
                    processLock.unlock();
                }
                if (failure != null) throw failure;
            };
        } catch (IOException | RuntimeException failure) {
            if (fileLock != null) try { fileLock.release(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            if (channel != null) try { channel.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            processLock.unlock();
            throw failure;
        }
    }

    @Override
    public synchronized Optional<URI> loadAccountLocation(AcmeEnvironment environment) throws IOException {
        Path file = environmentDirectory(environment).resolve("account-location.txt");
        rejectSymlinks(file);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requirePrivateRegularFile(file);
        String value = readBoundedAscii(file, 4096).strip();
        return Optional.of(validateAccountLocation(URI.create(value)));
    }

    @Override
    public synchronized void saveAccountLocation(AcmeEnvironment environment, URI location) throws IOException {
        URI validated = validateAccountLocation(location);
        Path directory = environmentDirectory(environment);
        ensurePrivateDirectory(directory);
        writePrivateAtomic(directory.resolve("account-location.txt"), validated.toASCIIString() + System.lineSeparator(), true);
    }

    @Override
    public synchronized Optional<IssuanceTransaction> loadIssuance(AcmeEnvironment environment) throws IOException {
        Path file = environmentDirectory(environment).resolve("pending-issuance.state");
        rejectSymlinks(file);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requirePrivateRegularFile(file);
        try {
            Map<String, String> values = parseState(readBoundedAscii(file, 16384));
            if (!"1".equals(values.get("version"))
                    || !environment.directoryName().equals(values.get("environment"))) {
                throw new IllegalArgumentException("Issuance state version or environment mismatch");
            }
            int challengeCount = boundedCount(values.get("challenge.count"));
            int recordCount = boundedCount(values.get("record.count"));
            int expectedFields = 9 + challengeCount * 3 + recordCount;
            if (values.size() != expectedFields) throw new IllegalArgumentException("Unexpected issuance state fields");
            List<AcmeGateway.DnsChallenge> challenges = new java.util.ArrayList<>(challengeCount);
            for (int i = 0; i < challengeCount; i++) {
                challenges.add(new AcmeGateway.DnsChallenge(values.get("challenge." + i + ".domain"),
                        values.get("challenge." + i + ".name"), values.get("challenge." + i + ".value")));
            }
            List<String> recordIds = new java.util.ArrayList<>(recordCount);
            for (int i = 0; i < recordCount; i++) recordIds.add(values.get("record." + i));
            return Optional.of(new IssuanceTransaction(environment, values.get("transaction"), values.get("zone"),
                    URI.create(values.get("account")), URI.create(values.get("order")),
                    IssuanceTransaction.Phase.valueOf(values.get("phase")), challenges, recordIds));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid pending ACME issuance state", invalid);
        }
    }

    @Override
    public synchronized void saveIssuance(IssuanceTransaction transaction) throws IOException {
        Path directory = environmentDirectory(transaction.environment());
        ensurePrivateDirectory(directory);
        StringBuilder value = new StringBuilder()
                .append("version=1\n")
                .append("environment=").append(transaction.environment().directoryName()).append('\n')
                .append("transaction=").append(transaction.transactionId()).append('\n')
                .append("zone=").append(transaction.zoneName()).append('\n')
                .append("account=").append(transaction.accountLocation().toASCIIString()).append('\n')
                .append("order=").append(transaction.orderLocation().toASCIIString()).append('\n')
                .append("phase=").append(transaction.phase()).append('\n')
                .append("challenge.count=").append(transaction.challenges().size()).append('\n');
        for (int i = 0; i < transaction.challenges().size(); i++) {
            AcmeGateway.DnsChallenge challenge = transaction.challenges().get(i);
            value.append("challenge.").append(i).append(".domain=").append(challenge.requestedDomain()).append('\n')
                    .append("challenge.").append(i).append(".name=").append(challenge.recordName()).append('\n')
                    .append("challenge.").append(i).append(".value=").append(challenge.value()).append('\n');
        }
        value.append("record.count=").append(transaction.recordIds().size()).append('\n');
        for (int i = 0; i < transaction.recordIds().size(); i++) {
            value.append("record.").append(i).append('=').append(transaction.recordIds().get(i)).append('\n');
        }
        writePrivateAtomic(directory.resolve("pending-issuance.state"), value.toString(), true);
    }

    @Override
    public synchronized void clearIssuance(AcmeEnvironment environment) throws IOException {
        Path file = environmentDirectory(environment).resolve("pending-issuance.state");
        rejectSymlinks(file);
        Files.deleteIfExists(file);
    }

    /** Atomically reserves a production attempt across JVMs; empty means the reservation succeeded. */
    public Optional<Instant> reserveRenewalAttempt(AcmeEnvironment environment, Instant now, Duration cooldown)
            throws IOException, InterruptedException {
        if (now == null || cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("Renewal attempt time and cooldown are required");
        }
        Path directory = environmentDirectory(environment);
        ensurePrivateDirectory(directory);
        Path lockFile = directory.resolve("renewal-attempt.lock");
        Path stateFile = directory.resolve("last-renewal-attempt.txt");
        rejectSymlinks(lockFile);
        rejectSymlinks(stateFile);
        ReentrantLock processLock = JVM_FILE_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
        processLock.lockInterruptibly();
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            restrict(lockFile, false);
            try (FileLock ignored = channel.lock()) {
                if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                    requirePrivateRegularFile(stateFile);
                    Instant previous;
                    try {
                        previous = Instant.parse(readBoundedAscii(stateFile, 128).strip());
                    } catch (RuntimeException invalid) {
                        throw new IOException("Invalid renewal-attempt state", invalid);
                    }
                    if (previous.isAfter(now)) throw new IOException("Renewal-attempt state is in the future");
                    Instant next = previous.plus(cooldown);
                    if (now.isBefore(next)) return Optional.of(next);
                }
                writePrivateAtomic(stateFile, now + System.lineSeparator(), true);
                return Optional.empty();
            }
        } finally {
            processLock.unlock();
        }
    }

    private Path environmentDirectory(AcmeEnvironment environment) {
        Path path = root.resolve(environment.directoryName()).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("ACME state path escapes its private root");
        return path;
    }

    private void ensurePrivateDirectory(Path directory) throws IOException {
        rejectSymlinks(directory);
        Files.createDirectories(directory);
        rejectSymlinks(directory);
        restrict(directory, true);
    }

    private void writePrivateAtomic(Path target, String value, boolean replace) throws IOException {
        rejectSymlinks(target);
        Path temporary = Files.createTempFile(target.getParent(), ".amp-autocert-", ".tmp");
        try {
            restrict(temporary, false);
            Files.writeString(temporary, value, StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Private ACME state requires atomic file replacement", unsupported);
            }
            restrict(target, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static KeyPair generateAccountKey() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IOException("Cannot generate ACME account key", failure);
        }
    }

    private static KeyPair readKeyPair(Path file) throws IOException {
        try {
            String pem = readBoundedAscii(file, 16384);
            byte[] privateBytes = decodeBlock(pem, PRIVATE_BEGIN, PRIVATE_END);
            byte[] publicBytes = decodeBlock(pem, PUBLIC_BEGIN, PUBLIC_END);
            KeyFactory factory = KeyFactory.getInstance("EC");
            return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(publicBytes)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException("Invalid ACME account key file", failure);
        }
    }

    private static String encodeKeyPair(KeyPair pair) {
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
        return PRIVATE_BEGIN + "\n" + encoder.encodeToString(pair.getPrivate().getEncoded()) + "\n" + PRIVATE_END + "\n"
                + PUBLIC_BEGIN + "\n" + encoder.encodeToString(pair.getPublic().getEncoded()) + "\n" + PUBLIC_END + "\n";
    }

    private static byte[] decodeBlock(String pem, String begin, String end) {
        int start = pem.indexOf(begin);
        int finish = pem.indexOf(end);
        if (start < 0 || finish <= start) throw new IllegalArgumentException("Missing PEM block");
        return Base64.getMimeDecoder().decode(pem.substring(start + begin.length(), finish));
    }

    private static URI validateAccountLocation(URI location) {
        if (location == null || !"https".equalsIgnoreCase(location.getScheme()) || location.getHost() == null
                || location.getUserInfo() != null || location.getFragment() != null) {
            throw new IllegalArgumentException("ACME account location must be an HTTPS URL");
        }
        return location.normalize();
    }

    private static Map<String, String> parseState(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Malformed issuance state line");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate issuance state field");
        }
        return values;
    }

    private static int boundedCount(String value) {
        int count = Integer.parseInt(value);
        if (count < 0 || count > 2) throw new IllegalArgumentException("Invalid issuance state count");
        return count;
    }

    private static String readBoundedAscii(Path file, int maximumBytes) throws IOException {
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) throw new IOException("ACME state file is unexpectedly large");
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }

    private static void requirePrivateRegularFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ACME state must be a regular file");
        }
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> allowed = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            if (!allowed.containsAll(permissions)) throw new IOException("ACME state file permissions are too broad");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Cannot verify private ACME state permissions");
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(acl.getOwner())) {
                throw new IOException("ACME state file ACL grants access to another principal");
            }
        }
    }

    private static void rejectSymlinks(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) throw new IOException("ACME state path must be absolute");
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("ACME state path must not contain symbolic links");
        }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Cannot enforce private ACME state permissions");
        AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(ownerOnly));
    }
}
