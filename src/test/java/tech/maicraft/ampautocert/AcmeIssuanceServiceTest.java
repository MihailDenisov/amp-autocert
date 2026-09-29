package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class AcmeIssuanceServiceTest {
    private static final String APEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String WILDCARD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String RECORD = "_acme-challenge.maicraft.tech";
    private static final List<String> DOMAINS = List.of("maicraft.tech", "*.maicraft.tech");

    @Test void fakeEndToEndIssuancePublishesBothValuesThenCleansThem() throws Exception {
        Fixture fixture = new Fixture();

        AcmeIssuanceService.IssuedMaterial result = fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test");

        assertEquals(AcmeEnvironment.STAGING, result.environment());
        assertEquals(List.of("maicraft.tech", "*.maicraft.tech"), result.domains());
        assertTrue(result.fullChainPem().contains("BEGIN CERTIFICATE"));
        assertTrue(result.privateKeyPkcs8().length > 0);
        assertTrue(fixture.dns.active.isEmpty());
        assertEquals(List.of("open", "challenges", "trigger", "ready", "finalize"), fixture.gateway.events);
        assertEquals(URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/1"), fixture.state.location.orElseThrow());
        assertTrue(fixture.state.pending.isEmpty());
        assertTrue(fixture.state.history.stream().anyMatch(state -> state.phase() == IssuanceTransaction.Phase.PREPARED));
        assertTrue(fixture.state.history.stream().anyMatch(state -> state.phase() == IssuanceTransaction.Phase.FINALIZED));
    }

    @Test void waitsWithBoundedBackoffUntilBothTxtValuesAreVisible() throws Exception {
        Fixture fixture = new Fixture();
        fixture.resolver.invisibleLookups = 2;

        fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test");

        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), fixture.sleeper.delays);
        assertEquals(3, fixture.resolver.lookups);
    }

    @Test void waitsBeforeFirstLookupAndRetriesTransientResolverFailure() throws Exception {
        Fixture fixture = new Fixture();
        fixture.resolver.failLookups = 1;
        List<String> diagnostics = new ArrayList<>();

        fixture.service(Duration.ofMinutes(2), Duration.ofSeconds(15), true, diagnostics::add)
                .issue(AcmeEnvironment.STAGING, "ops@example.test");

        assertEquals(List.of(Duration.ofSeconds(15), Duration.ofSeconds(1)), fixture.sleeper.delays);
        assertEquals(2, fixture.resolver.lookups);
        String rendered = String.join("\n", diagnostics);
        assertTrue(rendered.contains("Waiting 15 seconds before the first public DNS propagation lookup"));
        assertTrue(rendered.contains("lookup attempt 1 failed safely"));
        assertTrue(rendered.contains("lookup attempt 2 reached resolver consensus"));
        assertFalse(rendered.contains(APEX));
        assertFalse(rendered.contains(WILDCARD));
    }

    @Test void propagationTimeoutStillCleansBothProviderRecords() {
        Fixture fixture = new Fixture();
        fixture.resolver.alwaysInvisible = true;

        IOException failure = assertThrows(IOException.class,
                () -> fixture.service(Duration.ofSeconds(3)).issue(AcmeEnvironment.STAGING, "ops@example.test"));

        assertEquals("DNS-01 propagation timed out", failure.getMessage());
        assertTrue(fixture.dns.active.isEmpty());
        assertFalse(fixture.gateway.events.contains("trigger"));
    }

    @Test void resolverResultArrivingAtDeadlineIsRejectedAndRecordsAreCleaned() {
        Fixture fixture = new Fixture();
        fixture.resolver.lookupDelay = Duration.ofSeconds(3);

        IOException failure = assertThrows(IOException.class,
                () -> fixture.service(Duration.ofSeconds(3)).issue(AcmeEnvironment.STAGING, "ops@example.test"));

        assertEquals("DNS-01 propagation timed out", failure.getMessage());
        assertTrue(fixture.dns.active.isEmpty());
        assertFalse(fixture.gateway.events.contains("trigger"));
    }

    @Test void finalCleanupFailureReturnsRetryHandle() throws Exception {
        Fixture fixture = new Fixture();
        fixture.dns.failDeleteOnce = true;

        DnsCleanupException failure = assertThrows(DnsCleanupException.class,
                () -> fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test"));
        assertEquals(1, fixture.dns.active.size());

        failure.retryCleanup();
        assertTrue(fixture.dns.active.isEmpty());
    }

    @Test void acmeFailureStillCleansBothProviderRecords() {
        Fixture fixture = new Fixture();
        fixture.gateway.failFinalize = true;

        assertThrows(IOException.class,
                () -> fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test"));

        assertTrue(fixture.dns.active.isEmpty());
    }

    @Test void rejectsUnexpectedChallengeBeforeAnyDnsMutation() {
        Fixture fixture = new Fixture();
        fixture.gateway.challenges = List.of(
                new AcmeGateway.DnsChallenge("maicraft.tech", RECORD, APEX),
                new AcmeGateway.DnsChallenge("evil.example", "_acme-challenge.evil.example", WILDCARD));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test"));
        assertTrue(fixture.dns.active.isEmpty());
    }

    @Test void issuedMaterialNeverPrintsPrivateMaterial() throws Exception {
        Fixture fixture = new Fixture();
        var result = fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test");
        String rendered = result.toString();
        assertFalse(rendered.contains(result.fullChainPem()));
        assertFalse(rendered.contains(java.util.Base64.getEncoder().encodeToString(result.privateKeyPkcs8())));
    }

    @Test void reusedValidAuthorizationsRequireNoDnsMutationOrLookup() throws Exception {
        Fixture fixture = new Fixture();
        fixture.gateway.challenges = List.of();

        fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test");

        assertTrue(fixture.dns.active.isEmpty());
        assertEquals(0, fixture.resolver.lookups);
        assertTrue(fixture.gateway.events.contains("finalize"));
    }

    @Test void oneReusedAuthorizationPublishesOnlyThePendingChallenge() throws Exception {
        Fixture fixture = new Fixture();
        fixture.gateway.challenges = List.of(
                new AcmeGateway.DnsChallenge("*.maicraft.tech", RECORD, WILDCARD));

        fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test");

        assertEquals(1, fixture.dns.sequence);
        assertTrue(fixture.dns.active.isEmpty());
    }

    @Test void acceptsAcmeAbsoluteDnsOwnerAndKeepsCanonicalConfiguredOwner() throws Exception {
        Fixture fixture = new Fixture();
        fixture.gateway.challenges = List.of(
                new AcmeGateway.DnsChallenge("maicraft.tech", RECORD + ".", APEX),
                new AcmeGateway.DnsChallenge("*.maicraft.tech", RECORD + ".", WILDCARD));

        fixture.service().issue(AcmeEnvironment.STAGING, "ops@example.test");

        assertEquals(List.of(RECORD, RECORD), fixture.dns.names);
        assertTrue(fixture.dns.active.isEmpty());
    }

    @Test void debugDiagnosticsDescribePhasesWithoutSensitiveValues() throws Exception {
        Fixture fixture = new Fixture();
        List<String> diagnostics = new ArrayList<>();

        fixture.service(Duration.ofMinutes(2), true, diagnostics::add)
                .issue(AcmeEnvironment.STAGING, "ops@example.test");

        String rendered = String.join("\n", diagnostics);
        assertTrue(rendered.contains("Published 2 owned TXT records at " + RECORD));
        assertTrue(rendered.contains("Completed ACME issuance and owned TXT cleanup"));
        assertFalse(rendered.contains(APEX));
        assertFalse(rendered.contains(WILDCARD));
        assertFalse(rendered.contains("ops@example.test"));
        assertFalse(rendered.contains("/acme/acct/"));
        assertFalse(rendered.contains("BEGIN CERTIFICATE"));
    }

    @Test void recoveryFindsRecordCreatedBeforeItsIdWasJournaled() throws Exception {
        Fixture fixture = new Fixture();
        IssuanceTransaction pending = fixture.pending(List.of());
        fixture.state.pending = Optional.of(pending);
        String id = fixture.dns.createOwnedTxt(RECORD, APEX, pending.ownershipMarker());

        fixture.service().recoverInterruptedIssuance(AcmeEnvironment.STAGING);

        assertFalse(fixture.dns.active.containsKey(id));
        assertTrue(fixture.state.pending.isEmpty());
        assertTrue(fixture.gateway.events.isEmpty());
    }

    @Test void recoveryDeletesPersistedAndDiscoveredIdsButPreservesOperatorRecord() throws Exception {
        Fixture fixture = new Fixture();
        IssuanceTransaction pending = fixture.pending(List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        fixture.state.pending = Optional.of(pending);
        fixture.dns.active.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", APEX);
        fixture.dns.ownership.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", pending.ownershipMarker());
        fixture.dns.active.put("ffffffffffffffffffffffffffffffff", "operator-value");

        fixture.service().recoverInterruptedIssuance(AcmeEnvironment.STAGING);

        assertEquals(Map.of("ffffffffffffffffffffffffffffffff", "operator-value"), fixture.dns.active);
        assertTrue(fixture.state.pending.isEmpty());
    }

    @Test void failedRecoveryKeepsJournalForNextAttempt() {
        Fixture fixture = new Fixture();
        IssuanceTransaction pending = fixture.pending(List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        fixture.state.pending = Optional.of(pending);
        fixture.dns.active.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", APEX);
        fixture.dns.ownership.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", pending.ownershipMarker());
        fixture.dns.failDeleteOnce = true;

        assertThrows(IOException.class,
                () -> fixture.service().recoverInterruptedIssuance(AcmeEnvironment.STAGING));

        assertTrue(fixture.state.pending.isPresent());
    }

    @Test void interruptedRecoveryAttemptsEveryOwnedIdThenRestoresInterrupt() {
        Fixture fixture = new Fixture();
        List<String> ids = List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        IssuanceTransaction pending = fixture.pending(ids);
        fixture.state.pending = Optional.of(pending);
        for (String id : ids) {
            fixture.dns.active.put(id, APEX);
            fixture.dns.ownership.put(id, pending.ownershipMarker());
        }
        fixture.dns.interruptDeleteOnce = true;

        assertThrows(IOException.class,
                () -> fixture.service().recoverInterruptedIssuance(AcmeEnvironment.STAGING));

        assertEquals(1, fixture.dns.active.size());
        assertTrue(Thread.interrupted(), "interrupt status must be restored after all cleanup attempts");
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final FakeSleeper sleeper = new FakeSleeper(clock);
        final FakeDns dns = new FakeDns();
        final FakeResolver resolver = new FakeResolver(dns, clock);
        final FakeState state = new FakeState();
        final FakeGateway gateway = new FakeGateway();

        AcmeIssuanceService service() { return service(Duration.ofMinutes(2)); }
        AcmeIssuanceService service(Duration timeout) {
            return new AcmeIssuanceService(state, state, gateway, dns, resolver, clock, sleeper,
                    "maicraft.tech", DOMAINS, RECORD, timeout,
                    () -> "12345678-1234-4234-8234-123456789abc");
        }
        AcmeIssuanceService service(Duration timeout, boolean debugMode,
                                    java.util.function.Consumer<String> diagnostics) {
            return new AcmeIssuanceService(state, state, gateway, dns, resolver, clock, sleeper,
                    "maicraft.tech", DOMAINS, RECORD, timeout, debugMode, diagnostics,
                    () -> "12345678-1234-4234-8234-123456789abc");
        }
        AcmeIssuanceService service(Duration timeout, Duration initialDelay, boolean debugMode,
                                    java.util.function.Consumer<String> diagnostics) {
            return new AcmeIssuanceService(state, state, gateway, dns, resolver, clock, sleeper,
                    "maicraft.tech", DOMAINS, RECORD, timeout, initialDelay, debugMode, diagnostics,
                    () -> "12345678-1234-4234-8234-123456789abc");
        }
        IssuanceTransaction pending(List<String> ids) {
            return new IssuanceTransaction(AcmeEnvironment.STAGING,
                    "12345678-1234-4234-8234-123456789abc", "maicraft.tech",
                    URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/1"),
                    URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/order/1/1"),
                    IssuanceTransaction.Phase.PREPARED, gateway.challenges, ids);
        }
    }

    private static final class FakeState implements AcmeAccountState, IssuanceStateStore {
        final KeyPair key;
        Optional<URI> location = Optional.empty();
        Optional<IssuanceTransaction> pending = Optional.empty();
        final List<IssuanceTransaction> history = new ArrayList<>();
        final ReentrantLock issuanceLock = new ReentrantLock();
        FakeState() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(256);
                key = generator.generateKeyPair();
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
        @Override public KeyPair loadOrCreateKeyPair(AcmeEnvironment environment) { return key; }
        @Override public IssuanceLock acquireIssuanceLock(AcmeEnvironment environment) throws InterruptedException {
            issuanceLock.lockInterruptibly();
            return issuanceLock::unlock;
        }
        @Override public Optional<URI> loadAccountLocation(AcmeEnvironment environment) { return location; }
        @Override public void saveAccountLocation(AcmeEnvironment environment, URI value) { location = Optional.of(value); }
        @Override public Optional<IssuanceTransaction> loadIssuance(AcmeEnvironment environment) { return pending; }
        @Override public void saveIssuance(IssuanceTransaction transaction) {
            pending = Optional.of(transaction);
            history.add(transaction);
        }
        @Override public void clearIssuance(AcmeEnvironment environment) { pending = Optional.empty(); }
    }

    private static final class FakeGateway implements AcmeGateway, AcmeGateway.Order {
        final List<String> events = new ArrayList<>();
        List<DnsChallenge> challenges = List.of(
                new DnsChallenge("maicraft.tech", RECORD, APEX),
                new DnsChallenge("*.maicraft.tech", RECORD, WILDCARD));
        boolean failFinalize;
        @Override public Order openOrder(AcmeEnvironment environment, String email, KeyPair key,
                                         Optional<URI> account, List<String> domains) {
            events.add("open");
            assertEquals(List.of("maicraft.tech", "*.maicraft.tech"), domains);
            return this;
        }
        @Override public URI accountLocation() { return URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/1"); }
        @Override public URI orderLocation() { return URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/order/1/1"); }
        @Override public List<DnsChallenge> dnsChallenges() { events.add("challenges"); return challenges; }
        @Override public void triggerChallenges() { events.add("trigger"); }
        @Override public void awaitReady(Instant deadline) { events.add("ready"); }
        @Override public String finalizeAndDownload(KeyPair key, List<String> domains, Instant deadline) throws IOException {
            events.add("finalize");
            if (failFinalize) throw new IOException("simulated finalize failure");
            return "-----BEGIN CERTIFICATE-----\nZmFrZQ==\n-----END CERTIFICATE-----\n";
        }
    }

    private static final class FakeDns implements RecoverableDnsProvider {
        final Map<String, String> active = new LinkedHashMap<>();
        final Map<String, String> ownership = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();
        int sequence;
        boolean failDeleteOnce;
        boolean interruptDeleteOnce;
        @Override public String createTxt(String name, String value) {
            names.add(name);
            String id = String.format("%032x", ++sequence);
            active.put(id, value);
            return id;
        }
        @Override public String createOwnedTxt(String name, String value, String marker) {
            String id = createTxt(name, value);
            ownership.put(id, marker);
            return id;
        }
        @Override public List<String> findOwnedTxt(String name, String marker) {
            return ownership.entrySet().stream().filter(entry -> entry.getValue().equals(marker))
                    .map(Map.Entry::getKey).filter(active::containsKey).toList();
        }
        @Override public void deleteTxt(String id) throws IOException, InterruptedException {
            if (interruptDeleteOnce) {
                interruptDeleteOnce = false;
                throw new InterruptedException("simulated cleanup interruption");
            }
            if (failDeleteOnce) {
                failDeleteOnce = false;
                throw new IOException("simulated cleanup failure");
            }
            active.remove(id);
            ownership.remove(id);
        }
    }

    private static final class FakeResolver implements DnsTxtResolver {
        final FakeDns dns;
        final MutableClock clock;
        int invisibleLookups;
        int failLookups;
        int lookups;
        boolean alwaysInvisible;
        Duration lookupDelay = Duration.ZERO;
        FakeResolver(FakeDns dns, MutableClock clock) { this.dns = dns; this.clock = clock; }
        @Override public Set<String> resolve(String recordName, Instant deadline) throws IOException {
            lookups++;
            clock.advance(lookupDelay);
            if (lookups <= failLookups) throw new IOException("simulated transient resolver failure");
            if (alwaysInvisible || lookups <= invisibleLookups) return Set.of();
            return new LinkedHashSet<>(dns.active.values());
        }
    }

    private static final class FakeSleeper implements Sleeper {
        final MutableClock clock;
        final List<Duration> delays = new ArrayList<>();
        FakeSleeper(MutableClock clock) { this.clock = clock; }
        @Override public void sleep(Duration duration) { delays.add(duration); clock.advance(duration); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-27T00:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
