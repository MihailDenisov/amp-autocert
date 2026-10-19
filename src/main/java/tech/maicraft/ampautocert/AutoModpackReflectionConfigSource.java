package tech.maicraft.ampautocert;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Adapter pinned to AutoModpack 5.0.0-rc.1/rc.2. It prefers the loaded effective object and uses
 * AutoModpack's own Reconf parser for read-only semantic drift checks of an existing server.conf.
 */
final class AutoModpackReflectionConfigSource implements AutoModpackConfigSource {
    private static final String CONSTANTS = "pl.skidam.automodpack_core.Constants";
    private static final String CONFIGS = "pl.skidam.automodpack_core.config.ReconfConfigs";
    private static final String CONFIG_FIELDS =
            "pl.skidam.automodpack_core.config.ServerConfigJsons$ServerConfigFieldsV3";

    private final Path serverConfig;
    private final int minecraftPort;
    private final ClassLoader classLoader;

    AutoModpackReflectionConfigSource(Path serverRoot, int minecraftPort, ClassLoader classLoader) {
        Path root = serverRoot.toAbsolutePath().normalize();
        this.serverConfig = root.resolve("automodpack/server.conf").normalize();
        if (!serverConfig.startsWith(root)) throw new IllegalArgumentException("AutoModpack config path escapes server root");
        this.minecraftPort = minecraftPort;
        this.classLoader = classLoader == null ? AutoModpackReflectionConfigSource.class.getClassLoader() : classLoader;
    }

    @Override
    public AutoModpackIdentitySnapshot effective(AutoCertConfig config) throws IOException {
        try {
            Class<?> constants = Class.forName(CONSTANTS, true, classLoader);
            Object version = constants.getField("AM_VERSION").get(null);
            Object fields = constants.getField("serverConfig").get(null);
            if (fields == null) throw new IOException("AutoModpack effective server configuration is unavailable");
            return snapshot(version, fields, config);
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            throw new IOException("AutoModpack " + AutoModpackHostControl.SUPPORTED_VERSION_RANGE
                    + " effective configuration API is unavailable", failure);
        }
    }

    @Override
    public AutoModpackIdentitySnapshot onDisk(AutoCertConfig config) throws IOException {
        if (!Files.isRegularFile(serverConfig, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(serverConfig)) {
            throw new IOException("AutoModpack automodpack/server.conf is missing or unsafe");
        }
        try {
            Class<?> constants = Class.forName(CONSTANTS, true, classLoader);
            Object version = constants.getField("AM_VERSION").get(null);
            Class<?> fieldsType = Class.forName(CONFIG_FIELDS, true, classLoader);
            Class<?> configsType = Class.forName(CONFIGS, true, classLoader);
            Method read = configsType.getMethod("read", Path.class, Class.class);
            Object result = read.invoke(null, serverConfig, fieldsType);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                throw new IOException("AutoModpack automodpack/server.conf could not be read");
            }
            return snapshot(version, optional.get(), config);
        } catch (InvocationTargetException invalid) {
            throw new IOException("AutoModpack automodpack/server.conf is malformed or unsupported", invalid.getCause());
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            throw new IOException("AutoModpack " + AutoModpackHostControl.SUPPORTED_VERSION_RANGE
                    + " configuration parser is unavailable", failure);
        }
    }

    private AutoModpackIdentitySnapshot snapshot(Object version, Object fields, AutoCertConfig config)
            throws ReflectiveOperationException, IOException {
        try {
            return AutoModpackIdentitySnapshot.resolve(String.valueOf(version), bool(fields, "modpackHost"),
                    String.valueOf(value(fields, "connectionMode")), integer(fields, "bindPort"),
                    string(fields, "advertisedEndpointHost"), integer(fields, "advertisedEndpointPort"),
                    bool(fields, "disableInternalTLS"), config.originHostname(), config.zoneName(), minecraftPort);
        } catch (IllegalArgumentException blocker) {
            throw new IOException(blocker.getMessage(), blocker);
        }
    }

    private static Object value(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }
    private static boolean bool(Object target, String name) throws ReflectiveOperationException {
        return (boolean) value(target, name);
    }
    private static int integer(Object target, String name) throws ReflectiveOperationException {
        return (int) value(target, name);
    }
    private static String string(Object target, String name) throws ReflectiveOperationException {
        return (String) value(target, name);
    }
}
