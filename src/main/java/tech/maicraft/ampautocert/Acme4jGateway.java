package tech.maicraft.ampautocert;

import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Production {@link AcmeGateway} backed by acme4j 5.1.x. */
public final class Acme4jGateway implements AcmeGateway {
    private final Acme4jClient client;
    private final Clock clock;
    private final List<String> allowedDomains;

    public Acme4jGateway(AutoCertConfig config, Clock clock) {
        this(new DefaultAcme4jClient(config, clock), clock, config.domains());
    }

    Acme4jGateway(Acme4jClient client, Clock clock, List<String> allowedDomains) {
        this.client = client;
        this.clock = clock;
        this.allowedDomains = List.copyOf(allowedDomains);
    }

    @Override
    public Order openOrder(AcmeEnvironment environment, String contactEmail, KeyPair accountKey,
                           Optional<URI> accountLocation, List<String> domains)
            throws IOException, InterruptedException {
        if (!List.copyOf(domains).equals(allowedDomains)) {
            throw new IllegalArgumentException("ACME order domains do not match the fixed allowlist");
        }
        if (contactEmail == null || contactEmail.isBlank() || !contactEmail.contains("@")) {
            throw new IllegalArgumentException("ACME contact email is invalid");
        }
        try {
            return new GatewayOrder(client.open(environment, contactEmail, accountKey, accountLocation, domains));
        } catch (AcmeException | AcmeLazyLoadingException failure) {
            throw acmeFailure("Cannot open ACME order", failure);
        }
    }

    private final class GatewayOrder implements Order {
        private final Acme4jClient.RemoteOrder remote;

        private GatewayOrder(Acme4jClient.RemoteOrder remote) { this.remote = remote; }
        @Override public URI accountLocation() { return remote.accountLocation(); }
        @Override public URI orderLocation() { return remote.orderLocation(); }

        @Override
        public List<DnsChallenge> dnsChallenges() throws IOException {
            try {
                return List.copyOf(remote.dnsChallenges());
            } catch (AcmeException | AcmeLazyLoadingException failure) {
                throw acmeFailure("Cannot read ACME DNS-01 challenges", failure);
            }
        }

        @Override
        public void triggerChallenges() throws IOException, InterruptedException {
            try {
                remote.triggerChallenges();
            } catch (AcmeException | AcmeLazyLoadingException failure) {
                throw acmeFailure("Cannot trigger ACME DNS-01 challenges", failure);
            }
        }

        @Override
        public void awaitReady(Instant deadline) throws IOException, InterruptedException {
            requireFuture(deadline);
            try {
                remote.awaitReady(deadline);
            } catch (AcmeException | AcmeLazyLoadingException failure) {
                throw acmeFailure("ACME authorization or order did not become ready", failure);
            }
        }

        @Override
        public String finalizeAndDownload(KeyPair certificateKey, List<String> domains, Instant deadline)
                throws IOException, InterruptedException {
            if (!List.copyOf(domains).equals(allowedDomains)) {
                throw new IllegalArgumentException("CSR domains do not match the fixed allowlist");
            }
            requireFuture(deadline);
            try {
                return remote.finalizeAndDownload(certificateKey, deadline);
            } catch (AcmeException | AcmeLazyLoadingException failure) {
                throw acmeFailure("Cannot finalize or download ACME certificate", failure);
            }
        }

        private void requireFuture(Instant deadline) throws IOException {
            if (deadline == null || !clock.instant().isBefore(deadline)) {
                throw new IOException("ACME operation deadline expired");
            }
        }
    }

    private static IOException acmeFailure(String message, Throwable cause) {
        return new IOException(message + " (" + cause.getClass().getSimpleName() + ")");
    }
}
