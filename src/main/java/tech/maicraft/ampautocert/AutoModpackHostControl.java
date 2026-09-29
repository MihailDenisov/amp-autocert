package tech.maicraft.ampautocert;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Host control for the inspected AutoModpack 5.0.0 release candidates. */
final class AutoModpackHostControl {
    static final String SUPPORTED_VERSION_RANGE = "5.0.0-rc.1 through 5.0.0-rc.2";
    private static final String RESTART_COMMAND = "automodpack host restart";

    private final RestartDispatcher restartDispatcher;
    private final HostProbe hostProbe;
    private final HostTransitionLock transitionLock;
    private final BooleanSupplier serverThreadCheck;

    static AutoModpackHostControl forServer(MinecraftServer server) {
        RestartDispatcher dispatcher = serverThreadDispatcher(server::execute, server::isSameThread,
                () -> server.getCommands().getDispatcher().execute(
                        RESTART_COMMAND, server.createCommandSourceStack()));
        ReflectionHostAccess access = new ReflectionHostAccess(Thread.currentThread().getContextClassLoader());
        return new AutoModpackHostControl(dispatcher, access, access, server::isSameThread);
    }

    static RestartDispatcher serverThreadDispatcher(Consumer<Runnable> executor,
                                                      BooleanSupplier serverThreadCheck,
                                                      CommandInvocation command) {
        return allowed -> {
            CompletableFuture<Integer> result = new CompletableFuture<>();
            try {
                executor.accept(() -> {
                    if (result.isCancelled() || !allowed.getAsBoolean()) {
                        result.cancel(false);
                        return;
                    }
                    try {
                        if (!serverThreadCheck.getAsBoolean()) {
                            throw new IllegalStateException("AutoModpack restart was not dispatched on the server thread");
                        }
                        result.complete(command.invoke());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException rejected) {
                result.completeExceptionally(rejected);
            }
            return result;
        };
    }

    AutoModpackHostControl(RestartDispatcher restartDispatcher, HostProbe hostProbe) {
        this(restartDispatcher, hostProbe, operation -> operation.run(), () -> false);
    }

    AutoModpackHostControl(RestartDispatcher restartDispatcher, HostProbe hostProbe,
                           BooleanSupplier serverThreadCheck) {
        this(restartDispatcher, hostProbe, operation -> operation.run(), serverThreadCheck);
    }

    AutoModpackHostControl(RestartDispatcher restartDispatcher, HostProbe hostProbe,
                           HostTransitionLock transitionLock, BooleanSupplier serverThreadCheck) {
        this.restartDispatcher = restartDispatcher;
        this.hostProbe = hostProbe;
        this.transitionLock = transitionLock;
        this.serverThreadCheck = serverThreadCheck;
    }

    CompletionStage<Integer> restart(BooleanSupplier allowed) {
        return restartDispatcher.restart(allowed);
    }

    HostSnapshot snapshot() throws IOException {
        return hostProbe.snapshot();
    }

    boolean isServerThread() {
        return serverThreadCheck.getAsBoolean();
    }

    void withTransitionLock(HostOperation operation) throws IOException {
        transitionLock.run(operation);
    }

    record HostSnapshot(boolean compatible, boolean initialized, boolean running,
                        String certificateFingerprint, String detail) {
        HostSnapshot(boolean compatible, boolean running, String certificateFingerprint, String detail) {
            this(compatible, compatible, running, certificateFingerprint, detail);
        }

        HostSnapshot {
            if (detail == null) throw new IllegalArgumentException("Host detail is required");
            if (certificateFingerprint != null) {
                certificateFingerprint = certificateFingerprint.toLowerCase(Locale.ROOT);
                if (!certificateFingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("AutoModpack returned an invalid certificate fingerprint");
                }
            }
        }

        static HostSnapshot absent() {
            return new HostSnapshot(false, false, false, null, "AutoModpack is absent");
        }

        static HostSnapshot incompatible() {
            return new HostSnapshot(false, false, false, null,
                    "AutoModpack version is outside " + SUPPORTED_VERSION_RANGE);
        }
    }

    @FunctionalInterface
    interface RestartDispatcher {
        CompletionStage<Integer> restart(BooleanSupplier allowed);
    }

    @FunctionalInterface
    interface CommandInvocation {
        int invoke() throws Exception;
    }

    @FunctionalInterface
    interface HostProbe {
        HostSnapshot snapshot() throws IOException;
    }

    @FunctionalInterface
    interface HostTransitionLock {
        void run(HostOperation operation) throws IOException;
    }

    @FunctionalInterface
    interface HostOperation {
        void run() throws IOException;
    }

    private static final class ReflectionHostAccess implements HostProbe, HostTransitionLock {
        private static final String CONSTANTS = "pl.skidam.automodpack_core.Constants";
        private final ClassLoader classLoader;

        private ReflectionHostAccess(ClassLoader classLoader) {
            this.classLoader = classLoader == null ? AutoModpackHostControl.class.getClassLoader() : classLoader;
        }

        @Override
        public HostSnapshot snapshot() throws IOException {
            final Class<?> constants;
            try {
                constants = Class.forName(CONSTANTS, true, classLoader);
            } catch (ClassNotFoundException absent) {
                return HostSnapshot.absent();
            } catch (LinkageError incompatible) {
                return HostSnapshot.incompatible();
            }

            try {
                Field versionField = constants.getField("AM_VERSION");
                Object version = versionField.get(null);
                if (!isSupportedVersion(version)) return HostSnapshot.incompatible();

                Object host = constants.getField("hostServer").get(null);
                if (host == null) {
                    return new HostSnapshot(true, false, false, null,
                            "AutoModpack host is not initialized");
                }
                Method isRunning = host.getClass().getMethod("isRunning");
                Method getFingerprint = host.getClass().getMethod("getCertificateFingerprint");
                boolean running = (boolean) isRunning.invoke(host);
                String fingerprint = (String) getFingerprint.invoke(host);
                return new HostSnapshot(true, running, fingerprint, running
                        ? "AutoModpack host is running" : "AutoModpack host is stopped");
            } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException incompatible) {
                throw new IOException("AutoModpack " + SUPPORTED_VERSION_RANGE + " host state API is unavailable");
            }
        }

        @Override
        public void run(HostOperation operation) throws IOException {
            Object host = compatibleHost();
            synchronized (host) {
                operation.run();
            }
        }

        private Object compatibleHost() throws IOException {
            try {
                Class<?> constants = Class.forName(CONSTANTS, true, classLoader);
                if (!isSupportedVersion(constants.getField("AM_VERSION").get(null))) {
                    throw new IOException("AutoModpack version is outside " + SUPPORTED_VERSION_RANGE);
                }
                Object host = constants.getField("hostServer").get(null);
                if (host == null) throw new IOException("AutoModpack host is not initialized");
                return host;
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                throw new IOException("AutoModpack " + SUPPORTED_VERSION_RANGE + " host state API is unavailable");
            }
        }
    }

    static boolean isSupportedVersion(Object value) {
        return "5.0.0-rc.1".equals(value) || "5.0.0-rc.2".equals(value);
    }
}
