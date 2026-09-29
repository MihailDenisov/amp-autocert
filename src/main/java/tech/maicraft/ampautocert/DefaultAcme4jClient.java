package tech.maicraft.ampautocert;

import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.exception.AcmeException;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The only class that directly translates amp-autocert operations into acme4j calls. */
final class DefaultAcme4jClient implements Acme4jClient {
    private final Clock clock;
    private final AcmePoller poller;
    private final URI stagingDirectory;
    private final URI productionDirectory;
    private final Duration networkTimeout;

    DefaultAcme4jClient(AutoCertConfig config, Clock clock) {
        this(config.stagingDirectory(), config.productionDirectory(), config.acmeNetworkTimeout(), clock);
    }

    DefaultAcme4jClient(URI stagingDirectory, URI productionDirectory, Duration networkTimeout, Clock clock) {
        this.clock = clock;
        this.poller = new AcmePoller(clock, Sleeper.SYSTEM);
        this.stagingDirectory = stagingDirectory;
        this.productionDirectory = productionDirectory;
        this.networkTimeout = networkTimeout;
    }

    @Override
    public RemoteOrder open(AcmeEnvironment environment, String contactEmail, KeyPair accountKey,
                            Optional<URI> accountLocation, List<String> domains)
            throws AcmeException, IOException, InterruptedException {
        Instant deadline = clock.instant().plus(networkTimeout);
        return AcmePoller.call(deadline, clock,
                () -> openNow(environment, contactEmail, accountKey, accountLocation, domains));
    }

    private RemoteOrder openNow(AcmeEnvironment environment, String contactEmail, KeyPair accountKey,
                                Optional<URI> accountLocation, List<String> domains)
            throws AcmeException, IOException {
        URI directory = directory(environment);
        Session session = new Session(directory);
        session.networkSettings().setTimeout(networkTimeout);
        Login login;
        if (accountLocation.isPresent()) {
            URL accountUrl = validateAccountLocation(directory, accountLocation.orElseThrow()).toURL();
            login = session.login(accountUrl, accountKey);
        } else {
            login = new AccountBuilder().useKeyPair(accountKey).addEmail(contactEmail)
                    .agreeToTermsOfService().createLogin(session);
        }
        org.shredzone.acme4j.Order order = login.newOrder().domains(domains).create();
        return new LiveOrder(directory, login.getAccount().getLocation(), order.getLocation(), order);
    }

    static URI validateAccountLocation(URI directory, URI location) {
        int port = location.getPort();
        if (!"https".equalsIgnoreCase(location.getScheme())
                || !directory.getHost().equalsIgnoreCase(location.getHost())
                || (port != -1 && port != 443)
                || location.getUserInfo() != null || location.getQuery() != null || location.getFragment() != null
                || location.getPath() == null || !location.getPath().startsWith("/acme/acct/")) {
            throw new IllegalArgumentException("Persisted ACME account location does not match its environment");
        }
        return location.normalize();
    }

    static URI validateOrderLocation(URI directory, URI location) {
        int port = location.getPort();
        if (!"https".equalsIgnoreCase(location.getScheme())
                || !directory.getHost().equalsIgnoreCase(location.getHost())
                || (port != -1 && port != 443)
                || location.getUserInfo() != null || location.getQuery() != null || location.getFragment() != null
                || location.getPath() == null || !location.getPath().startsWith("/acme/order/")) {
            throw new IllegalArgumentException("Persisted ACME order location does not match its environment");
        }
        return location.normalize();
    }

    private final class LiveOrder implements RemoteOrder {
        private final URI accountLocation;
        private final URI orderLocation;
        private final org.shredzone.acme4j.Order order;
        private List<Dns01Challenge> pendingChallenges;

        private LiveOrder(URI directory, URL accountLocation, URL orderLocation, org.shredzone.acme4j.Order order) throws IOException {
            try {
                this.accountLocation = validateAccountLocation(directory, accountLocation.toURI());
                this.orderLocation = validateOrderLocation(directory, orderLocation.toURI());
            } catch (URISyntaxException | IllegalArgumentException invalid) {
                throw new IOException("ACME returned an invalid account location", invalid);
            }
            this.order = order;
        }

