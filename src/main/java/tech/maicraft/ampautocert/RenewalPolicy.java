package tech.maicraft.ampautocert;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Small pure-Java policy; test it without running Minecraft or talking to the CA. */
public final class RenewalPolicy {
    public enum Decision { MISSING, VALID, DUE, EXPIRED }

    private RenewalPolicy() { }

    public static Decision decide(Optional<Instant> notAfter, Instant now, Duration beforeExpiry) {
        Objects.requireNonNull(notAfter);
        Objects.requireNonNull(now);
        Objects.requireNonNull(beforeExpiry);
        if (beforeExpiry.isNegative() || beforeExpiry.isZero())
            throw new IllegalArgumentException("Renewal lead time must be positive");
        if (notAfter.isEmpty()) return Decision.MISSING;
        if (!now.isBefore(notAfter.get())) return Decision.EXPIRED;
        return !now.isBefore(notAfter.get().minus(beforeExpiry)) ? Decision.DUE : Decision.VALID;
    }
}
