package tech.maicraft.ampautocert;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Injectable ACME boundary; the production implementation is backed by acme4j. */
public interface AcmeGateway {
    Order openOrder(AcmeEnvironment environment, String contactEmail, KeyPair accountKey,
                    Optional<URI> accountLocation, List<String> domains)
            throws IOException, InterruptedException;

    interface Order {
        URI accountLocation();
        URI orderLocation();
        List<DnsChallenge> dnsChallenges() throws IOException;
        void triggerChallenges() throws IOException, InterruptedException;
        void awaitReady(Instant deadline) throws IOException, InterruptedException;
        String finalizeAndDownload(KeyPair certificateKey, List<String> domains, Instant deadline)
                throws IOException, InterruptedException;
    }

    record DnsChallenge(String requestedDomain, String recordName, String value) { }
}
