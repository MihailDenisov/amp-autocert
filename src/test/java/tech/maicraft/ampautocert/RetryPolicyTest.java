package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy(Duration.ofMinutes(15), Duration.ofHours(12), 20);

    @Test void doublesAndCapsDelayWithoutJitterAtMidpoint() {
        assertEquals(Duration.ofMinutes(15), policy.delay(1, () -> 0.5));
        assertEquals(Duration.ofMinutes(30), policy.delay(2, () -> 0.5));
        assertEquals(Duration.ofHours(12), policy.delay(20, () -> 0.5));
    }

    @Test void appliesBoundedSymmetricJitter() {
        assertEquals(Duration.ofMinutes(12), policy.delay(1, () -> 0.0));
        Duration upper = policy.delay(1, () -> Math.nextDown(1.0));
        assertTrue(upper.compareTo(Duration.ofMinutes(15)) > 0);
        assertTrue(upper.compareTo(Duration.ofMinutes(18)) <= 0);
    }

    @Test void rejectsInvalidFailureCountAndRandomSample() {
        assertThrows(IllegalArgumentException.class, () -> policy.delay(0, () -> 0.5));
        assertThrows(IllegalArgumentException.class, () -> policy.delay(1, () -> 1.0));
    }
}
