package tech.maicraft.ampautocert;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.util.Optional;

public interface AcmeAccountState {
    KeyPair loadOrCreateKeyPair(AcmeEnvironment environment) throws IOException;
    Optional<URI> loadAccountLocation(AcmeEnvironment environment) throws IOException;
    void saveAccountLocation(AcmeEnvironment environment, URI location) throws IOException;
}
