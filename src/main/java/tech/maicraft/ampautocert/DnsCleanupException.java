package tech.maicraft.ampautocert;

import java.io.IOException;

/** Signals that provider-owned TXT IDs remain and exposes a bounded retry operation without revealing them. */
public final class DnsCleanupException extends IOException {
    private final DnsChallengeSet remaining;

    DnsCleanupException(String message, Throwable cause, DnsChallengeSet remaining) {
        super(message, cause);
        this.remaining = remaining;
    }

    public void retryCleanup() throws IOException {
        remaining.close();
    }
}
