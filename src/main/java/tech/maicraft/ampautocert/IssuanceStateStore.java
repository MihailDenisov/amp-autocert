package tech.maicraft.ampautocert;

import java.io.IOException;
import java.util.Optional;

public interface IssuanceStateStore {
    IssuanceLock acquireIssuanceLock(AcmeEnvironment environment) throws IOException, InterruptedException;
    Optional<IssuanceTransaction> loadIssuance(AcmeEnvironment environment) throws IOException;
    void saveIssuance(IssuanceTransaction transaction) throws IOException;
    void clearIssuance(AcmeEnvironment environment) throws IOException;

    @FunctionalInterface
    interface IssuanceLock extends AutoCloseable {
        @Override void close() throws IOException;
    }
}
