package tech.maicraft.ampautocert;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Background-only ACME orchestration. It returns staged material and never deploys live credentials. */
public final class AcmeIssuanceService {
    private final AcmeAccountState accountState;
    private final IssuanceStateStore issuanceState;
    private final AcmeGateway acme;
    private final RecoverableDnsProvider dnsProvider;
    private final DnsTxtResolver dnsResolver;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration propagationTimeout;
    private final Duration propagationInitialDelay;
    private final Supplier<String> transactionIds;
    private final String zoneName;
    private final List<String> domains;
    private final String challengeRecord;
    private final boolean debugMode;
    private final java.util.function.Consumer<String> diagnosticSink;

    public AcmeIssuanceService(AcmeAccountState accountState, IssuanceStateStore issuanceState,
                               AcmeGateway acme, RecoverableDnsProvider dnsProvider,
                               DnsTxtResolver dnsResolver, Clock clock, Sleeper sleeper,
                               AutoCertConfig config) {
        this(accountState, issuanceState, acme, dnsProvider, dnsResolver, clock, sleeper,
                config.zoneName(), config.domains(), config.challengeRecord(), config.dnsPropagationTimeout(),
                config.dnsPropagationInitialDelay(), config.debugMode(), ignored -> { },
                () -> UUID.randomUUID().toString());
    }

    AcmeIssuanceService(AcmeAccountState accountState, IssuanceStateStore issuanceState,
                        AcmeGateway acme, RecoverableDnsProvider dnsProvider,
                        DnsTxtResolver dnsResolver, Clock clock, Sleeper sleeper,
                        AutoCertConfig config, java.util.function.Consumer<String> diagnosticSink) {
        this(accountState, issuanceState, acme, dnsProvider, dnsResolver, clock, sleeper,
                config.zoneName(), config.domains(), config.challengeRecord(), config.dnsPropagationTimeout(),
                config.dnsPropagationInitialDelay(), config.debugMode(), diagnosticSink,
                () -> UUID.randomUUID().toString());
    }

    AcmeIssuanceService(AcmeAccountState accountState, IssuanceStateStore issuanceState,
                        AcmeGateway acme, RecoverableDnsProvider dnsProvider,
                        DnsTxtResolver dnsResolver, Clock clock, Sleeper sleeper,
                        String zoneName, List<String> domains, String challengeRecord,
                        Duration propagationTimeout, Supplier<String> transactionIds) {
        this(accountState, issuanceState, acme, dnsProvider, dnsResolver, clock, sleeper, zoneName, domains,
                challengeRecord, propagationTimeout, Duration.ZERO, false, ignored -> { }, transactionIds);
    }

    AcmeIssuanceService(AcmeAccountState accountState, IssuanceStateStore issuanceState,
                        AcmeGateway acme, RecoverableDnsProvider dnsProvider,
                        DnsTxtResolver dnsResolver, Clock clock, Sleeper sleeper,
                        String zoneName, List<String> domains, String challengeRecord,
                        Duration propagationTimeout, boolean debugMode,
                        java.util.function.Consumer<String> diagnosticSink, Supplier<String> transactionIds) {
        this(accountState, issuanceState, acme, dnsProvider, dnsResolver, clock, sleeper, zoneName, domains,
                challengeRecord, propagationTimeout, Duration.ZERO, debugMode, diagnosticSink, transactionIds);
    }

