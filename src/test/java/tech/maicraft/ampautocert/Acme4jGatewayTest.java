package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Acme4jGatewayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant DEADLINE = Instant.parse("2026-09-27T00:05:00Z");
    private static final List<String> DOMAINS = List.of("mydomain.com", "*.mydomain.com");
    private static final URI STAGING = URI.create("https://acme-staging-v02.api.letsencrypt.org/directory");
    private static final URI PRODUCTION = URI.create("https://acme-v02.api.letsencrypt.org/directory");
    private static final String RECORD = "_acme-challenge.mydomain.com";

    @Test void delegatesCompleteProductionGatewayFlow() throws Exception {
        FakeClient client = new FakeClient();
        Acme4jGateway gateway = new Acme4jGateway(client, CLOCK, DOMAINS);
        KeyPair accountKey = keyPair();
        URI saved = URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/123");

        AcmeGateway.Order order = gateway.openOrder(AcmeEnvironment.STAGING, "ops@example.test",
                accountKey, Optional.of(saved), DOMAINS);
        assertSame(accountKey, client.accountKey);
        assertEquals(AcmeEnvironment.STAGING, client.environment);
        assertEquals(Optional.of(saved), client.savedLocation);
        assertEquals(client.order.accountLocation(), order.accountLocation());
        assertEquals(client.order.challenges, order.dnsChallenges());

        order.triggerChallenges();
        order.awaitReady(DEADLINE);
        String pem = order.finalizeAndDownload(keyPair(), DOMAINS, DEADLINE);

        assertTrue(client.order.triggered);
        assertEquals(DEADLINE, client.order.readyDeadline);
        assertEquals(DEADLINE, client.order.finalizeDeadline);
        assertEquals("certificate-chain", pem);
    }

    @Test void rejectsAnyDomainExpansionBeforeNetworkUse() throws Exception {
        FakeClient client = new FakeClient();
        Acme4jGateway gateway = new Acme4jGateway(client, CLOCK, DOMAINS);

        assertThrows(IllegalArgumentException.class, () -> gateway.openOrder(AcmeEnvironment.STAGING,
                "ops@example.test", keyPair(), Optional.empty(), List.of("evil.example")));
        assertFalse(client.opened);
    }

    @Test void expiredDeadlineNeverCallsRemoteOrder() throws Exception {
        FakeClient client = new FakeClient();
        AcmeGateway.Order order = new Acme4jGateway(client, CLOCK, DOMAINS).openOrder(AcmeEnvironment.STAGING,
                "ops@example.test", keyPair(), Optional.empty(), DOMAINS);

        assertThrows(IOException.class, () -> order.awaitReady(CLOCK.instant()));
        assertNull(client.order.readyDeadline);
        assertThrows(IOException.class,
                () -> order.finalizeAndDownload(keyPair(), DOMAINS, CLOCK.instant()));
        assertNull(client.order.finalizeDeadline);
    }

    @Test void acmeErrorsUseOperationMessageInsteadOfRemoteDetails() throws Exception {
        FakeClient client = new FakeClient();
        client.failOpen = true;

        IOException failure = assertThrows(IOException.class,
                () -> new Acme4jGateway(client, CLOCK, DOMAINS).openOrder(AcmeEnvironment.STAGING,
                        "ops@example.test", keyPair(), Optional.empty(), DOMAINS));

        assertEquals("Cannot open ACME order (AcmeException)", failure.getMessage());
        assertFalse(failure.toString().contains("sensitive-remote-detail"));
    }

    @Test void lazyLoadingErrorsAreAlsoRedacted() throws Exception {
        FakeClient client = new FakeClient();
        client.order.failLazy = true;
        AcmeGateway.Order order = new Acme4jGateway(client, CLOCK, DOMAINS).openOrder(AcmeEnvironment.STAGING,
                "ops@example.test", keyPair(), Optional.empty(), DOMAINS);

        IOException failure = assertThrows(IOException.class, order::dnsChallenges);

        assertTrue(failure.getMessage().contains("AcmeLazyLoadingException"));
        assertFalse(failure.toString().contains("sensitive-remote-detail"));
    }

    @Test void persistedAccountLocationIsPinnedToSelectedEnvironment() {
        URI staging = URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/123");
        assertEquals(staging, DefaultAcme4jClient.validateAccountLocation(STAGING, staging));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultAcme4jClient.validateAccountLocation(PRODUCTION, staging));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultAcme4jClient.validateAccountLocation(STAGING,
                        URI.create("https://example.test/acme/acct/123")));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultAcme4jClient.validateAccountLocation(STAGING,
                        URI.create("https://acme-staging-v02.api.letsencrypt.org:8443/acme/acct/123")));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static final class FakeClient implements Acme4jClient {
        final FakeRemoteOrder order = new FakeRemoteOrder();
        boolean opened;
        boolean failOpen;
        AcmeEnvironment environment;
        KeyPair accountKey;
        Optional<URI> savedLocation;

        @Override
        public RemoteOrder open(AcmeEnvironment environment, String email, KeyPair accountKey,
                                Optional<URI> accountLocation, List<String> domains) throws AcmeException {
            opened = true;
            if (failOpen) throw new AcmeException("sensitive-remote-detail");
            this.environment = environment;
            this.accountKey = accountKey;
            this.savedLocation = accountLocation;
            assertEquals(DOMAINS, domains);
            return order;
        }
    }

    private static final class FakeRemoteOrder implements Acme4jClient.RemoteOrder {
        final List<AcmeGateway.DnsChallenge> challenges = List.of(
                new AcmeGateway.DnsChallenge("mydomain.com", RECORD,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        boolean triggered;
        Instant readyDeadline;
        Instant finalizeDeadline;
        boolean failLazy;

        @Override public URI accountLocation() { return URI.create("https://acme.test/acct/1"); }
        @Override public URI orderLocation() { return URI.create("https://acme.test/order/1"); }
        @Override public List<AcmeGateway.DnsChallenge> dnsChallenges() {
            if (failLazy) {
                try {
                    throw new AcmeLazyLoadingException(org.shredzone.acme4j.Account.class,
                            URI.create("https://acme.test/acct/1").toURL(),
                            new AcmeException("sensitive-remote-detail"));
                } catch (java.net.MalformedURLException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            return challenges;
        }
        @Override public void triggerChallenges() { triggered = true; }
        @Override public void awaitReady(Instant deadline) { readyDeadline = deadline; }
        @Override public String finalizeAndDownload(KeyPair certificateKey, Instant deadline) {
            finalizeDeadline = deadline;
            return "certificate-chain";
        }
    }
}
