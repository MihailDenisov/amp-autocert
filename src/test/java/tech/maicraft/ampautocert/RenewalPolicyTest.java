package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RenewalPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final Duration LEAD = Duration.ofDays(30);

    @Test void missingCertificate() {
        assertEquals(RenewalPolicy.Decision.MISSING, RenewalPolicy.decide(Optional.empty(), NOW, LEAD));
    }
    @Test void validOutsideRenewalWindow() {
        assertEquals(RenewalPolicy.Decision.VALID,
                RenewalPolicy.decide(Optional.of(NOW.plus(Duration.ofDays(31))), NOW, LEAD));
    }
    @Test void dueExactlyOnRenewalBoundary() {
        assertEquals(RenewalPolicy.Decision.DUE,
                RenewalPolicy.decide(Optional.of(NOW.plus(Duration.ofDays(30))), NOW, LEAD));
    }
    @Test void expiredAtBoundary() {
        assertEquals(RenewalPolicy.Decision.EXPIRED,
                RenewalPolicy.decide(Optional.of(NOW), NOW, LEAD));
    }
    @Test void disallowNonPositiveLeadTime() {
        assertThrows(IllegalArgumentException.class,
                () -> RenewalPolicy.decide(Optional.empty(), NOW, Duration.ZERO));
    }
}
