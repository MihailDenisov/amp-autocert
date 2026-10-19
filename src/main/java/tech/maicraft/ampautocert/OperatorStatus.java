package tech.maicraft.ampautocert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Immutable, secret-free snapshot read by server commands without filesystem or network access. */
record OperatorStatus(boolean enabled, Optional<AcmeEnvironment> environment, String lifecycle,
                      Optional<Instant> certificateExpiry, List<String> certificateSans,
                      Optional<Instant> lastCheck, String lastOutcome, Optional<Instant> nextAttempt,
                      List<String> configurationDriftFields) {
    OperatorStatus(boolean enabled, Optional<AcmeEnvironment> environment, String lifecycle,
                   Optional<Instant> certificateExpiry, List<String> certificateSans,
                   Optional<Instant> lastCheck, String lastOutcome, Optional<Instant> nextAttempt) {
        this(enabled, environment, lifecycle, certificateExpiry, certificateSans, lastCheck, lastOutcome,
                nextAttempt, List.of());
    }

    OperatorStatus {
        environment = environment == null ? Optional.empty() : environment;
        certificateExpiry = certificateExpiry == null ? Optional.empty() : certificateExpiry;
        certificateSans = certificateSans == null ? List.of() : List.copyOf(certificateSans);
        lastCheck = lastCheck == null ? Optional.empty() : lastCheck;
        nextAttempt = nextAttempt == null ? Optional.empty() : nextAttempt;
        configurationDriftFields = ConfigurationDriftGuard.safeFields(configurationDriftFields);
    }

    static OperatorStatus notStarted() {
        return new OperatorStatus(false, Optional.empty(), "not started", Optional.empty(), List.of(),
                Optional.empty(), "none", Optional.empty());
    }

    List<String> format(Clock clock) {
        List<String> lines = new ArrayList<>();
        String environmentText = environment.map(value -> value.name().toLowerCase(Locale.ROOT)).orElse("unknown");
        lines.add("amp-autocert: " + lifecycle + "; enabled=" + enabled + "; environment=" + environmentText);
        if (certificateExpiry.isPresent()) {
            Instant expiry = certificateExpiry.get();
            String remaining = !expiry.isAfter(clock.instant())
                    ? "expired"
                    : Duration.between(clock.instant(), expiry).toDays() + " days left";
            lines.add("Certificate expiry: " + expiry + " (" + remaining + ")");
        } else {
            lines.add("Certificate expiry: not available");
        }
        lines.add("Deployed DNS SANs: " + (certificateSans.isEmpty() ? "not available" : String.join(", ", certificateSans)));
        lines.add("Last check: " + lastCheck.map(Object::toString).orElse("never") + "; outcome: " + lastOutcome);
        nextAttempt.ifPresent(value -> lines.add("Issuance cooldown until: " + value));
        if (!configurationDriftFields.isEmpty()) {
            lines.add("Automation block fields: " + String.join(", ", configurationDriftFields));
        }
        return List.copyOf(lines);
    }
}
