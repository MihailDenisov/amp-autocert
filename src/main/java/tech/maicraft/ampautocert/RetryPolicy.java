package tech.maicraft.ampautocert;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Bounded exponential retry delay with symmetric jitter. */
final class RetryPolicy {
    private final Duration initial;
    private final Duration maximum;
    private final double jitterFraction;

    RetryPolicy(Duration initial, Duration maximum, int jitterPercent) {
        if (initial.isZero() || initial.isNegative() || maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("Retry delays must be positive and ordered");
        }
        if (jitterPercent < 0 || jitterPercent > 50) {
            throw new IllegalArgumentException("Retry jitter must be between 0 and 50 percent");
        }
        this.initial = initial;
        this.maximum = maximum;
        this.jitterFraction = jitterPercent / 100.0;
    }

    Duration delay(int consecutiveFailures, DoubleSupplier random) {
        if (consecutiveFailures < 1) throw new IllegalArgumentException("consecutiveFailures must be positive");
        long initialMillis = initial.toMillis();
        long maximumMillis = maximum.toMillis();
        int shifts = Math.min(consecutiveFailures - 1, 62);
        long exponential = initialMillis;
        for (int i = 0; i < shifts && exponential < maximumMillis; i++) {
            exponential = Math.min(maximumMillis, exponential > maximumMillis / 2
                    ? maximumMillis : exponential * 2);
        }
        double sample = random.getAsDouble();
        if (sample < 0.0 || sample >= 1.0) throw new IllegalArgumentException("Random sample must be in [0, 1)");
        double factor = 1.0 - jitterFraction + sample * (2.0 * jitterFraction);
        long jittered = Math.max(1L, Math.round(exponential * factor));
        return Duration.ofMillis(Math.min(maximumMillis, jittered));
    }
}
