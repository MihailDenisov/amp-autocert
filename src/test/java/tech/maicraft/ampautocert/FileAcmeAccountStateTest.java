package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileAcmeAccountStateTest {
    @TempDir Path root;

    @Test void persistsIdentityAndSeparatesStagingFromProduction() throws Exception {
        FileAcmeAccountState state = state();
        var staging = state.loadOrCreateKeyPair(AcmeEnvironment.STAGING);
        var stagingAgain = state.loadOrCreateKeyPair(AcmeEnvironment.STAGING);
        var production = state.loadOrCreateKeyPair(AcmeEnvironment.PRODUCTION);

        assertArrayEquals(staging.getPrivate().getEncoded(), stagingAgain.getPrivate().getEncoded());
        assertFalse(Arrays.equals(staging.getPrivate().getEncoded(), production.getPrivate().getEncoded()));
        assertTrue(Files.exists(root.resolve("staging/account-key.pem")));
        assertTrue(Files.exists(root.resolve("production/account-key.pem")));
    }

    @Test void accountLocationsAreSeparatedAndMustUseHttps() throws Exception {
        FileAcmeAccountState state = state();
        URI staging = URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/123");
        state.saveAccountLocation(AcmeEnvironment.STAGING, staging);

        assertEquals(staging, state.loadAccountLocation(AcmeEnvironment.STAGING).orElseThrow());
        assertTrue(state.loadAccountLocation(AcmeEnvironment.PRODUCTION).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> state.saveAccountLocation(AcmeEnvironment.PRODUCTION, URI.create("http://example.test/acct/1")));
    }

    @Test void rejectsSymlinkedEnvironmentDirectoryWhenSupported() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("staging"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        assertThrows(IOException.class, () -> state().loadOrCreateKeyPair(AcmeEnvironment.STAGING));
    }

    @Test void rejectsExistingAccountKeyWithBroadPosixPermissionsWhenSupported() throws Exception {
        FileAcmeAccountState state = state();
        state.loadOrCreateKeyPair(AcmeEnvironment.STAGING);
        Path key = root.resolve("staging/account-key.pem");
        if (!Files.getFileStore(key).supportsFileAttributeView("posix")) return;

        Files.setPosixFilePermissions(key, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));

        assertThrows(IOException.class, () -> state.loadOrCreateKeyPair(AcmeEnvironment.STAGING));
    }

    @Test void concurrentInstancesKeepOneAccountIdentity() throws Exception {
        FileAcmeAccountState first = state();
        FileAcmeAccountState second = state();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); return first.loadOrCreateKeyPair(AcmeEnvironment.STAGING); });
            var two = executor.submit(() -> { start.await(); return second.loadOrCreateKeyPair(AcmeEnvironment.STAGING); });
            start.countDown();
            assertArrayEquals(one.get().getPrivate().getEncoded(), two.get().getPrivate().getEncoded());
        }
    }

    @Test void persistsAndClearsEnvironmentSeparatedIssuanceJournal() throws Exception {
        FileAcmeAccountState state = state();
        IssuanceTransaction transaction = new IssuanceTransaction(AcmeEnvironment.STAGING,
                "12345678-1234-4234-8234-123456789abc", "maicraft.tech",
                URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/acct/1"),
                URI.create("https://acme-staging-v02.api.letsencrypt.org/acme/order/1/2"),
                IssuanceTransaction.Phase.DNS_PUBLISHED,
                List.of(new AcmeGateway.DnsChallenge("maicraft.tech", "_acme-challenge.maicraft.tech",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        state.saveIssuance(transaction);

        assertEquals(transaction, state.loadIssuance(AcmeEnvironment.STAGING).orElseThrow());
        assertTrue(state.loadIssuance(AcmeEnvironment.PRODUCTION).isEmpty());
        state.clearIssuance(AcmeEnvironment.STAGING);
        assertTrue(state.loadIssuance(AcmeEnvironment.STAGING).isEmpty());
    }

    @Test void issuanceLockSerializesIndependentStateInstances() throws Exception {
        FileAcmeAccountState first = state();
        FileAcmeAccountState second = state();
        try (IssuanceStateStore.IssuanceLock held = first.acquireIssuanceLock(AcmeEnvironment.STAGING);
             var executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> {
                try (IssuanceStateStore.IssuanceLock acquired = second.acquireIssuanceLock(AcmeEnvironment.STAGING)) {
                    assertNotNull(acquired);
                }
                return null;
            });
            Thread.sleep(100);
            assertFalse(waiting.isDone());
            held.close();
            waiting.get();
        }
    }

    @Test void productionAttemptCooldownPersistsAcrossInstances() throws Exception {
        Instant firstAttempt = Instant.parse("2026-09-28T12:00:00Z");
        Duration cooldown = Duration.ofHours(48);

        assertTrue(state().reserveRenewalAttempt(AcmeEnvironment.PRODUCTION,
                firstAttempt, cooldown).isEmpty());
        assertEquals(firstAttempt.plus(cooldown), state().reserveRenewalAttempt(AcmeEnvironment.PRODUCTION,
                firstAttempt.plus(Duration.ofHours(1)), cooldown).orElseThrow());
        assertTrue(state().reserveRenewalAttempt(AcmeEnvironment.PRODUCTION,
                firstAttempt.plus(cooldown), cooldown).isEmpty());
    }

    private FileAcmeAccountState state() throws IOException {
        return new FileAcmeAccountState(root);
    }
}
