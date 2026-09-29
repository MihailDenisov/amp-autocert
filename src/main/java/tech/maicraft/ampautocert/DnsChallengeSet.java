package tech.maicraft.ampautocert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns all provider record IDs created for one order and cleans only those IDs. */
public final class DnsChallengeSet implements AutoCloseable {
    private final DnsProvider provider;
    private final List<String> recordIds;
    private final RecordIdsChanged recordIdsChanged;

    private DnsChallengeSet(DnsProvider provider, List<String> recordIds, RecordIdsChanged recordIdsChanged) {
        this.provider = provider;
        this.recordIds = recordIds;
        this.recordIdsChanged = recordIdsChanged;
    }

    public static DnsChallengeSet publish(DnsProvider provider, String recordName, List<String> values)
            throws IOException, InterruptedException {
        validateValues(values);
        List<String> ids = new ArrayList<>(2);
        try {
            for (String value : values) {
                ids.add(provider.createTxt(recordName, value));
            }
            return new DnsChallengeSet(provider, ids, ignored -> { });
        } catch (IOException | InterruptedException | RuntimeException creationFailure) {
            List<String> remaining = cleanupAfterFailedPublish(provider, ids, creationFailure);
            if (!remaining.isEmpty()) {
                throw new DnsCleanupException("DNS-01 publication failed and cleanup must be retried",
                        creationFailure, new DnsChallengeSet(provider, remaining, ignored -> { }));
            }
            throw creationFailure;
        }
    }

    public static DnsChallengeSet publishRecoverable(RecoverableDnsProvider provider, String recordName, List<String> values,
                                                      String ownershipMarker, RecordIdsChanged changed)
            throws IOException, InterruptedException {
        validateValues(values);
        List<String> ids = new ArrayList<>(2);
        try {
            for (String value : values) {
                String id = provider.createOwnedTxt(recordName, value, ownershipMarker);
                ids.add(id);
                changed.update(List.copyOf(ids));
            }
            return new DnsChallengeSet(provider, ids, changed);
        } catch (IOException | InterruptedException | RuntimeException creationFailure) {
            List<String> remaining = cleanupAfterFailedPublish(provider, ids, creationFailure);
            if (!remaining.isEmpty()) {
                throw new DnsCleanupException("DNS-01 publication failed and cleanup must be retried",
                        creationFailure, new DnsChallengeSet(provider, remaining, changed));
            }
            throw creationFailure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        IOException cleanupFailure = null;
        boolean restoreInterrupt = Thread.interrupted();
        List<String> reverse = new ArrayList<>(recordIds);
        Collections.reverse(reverse);
        for (String id : reverse) {
            try {
                provider.deleteTxt(id);
                recordIds.remove(id);
                recordIdsChanged.update(List.copyOf(recordIds));
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
                Thread.interrupted();
                IOException wrapped = new IOException("Interrupted while cleaning DNS-01 records", interrupted);
                if (cleanupFailure == null) cleanupFailure = wrapped; else cleanupFailure.addSuppressed(wrapped);
            } catch (IOException failure) {
                if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure);
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
        if (cleanupFailure != null) {
            throw new DnsCleanupException("DNS-01 cleanup must be retried", cleanupFailure, this);
        }
    }

    private static List<String> cleanupAfterFailedPublish(DnsProvider provider, List<String> ids, Throwable original) {
        List<String> remaining = new ArrayList<>();
        boolean restoreInterrupt = creationWasInterrupted(original) | Thread.interrupted();
        for (int i = ids.size() - 1; i >= 0; i--) {
            try {
                provider.deleteTxt(ids.get(i));
            } catch (IOException | InterruptedException cleanupFailure) {
                remaining.add(ids.get(i));
                if (cleanupFailure instanceof InterruptedException) {
                    restoreInterrupt = true;
                    Thread.interrupted();
                }
                original.addSuppressed(cleanupFailure);
            }
        }
        Collections.reverse(remaining);
        if (restoreInterrupt) Thread.currentThread().interrupt();
        return remaining;
    }

    private static boolean creationWasInterrupted(Throwable failure) {
        return failure instanceof InterruptedException;
    }

    private static void validateValues(List<String> values) {
        if (values.size() > 2 || values.size() != values.stream().distinct().count()) {
            throw new IllegalArgumentException("An ACME order can publish at most two distinct DNS-01 values");
        }
    }

    @FunctionalInterface
    public interface RecordIdsChanged {
        void update(List<String> recordIds) throws IOException;
    }
}
