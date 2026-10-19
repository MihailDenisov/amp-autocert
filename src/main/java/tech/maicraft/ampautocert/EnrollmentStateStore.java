package tech.maicraft.ampautocert;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Properties;

/** Atomic, non-secret enrollment phase and accepted semantic baseline. */
final class EnrollmentStateStore implements ConfigurationDriftGuard.StateAccess {
    private static final int FILE_VERSION = 1;
    private final Path root;
    private final Path directory;
    private final Path file;

    EnrollmentStateStore(Path serverRoot) {
        root = serverRoot.toAbsolutePath().normalize();
        directory = root.resolve("automodpack/amp-autocert-state").normalize();
        file = directory.resolve("enrollment.properties");
        if (!directory.startsWith(root)) throw new IllegalArgumentException("Enrollment state path escapes server root");
    }

    @Override public synchronized State load() throws IOException {
        rejectSymlinks(file);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return State.none();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Enrollment state is not a regular file");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        try {
            if (!Integer.toString(FILE_VERSION).equals(properties.getProperty("version"))) {
                throw new IllegalArgumentException("Unsupported enrollment state version");
            }
            Phase phase = Phase.valueOf(properties.getProperty("phase", ""));
            Optional<ConfigurationBaseline> baseline = properties.containsKey("baseline.hash")
                    ? Optional.of(ConfigurationBaseline.parse(properties, "baseline.")) : Optional.empty();
            if (phase != Phase.NONE && baseline.isEmpty()) throw new IllegalArgumentException("Enrollment baseline is missing");
            String detail = properties.getProperty("detail", "none");
            if (!detail.matches("[A-Za-z0-9_.:, -]{1,240}")) throw new IllegalArgumentException("Unsafe enrollment detail");
            return new State(phase, baseline, detail);
        } catch (RuntimeException malformed) {
            throw new IOException("Enrollment state is malformed or has been modified", malformed);
        }
    }

    @Override public synchronized void save(State state) throws IOException {
        rejectSymlinks(file);
        Files.createDirectories(directory);
        rejectSymlinks(file);
        StringBuilder text = new StringBuilder("version=").append(FILE_VERSION).append('\n')
                .append("phase=").append(state.phase()).append('\n')
                .append("detail=").append(safeDetail(state.detail())).append('\n');
        state.baseline().ifPresent(value -> value.writeTo(text, "baseline."));
        Path temporary = Files.createTempFile(directory, ".enrollment-", ".tmp");
        boolean committed = false;
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                committed = true;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Enrollment state requires atomic replacement", unsupported);
            }
        } finally {
            // Nothing may fail after the atomic move has committed the new state.
            if (!committed) Files.deleteIfExists(temporary);
        }
    }

    private String safeDetail(String detail) {
        String value = detail == null || detail.isBlank() ? "none" : detail.strip();
        if (!value.matches("[A-Za-z0-9_.:, -]{1,240}")) throw new IllegalArgumentException("Unsafe enrollment detail");
        return value;
    }

    private void rejectSymlinks(Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Enrollment state path must not contain symbolic links");
        }
    }

    enum Phase {
        NONE, MIGRATED_PENDING_STAGING, STAGING_VALIDATED, ACTIVE,
        BLOCKED_CONFIGURATION_DRIFT, BLOCKED_PREFLIGHT, STAGING_FAILED
    }

    record State(Phase phase, Optional<ConfigurationBaseline> baseline, String detail) {
        State {
            baseline = baseline == null ? Optional.empty() : baseline;
            detail = detail == null || detail.isBlank() ? "none" : detail;
        }
        static State none() { return new State(Phase.NONE, Optional.empty(), "none"); }
        static State of(Phase phase, ConfigurationBaseline baseline, String detail) {
            return new State(phase, Optional.of(baseline), detail);
        }
    }
}
