package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CertificateDeploymentServiceTest {
    @TempDir Path root;

    private final AtomicInteger transactionSequence = new AtomicInteger();

    @Test void validationFailureTouchesNoCredentialFiles() throws Exception {
        CertificateDeploymentService service = service(material -> {
            throw new GeneralSecurityException("fixture rejected");
        }, point -> { });

        assertThrows(GeneralSecurityException.class, () -> service.prepare(material()));
        assertFalse(Files.exists(root.resolve("automodpack")));
    }

    @Test void firstInstallationCommitsBothFilesAndClearsRecoveryState() throws Exception {
        CertificateDeploymentService service = service(valid(), point -> { });

        CertificateDeploymentService.PendingDeployment pending = service.prepare(material());
        assertEquals("NEW CERTIFICATE\n", Files.readString(certificate()));
        assertTrue(Files.readString(privateKey()).startsWith("-----BEGIN PRIVATE KEY-----\n"));
        pending.commit();

        assertFalse(Files.exists(journal()));
        assertNoTransactionDirectories();
    }

    @Test void closingPendingDeploymentRestoresExistingPair() throws Exception {
        writeExistingPair();
        CertificateDeploymentService service = service(valid(), point -> { });

        CertificateDeploymentService.PendingDeployment pending = service.prepare(material());
        try (pending) {
            assertEquals("NEW CERTIFICATE\n", Files.readString(certificate()));
        }

        assertEquals("OLD CERTIFICATE\n", Files.readString(certificate()));
        assertEquals("OLD PRIVATE KEY\n", Files.readString(privateKey()));
        assertTrue(Files.exists(journal()));
        pending.completeRollbackReconciliation();
        assertFalse(Files.exists(journal()));
    }

    @Test void ordinaryFailureAfterFirstReplacementRollsBackBothFiles() throws Exception {
        writeExistingPair();
        CertificateDeploymentService service = service(valid(), point -> {
            if (point == CertificateDeploymentService.ReplacementPoint.PRIVATE_KEY_REPLACED) {
                throw new IOException("injected replacement failure");
            }
        });

        IOException failure = assertThrows(IOException.class, () -> service.prepare(material()));

        assertTrue(failure.getMessage().contains("injected"));
        assertEquals("OLD CERTIFICATE\n", Files.readString(certificate()));
        assertEquals("OLD PRIVATE KEY\n", Files.readString(privateKey()));
        assertTrue(Files.exists(journal()));
        service.completeRecoveryReconciliation();
        assertFalse(Files.exists(journal()));
    }

    @Test void recoveryRollsBackCrashAfterBothReplacements() throws Exception {
        writeExistingPair();
        CertificateDeploymentService crashing = service(valid(), point -> {
            if (point == CertificateDeploymentService.ReplacementPoint.CERTIFICATE_REPLACED) {
                throw new CertificateDeploymentService.SimulatedCrashException("simulated process exit");
            }
        });

        assertThrows(CertificateDeploymentService.SimulatedCrashException.class,
                () -> crashing.prepare(material()));
        assertEquals("NEW CERTIFICATE\n", Files.readString(certificate()));
        assertTrue(Files.exists(journal()));

        CertificateDeploymentService recovering = service(valid(), point -> { });
        var recovery = recovering.recoverAndReport();

        assertTrue(recovery.credentialsChanged());
        assertTrue(recovery.hasCertificatePair());
        assertEquals("OLD CERTIFICATE\n", Files.readString(certificate()));
        assertEquals("OLD PRIVATE KEY\n", Files.readString(privateKey()));
        assertTrue(Files.exists(journal()));
        assertThrows(IOException.class, () -> recovering.prepare(material()));
        recovering.completeRecoveryReconciliation();
        assertFalse(Files.exists(journal()));
    }

    @Test void recoveryRemovesPartialFirstInstallationAfterCrash() throws Exception {
        CertificateDeploymentService crashing = service(valid(), point -> {
            if (point == CertificateDeploymentService.ReplacementPoint.PRIVATE_KEY_REPLACED) {
                throw new CertificateDeploymentService.SimulatedCrashException("simulated process exit");
            }
        });

        assertThrows(CertificateDeploymentService.SimulatedCrashException.class,
                () -> crashing.prepare(material()));
        assertTrue(Files.exists(privateKey()));
        assertFalse(Files.exists(certificate()));

        CertificateDeploymentService recovering = service(valid(), point -> { });
        var recovery = recovering.recoverAndReport();

        assertTrue(recovery.credentialsChanged());
        assertFalse(recovery.hasCertificatePair());
        assertFalse(Files.exists(privateKey()));
        assertFalse(Files.exists(certificate()));
        assertTrue(Files.exists(journal()));
        recovering.completeRecoveryReconciliation();
        assertFalse(Files.exists(journal()));
    }

    @Test void recoveryClearsCommittedJournalWhenCleanupFinishedBeforeCrash() throws Exception {
        CertificateDeploymentService crashing = service(valid(), point -> {
            if (point == CertificateDeploymentService.ReplacementPoint.COMMIT_CLEANUP_COMPLETE) {
                throw new CertificateDeploymentService.SimulatedCrashException("simulated process exit");
            }
        });
        CertificateDeploymentService.PendingDeployment pending = crashing.prepare(material());

        assertThrows(CertificateDeploymentService.SimulatedCrashException.class, pending::commit);
        assertTrue(Files.exists(journal()));
        assertNoTransactionDirectories();

        var recovery = service(valid(), point -> { }).recoverAndReport();

        assertFalse(recovery.credentialsChanged());
        assertEquals("NEW CERTIFICATE\n", Files.readString(certificate()));
        assertTrue(Files.exists(privateKey()));
        assertFalse(Files.exists(journal()));
    }

    @Test void commitJournalFailureRollsBackAndDoesNotDisableClose() throws Exception {
        writeExistingPair();
        CertificateDeploymentService service = service(valid(), point -> {
            if (point == CertificateDeploymentService.ReplacementPoint.BEFORE_COMMIT_JOURNAL) {
                throw new IOException("injected commit journal failure");
            }
        });
        CertificateDeploymentService.PendingDeployment pending = service.prepare(material());

        IOException failure = assertThrows(IOException.class, pending::commit);
        pending.close();

        assertTrue(failure.getMessage().contains("commit journal"));
        assertEquals("OLD CERTIFICATE\n", Files.readString(certificate()));
        assertEquals("OLD PRIVATE KEY\n", Files.readString(privateKey()));
        assertTrue(Files.exists(journal()));
        pending.completeRollbackReconciliation();
        assertFalse(Files.exists(journal()));
    }

    @Test void failureAfterCommittedJournalRenamePersistsRollbackIntentBeforeRestore() throws Exception {
        writeExistingPair();
        CertificateDeploymentService service = service(valid(), point -> {
            if (point == CertificateDeploymentService.ReplacementPoint.COMMIT_JOURNAL_REPLACED) {
                throw new IOException("injected post-journal-rename failure");
            }
        });
        CertificateDeploymentService.PendingDeployment pending = service.prepare(material());

        IOException failure = assertThrows(IOException.class, pending::commit);

        assertTrue(failure.getMessage().contains("post-journal-rename"));
        assertEquals("OLD CERTIFICATE\n", Files.readString(certificate()));
        assertEquals("OLD PRIVATE KEY\n", Files.readString(privateKey()));
        assertTrue(Files.exists(journal()));
        pending.completeRollbackReconciliation();
        assertFalse(Files.exists(journal()));
    }

    @Test void pendingDeploymentCanBeFinalizedFromAnotherThread() throws Exception {
        CertificateDeploymentService.PendingDeployment pending = service(valid(), point -> { }).prepare(material());
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { pending.commit(); } catch (Throwable thrown) { failure.set(thrown); }
        });
        thread.join();

        assertNull(failure.get());
        assertEquals("NEW CERTIFICATE\n", Files.readString(certificate()));
        assertFalse(Files.exists(journal()));
    }

    @Test void refusesToOverwriteAnIncompleteExistingPair() throws Exception {
        Files.createDirectories(certificate().getParent());
        Files.writeString(certificate(), "orphan certificate");

        IOException failure = assertThrows(IOException.class,
                () -> service(valid(), point -> { }).prepare(material()));

        assertTrue(failure.getMessage().contains("incomplete existing"));
        assertEquals("orphan certificate", Files.readString(certificate()));
    }

    @Test void rejectsSymlinkedLiveCredentialWhenSupported() throws Exception {
        Files.createDirectories(certificate().getParent());
        Path outside = Files.writeString(root.resolve("outside.crt"), "outside");
        try {
            Files.createSymbolicLink(certificate(), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        assertThrows(IOException.class, () -> service(valid(), point -> { }).prepare(material()));
        assertEquals("outside", Files.readString(outside));
    }

    private CertificateDeploymentService service(CertificateDeploymentService.MaterialValidator validator,
                                                 CertificateDeploymentService.DeploymentFaultInjector injector)
            throws IOException {
        return new CertificateDeploymentService(root, validator, false, () -> new UUID(0,
                transactionSequence.incrementAndGet()).toString(), injector);
    }

    private static CertificateDeploymentService.MaterialValidator valid() {
        return material -> new IssuedCertificateValidator.ValidationResult(
                Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-12-26T00:00:00Z"),
                List.of("mydomain.com", "*.mydomain.com"));
    }

    private static AcmeIssuanceService.IssuedMaterial material() {
        return new AcmeIssuanceService.IssuedMaterial(AcmeEnvironment.PRODUCTION,
                "NEW CERTIFICATE\n", new byte[]{1, 2, 3, 4},
                List.of("mydomain.com", "*.mydomain.com"));
    }

    private void writeExistingPair() throws IOException {
        Files.createDirectories(certificate().getParent());
        Files.writeString(certificate(), "OLD CERTIFICATE\n", StandardCharsets.US_ASCII);
        Files.writeString(privateKey(), "OLD PRIVATE KEY\n", StandardCharsets.US_ASCII);
    }

    private Path certificate() {
        return root.resolve("automodpack/credentials/certificate.crt");
    }

    private Path privateKey() {
        return root.resolve("automodpack/credentials/private-key.pem");
    }

    private Path journal() {
        return root.resolve("automodpack/credentials/amp-autocert/deployment/pending-deployment.state");
    }

    private void assertNoTransactionDirectories() throws IOException {
        Path deployment = journal().getParent();
        try (var entries = Files.list(deployment)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().startsWith("tx-")));
        }
    }
}