    AcmeIssuanceService(AcmeAccountState accountState, IssuanceStateStore issuanceState,
                        AcmeGateway acme, RecoverableDnsProvider dnsProvider,
                        DnsTxtResolver dnsResolver, Clock clock, Sleeper sleeper,
                        String zoneName, List<String> domains, String challengeRecord,
                        Duration propagationTimeout, Duration propagationInitialDelay, boolean debugMode,
                        java.util.function.Consumer<String> diagnosticSink, Supplier<String> transactionIds) {
        if (propagationTimeout.isZero() || propagationTimeout.isNegative()) {
            throw new IllegalArgumentException("DNS propagation timeout must be positive");
        }
        if (propagationInitialDelay.isNegative() || propagationInitialDelay.compareTo(propagationTimeout) >= 0) {
            throw new IllegalArgumentException("DNS propagation initial delay must be non-negative and less than timeout");
        }
        this.accountState = accountState;
        this.issuanceState = issuanceState;
        this.acme = acme;
        this.dnsProvider = dnsProvider;
        this.dnsResolver = dnsResolver;
        this.clock = clock;
        this.sleeper = sleeper;
        this.propagationTimeout = propagationTimeout;
        this.propagationInitialDelay = propagationInitialDelay;
        this.transactionIds = transactionIds;
        this.zoneName = zoneName;
        this.domains = List.copyOf(domains);
        this.challengeRecord = challengeRecord;
        this.debugMode = debugMode;
        this.diagnosticSink = java.util.Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    public IssuedMaterial issue(AcmeEnvironment environment, String contactEmail)
            throws IOException, InterruptedException {
        try (IssuanceStateStore.IssuanceLock ignored = issuanceState.acquireIssuanceLock(environment)) {
            return issueLocked(environment, contactEmail);
        }
    }

    private IssuedMaterial issueLocked(AcmeEnvironment environment, String contactEmail)
            throws IOException, InterruptedException {
        diagnostic("Starting " + environment + " ACME issuance for " + domains.size() + " configured identifiers.");
        recoverInterruptedIssuanceLocked(environment);
        KeyPair accountKey = accountState.loadOrCreateKeyPair(environment);
        AcmeGateway.Order order = acme.openOrder(environment, contactEmail, accountKey,
                accountState.loadAccountLocation(environment), domains);
        accountState.saveAccountLocation(environment, order.accountLocation());
        List<AcmeGateway.DnsChallenge> challenges = validateChallenges(order.dnsChallenges());
        diagnostic("ACME order returned " + challenges.size() + " pending DNS-01 challenges.");
        List<String> values = challenges.stream().map(AcmeGateway.DnsChallenge::value).toList();
        AtomicReference<IssuanceTransaction> transaction = new AtomicReference<>(new IssuanceTransaction(
                environment, transactionIds.get(), zoneName, order.accountLocation(), order.orderLocation(),
                IssuanceTransaction.Phase.PREPARED, challenges, List.of()));
        issuanceState.saveIssuance(transaction.get());
        Instant deadline = clock.instant().plus(propagationTimeout);
        IssuedMaterial result;
        try (DnsChallengeSet ignored = DnsChallengeSet.publishRecoverable(dnsProvider, challengeRecord, values,
                transaction.get().ownershipMarker(), ids -> updateRecordIds(transaction, ids))) {
            diagnostic("Published " + values.size() + " owned TXT records at " + challengeRecord
                    + " without logging their values.");
            updatePhase(transaction, IssuanceTransaction.Phase.DNS_PUBLISHED);
            if (!values.isEmpty() && !propagationInitialDelay.isZero()) {
                diagnostic("Waiting " + propagationInitialDelay.toSeconds()
                        + " seconds before the first public DNS propagation lookup.");
                sleepWithinDeadline(propagationInitialDelay, deadline);
            }
            awaitPropagation(values, deadline);
            diagnostic("Observed all configured DNS-01 values before the propagation deadline.");
            order.triggerChallenges();
            diagnostic("Triggered " + challenges.size() + " ACME DNS-01 challenges.");
            updatePhase(transaction, IssuanceTransaction.Phase.CHALLENGES_TRIGGERED);
            Instant acmeDeadline = clock.instant().plus(propagationTimeout);
            order.awaitReady(acmeDeadline);
            diagnostic("ACME order reached ready state.");
            updatePhase(transaction, IssuanceTransaction.Phase.ORDER_READY);
            KeyPair certificateKey = generateCertificateKey();
            String fullChain = order.finalizeAndDownload(certificateKey, domains, acmeDeadline);
            if (fullChain == null || fullChain.isBlank()) throw new IOException("ACME returned an empty certificate chain");
            diagnostic("Downloaded a non-empty certificate chain; certificate and key bytes remain redacted.");
            updatePhase(transaction, IssuanceTransaction.Phase.FINALIZED);
            result = new IssuedMaterial(environment, fullChain, certificateKey.getPrivate().getEncoded(), domains);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            diagnostic(hasCleanupFailure(failure)
                    ? "Issuance stopped and owned TXT cleanup was incomplete; the recovery journal was retained."
                    : "Issuance stopped after guarded owned TXT cleanup; the recovery journal was retained for verification.");
            throw failure;
        }
        issuanceState.clearIssuance(environment);
        diagnostic("Completed ACME issuance and owned TXT cleanup.");
        return result;
    }

    /** Abandons an interrupted order safely; certificate keys are intentionally never journaled before validation. */
    public void recoverInterruptedIssuance(AcmeEnvironment environment) throws IOException, InterruptedException {
        try (IssuanceStateStore.IssuanceLock ignored = issuanceState.acquireIssuanceLock(environment)) {
            recoverInterruptedIssuanceLocked(environment);
        }
    }

    private void recoverInterruptedIssuanceLocked(AcmeEnvironment environment)
            throws IOException, InterruptedException {
        IssuanceTransaction transaction = issuanceState.loadIssuance(environment).orElse(null);
        if (transaction == null) return;
        diagnostic("Recovering an interrupted " + environment + " issuance with "
                + transaction.recordIds().size() + " journaled DNS record ID(s).");
        LinkedHashSet<String> ownedIds = new LinkedHashSet<>(transaction.recordIds());
        boolean restoreInterrupt = Thread.interrupted();
        try {
            ownedIds.addAll(dnsProvider.findOwnedTxt("_acme-challenge." + transaction.zoneName(), transaction.ownershipMarker()));
            diagnostic("Recovery identified " + ownedIds.size() + " owned DNS record(s) in total.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        IOException cleanupFailure = null;
        for (String id : ownedIds) {
            try {
                dnsProvider.deleteTxt(id);
            } catch (InterruptedException failure) {
                restoreInterrupt = true;
                Thread.interrupted();
                if (cleanupFailure == null) cleanupFailure = new IOException("Interrupted while recovering DNS-01 cleanup");
                cleanupFailure.addSuppressed(failure);
            } catch (IOException failure) {
                if (cleanupFailure == null) cleanupFailure = new IOException("Cannot recover DNS-01 cleanup");
                cleanupFailure.addSuppressed(failure);
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
        if (cleanupFailure != null) {
            diagnostic("Recovery cleanup was incomplete; the issuance journal remains available for retry.");
            throw cleanupFailure;
        }
        issuanceState.clearIssuance(environment);
        diagnostic("Recovery deleted all owned DNS records and cleared the issuance journal.");
    }

    private void updateRecordIds(AtomicReference<IssuanceTransaction> reference, List<String> ids) throws IOException {
        IssuanceTransaction updated = reference.get().withRecordIds(ids);
        issuanceState.saveIssuance(updated);
        reference.set(updated);
    }

    private void updatePhase(AtomicReference<IssuanceTransaction> reference, IssuanceTransaction.Phase phase)
            throws IOException {
        IssuanceTransaction updated = reference.get().withPhase(phase);
        issuanceState.saveIssuance(updated);
        reference.set(updated);
    }

    private void awaitPropagation(List<String> expected, Instant deadline)
            throws IOException, InterruptedException {
        if (expected.isEmpty()) return;
        Duration delay = Duration.ofSeconds(1);
        IOException lastLookupFailure = null;
        int attempt = 0;
        while (true) {
            Instant beforeLookup = clock.instant();
            if (!beforeLookup.isBefore(deadline)) throw new IOException("DNS-01 propagation timed out");
            attempt++;
            diagnostic("DNS propagation lookup attempt " + attempt + " started with "
                    + Math.max(0L, Duration.between(beforeLookup, deadline).toSeconds()) + " second(s) remaining.");
            Set<String> visible;
            try {
                visible = dnsResolver.resolve(challengeRecord, deadline);
            } catch (IOException failure) {
                lastLookupFailure = failure;
                visible = Set.of();
                diagnostic("DNS propagation lookup attempt " + attempt
                        + " failed safely; retrying within the configured deadline.");
            }
            if (!clock.instant().isBefore(deadline)) throw new IOException("DNS-01 propagation timed out");
            if (visible == null) throw new IOException("DNS resolver returned no result set");
            diagnostic("DNS propagation lookup attempt " + attempt + " observed " + visible.size()
                    + " TXT value(s); " + expected.size() + " required value(s) remain protected.");
            if (visible.containsAll(expected)) {
                diagnostic("DNS propagation lookup attempt " + attempt + " reached resolver consensus.");
                return;
            }
            Instant now = clock.instant();
            if (!now.isBefore(deadline)) throw propagationTimeout(lastLookupFailure);
            Duration remaining = Duration.between(now, deadline);
            Duration actualDelay = delay.compareTo(remaining) < 0 ? delay : remaining;
            diagnostic("DNS propagation retry delay is " + actualDelay.toSeconds() + " second(s).");
            sleeper.sleep(actualDelay);
            delay = delay.multipliedBy(2);
            if (delay.compareTo(Duration.ofSeconds(30)) > 0) delay = Duration.ofSeconds(30);
        }
    }

    private void sleepWithinDeadline(Duration requested, Instant deadline) throws IOException, InterruptedException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) throw new IOException("DNS-01 propagation timed out");
        if (requested.compareTo(remaining) >= 0) throw new IOException("DNS-01 propagation timed out");
        sleeper.sleep(requested);
    }

    private static IOException propagationTimeout(IOException cause) {
        IOException timeout = new IOException("DNS-01 propagation timed out");
        if (cause != null) timeout.addSuppressed(cause);
        return timeout;
    }

    private static boolean hasCleanupFailure(Throwable failure) {
        if (failure instanceof DnsCleanupException) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof DnsCleanupException) return true;
        }
        return false;
    }

    private List<AcmeGateway.DnsChallenge> validateChallenges(List<AcmeGateway.DnsChallenge> challenges) {
        if (challenges == null || challenges.size() > 2) {
            throw new IllegalArgumentException("ACME order returned too many DNS-01 challenges");
        }
        Set<String> challengeDomains = new HashSet<>();
        Set<String> values = new HashSet<>();
        List<AcmeGateway.DnsChallenge> validated = new ArrayList<>(2);
        for (AcmeGateway.DnsChallenge challenge : challenges) {
            if (!Set.copyOf(this.domains).contains(challenge.requestedDomain())) {
                throw new IllegalArgumentException("ACME returned a challenge outside the domain allowlist");
            }
            String recordName = canonicalDnsOwner(challenge.recordName());
            if (!challengeRecord.equals(recordName)) {
                throw new IllegalArgumentException("ACME returned an unexpected DNS owner name");
            }
            if (challenge.value() == null || !challenge.value().matches("[A-Za-z0-9_-]{20,255}")) {
                throw new IllegalArgumentException("ACME returned an invalid DNS-01 value");
            }
            challengeDomains.add(challenge.requestedDomain());
            values.add(challenge.value());
            validated.add(new AcmeGateway.DnsChallenge(challenge.requestedDomain(), recordName, challenge.value()));
        }
        if (challengeDomains.size() != challenges.size() || values.size() != challenges.size()) {
            throw new IllegalArgumentException("ACME challenges must have distinct allowlisted domains and values");
        }
        return validated;
    }

    private static String canonicalDnsOwner(String value) {
        if (value == null || value.isBlank()) return "";
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private void diagnostic(String message) {
        if (debugMode) diagnosticSink.accept(message);
    }

    private static KeyPair generateCertificateKey() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IOException("Cannot generate certificate key", failure);
        }
    }

    public record IssuedMaterial(AcmeEnvironment environment, String fullChainPem,
                                 byte[] privateKeyPkcs8, List<String> domains) {
        public IssuedMaterial {
            privateKeyPkcs8 = privateKeyPkcs8.clone();
            domains = List.copyOf(domains);
        }
        @Override public byte[] privateKeyPkcs8() { return privateKeyPkcs8.clone(); }
        @Override public String toString() {
            return "IssuedMaterial[environment=" + environment + ", fullChainPem=<redacted>, privateKeyPkcs8=<redacted>, domains=" + domains + "]";
        }
    }
}