        @Override public URI accountLocation() { return accountLocation; }
        @Override public URI orderLocation() { return orderLocation; }

        @Override
        public List<AcmeGateway.DnsChallenge> dnsChallenges() throws AcmeException {
            try {
                return AcmePoller.call(clock.instant().plus(networkTimeout), clock, this::loadDnsChallenges);
            } catch (IOException failure) {
                throw new AcmeException("Cannot load DNS-01 challenges", failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AcmeException("Interrupted while loading DNS-01 challenges");
            }
        }

        private List<AcmeGateway.DnsChallenge> loadDnsChallenges() throws AcmeException {
            List<AcmeGateway.DnsChallenge> result = new ArrayList<>();
            List<Dns01Challenge> challenges = new ArrayList<>();
            for (Authorization authorization : order.getAuthorizations()) {
                if (authorization.getStatus() == Status.VALID) continue;
                if (authorization.getStatus() != Status.PENDING) {
                    throw new AcmeException("ACME authorization is not actionable");
                }
                Dns01Challenge challenge = authorization.findChallenge(Dns01Challenge.class)
                        .orElseThrow(() -> new AcmeException("ACME authorization offers no DNS-01 challenge"));
                String baseDomain = authorization.getIdentifier().getDomain();
                String requestedDomain = authorization.isWildcard() ? "*." + baseDomain : baseDomain;
                result.add(new AcmeGateway.DnsChallenge(requestedDomain,
                        challenge.getRRName(authorization.getIdentifier()), challenge.getDigest()));
                challenges.add(challenge);
            }
            pendingChallenges = List.copyOf(challenges);
            return List.copyOf(result);
        }

        @Override
        public void triggerChallenges() throws AcmeException, InterruptedException {
            if (pendingChallenges == null) throw new AcmeException("DNS-01 challenges were not prepared");
            try {
                AcmePoller.call(clock.instant().plus(networkTimeout), clock, () -> {
                    for (Dns01Challenge challenge : pendingChallenges) challenge.trigger();
                    return null;
                });
            } catch (IOException failure) {
                throw new AcmeException("Cannot trigger DNS-01 challenges", failure);
            }
        }

        @Override
        public void awaitReady(Instant deadline) throws AcmeException, InterruptedException, IOException {
            List<Authorization> authorizations = AcmePoller.call(deadline, clock, order::getAuthorizations);
            for (Authorization authorization : authorizations) {
                poller.await(deadline, Set.of(Status.VALID), Set.of(Status.PENDING, Status.PROCESSING), () -> {
                    Optional<Instant> retry = authorization.fetch();
                    return new AcmePoller.Snapshot(authorization.getStatus(), retry);
                });
            }
            poller.await(deadline, Set.of(Status.READY), Set.of(Status.PENDING, Status.PROCESSING), () -> {
                Optional<Instant> retry = order.fetch();
                return new AcmePoller.Snapshot(order.getStatus(), retry);
            });
        }

        @Override
        public String finalizeAndDownload(KeyPair certificateKey, Instant deadline)
                throws AcmeException, InterruptedException, IOException {
            AcmePoller.call(deadline, clock, () -> { order.execute(certificateKey); return null; });
            poller.await(deadline, Set.of(Status.VALID), Set.of(Status.READY, Status.PROCESSING), () -> {
                Optional<Instant> retry = order.fetch();
                return new AcmePoller.Snapshot(order.getStatus(), retry);
            });
            return AcmePoller.call(deadline, clock, () -> {
                org.shredzone.acme4j.Certificate certificate = order.getCertificate();
                if (certificate == null) throw new AcmeException("ACME order returned no certificate");
                certificate.download();
                StringWriter pem = new StringWriter();
                certificate.writeCertificate(pem);
                return pem.toString();
            });
        }
    }

    private URI directory(AcmeEnvironment environment) {
        return environment == AcmeEnvironment.STAGING ? stagingDirectory : productionDirectory;
    }
}
