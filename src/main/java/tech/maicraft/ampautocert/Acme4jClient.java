package tech.maicraft.ampautocert;

import org.shredzone.acme4j.exception.AcmeException;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Small injectable seam around acme4j's network resources. */
interface Acme4jClient {
    RemoteOrder open(AcmeEnvironment environment, String contactEmail, KeyPair accountKey,
                     Optional<URI> accountLocation, List<String> domains)
            throws AcmeException, IOException, InterruptedException;

    interface RemoteOrder {
        URI accountLocation();
        URI orderLocation();
        List<AcmeGateway.DnsChallenge> dnsChallenges() throws AcmeException;
        void triggerChallenges() throws AcmeException, InterruptedException;
        void awaitReady(Instant deadline) throws AcmeException, InterruptedException, IOException;
        String finalizeAndDownload(KeyPair certificateKey, Instant deadline)
                throws AcmeException, InterruptedException, IOException;
    }
}
