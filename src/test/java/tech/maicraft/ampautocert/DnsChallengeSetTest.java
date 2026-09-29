package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DnsChallengeSetTest {
    private static final String RECORD = "_acme-challenge.mydomain.com";
    private static final List<String> VALUES = List.of(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    @Test void keepsApexAndWildcardTxtValuesSimultaneouslyThenDeletesOwnedIds() throws Exception {
        FakeDns dns = new FakeDns();
        DnsChallengeSet lease = DnsChallengeSet.publish(dns, RECORD, VALUES);

        assertEquals(VALUES, new ArrayList<>(dns.active.values()));
        assertEquals(List.of(RECORD, RECORD), dns.createdNames);

        lease.close();
        assertTrue(dns.active.isEmpty());
        int deletes = dns.deleteAttempts;
        lease.close();
        assertEquals(deletes, dns.deleteAttempts, "cleanup must be idempotent");
    }

    @Test void partialCreateFailureCleansOnlyRecordCreatedByThisAttempt() {
        FakeDns dns = new FakeDns();
        dns.failCreateNumber = 2;

        assertThrows(IOException.class, () -> DnsChallengeSet.publish(dns, RECORD, VALUES));

        assertTrue(dns.active.isEmpty());
        assertEquals(1, dns.deleteAttempts);
    }

    @Test void partialCreateAndCleanupFailureRetainsRetryHandle() throws Exception {
        FakeDns dns = new FakeDns();
        dns.failCreateNumber = 2;
        dns.failDeleteOnce = true;

        DnsCleanupException failure = assertThrows(DnsCleanupException.class,
                () -> DnsChallengeSet.publish(dns, RECORD, VALUES));
        assertEquals(1, dns.active.size());

        failure.retryCleanup();
        assertTrue(dns.active.isEmpty());
    }

    @Test void failedCleanupCanBeRetriedWithoutDeletingUnownedRecords() throws Exception {
        FakeDns dns = new FakeDns();
        dns.active.put("ffffffffffffffffffffffffffffffff", "operator-record");
        DnsChallengeSet lease = DnsChallengeSet.publish(dns, RECORD, VALUES);
        dns.failDeleteOnce = true;

        assertThrows(IOException.class, lease::close);
        lease.close();

        assertEquals(Map.of("ffffffffffffffffffffffffffffffff", "operator-record"), dns.active);
    }

    @Test void requiresTwoDistinctChallengeValues() {
        FakeDns dns = new FakeDns();
        assertThrows(IllegalArgumentException.class,
                () -> DnsChallengeSet.publish(dns, RECORD, List.of(VALUES.getFirst(), VALUES.getFirst())));
        assertTrue(dns.active.isEmpty());
    }

    @Test void supportsSingleOrReusedValidAuthorizations() throws Exception {
        FakeDns dns = new FakeDns();
        try (DnsChallengeSet single = DnsChallengeSet.publish(dns, RECORD, List.of(VALUES.getFirst()))) {
            assertEquals(List.of(VALUES.getFirst()), new ArrayList<>(dns.active.values()));
        }
        try (DnsChallengeSet none = DnsChallengeSet.publish(dns, RECORD, List.of())) {
            assertTrue(dns.active.isEmpty());
        }
    }

    @Test void interruptionDuringPublicationCleansCreatedRecordThenRestoresFlag() {
        FakeDns dns = new FakeDns();
        dns.interruptCreateNumber = 2;

        assertThrows(InterruptedException.class, () -> DnsChallengeSet.publish(dns, RECORD, VALUES));
        assertTrue(dns.active.isEmpty());
        assertTrue(Thread.interrupted(), "interrupt flag must be restored after cleanup");
    }

    @Test void interruptionDuringDeleteDoesNotPreventRemainingCleanup() throws Exception {
        FakeDns dns = new FakeDns();
        DnsChallengeSet lease = DnsChallengeSet.publish(dns, RECORD, VALUES);
        dns.interruptDeleteOnce = true;

        assertThrows(IOException.class, lease::close);
        assertEquals(1, dns.active.size(), "the other owned record must still be deleted");
        assertTrue(Thread.interrupted(), "interrupt flag must be restored after best-effort cleanup");

        lease.close();
        assertTrue(dns.active.isEmpty());
    }

    private static final class FakeDns implements DnsProvider {
        private final Map<String, String> active = new LinkedHashMap<>();
        private final List<String> createdNames = new ArrayList<>();
        private int creates;
        private int deleteAttempts;
        private int failCreateNumber = -1;
        private int interruptCreateNumber = -1;
        private boolean failDeleteOnce;
        private boolean interruptDeleteOnce;

        @Override
        public String createTxt(String recordName, String value) throws IOException, InterruptedException {
            creates++;
            if (creates == interruptCreateNumber) throw new InterruptedException("simulated interruption");
            if (creates == failCreateNumber) throw new IOException("simulated create failure");
            String id = String.format("%032x", creates);
            createdNames.add(recordName);
            active.put(id, value);
            return id;
        }

        @Override
        public void deleteTxt(String recordId) throws IOException, InterruptedException {
            deleteAttempts++;
            if (interruptDeleteOnce) {
                interruptDeleteOnce = false;
                throw new InterruptedException("simulated cleanup interruption");
            }
            if (failDeleteOnce) {
                failDeleteOnce = false;
                throw new IOException("simulated cleanup failure");
            }
            active.remove(recordId);
        }
    }
}
