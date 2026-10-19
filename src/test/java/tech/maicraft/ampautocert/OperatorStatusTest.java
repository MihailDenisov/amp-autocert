package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorStatusTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void formatsRequiredOperatorFieldsWithoutSecretMaterial() {
        OperatorStatus status = new OperatorStatus(true, Optional.of(AcmeEnvironment.PRODUCTION), "running",
                Optional.of(NOW.plusSeconds(31L * 86400)), List.of("*.mydomain.com", "mydomain.com"),
                Optional.of(NOW), "not_due", Optional.empty());

        List<String> lines = status.format(CLOCK);

        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("environment=production"));
        assertTrue(lines.get(1).contains("31 days left"));
        assertTrue(lines.get(2).contains("*.mydomain.com, mydomain.com"));
        assertTrue(lines.get(3).contains("outcome: not_due"));
        assertFalse(String.join(" ", lines).toLowerCase().contains("token"));
        assertFalse(String.join(" ", lines).toLowerCase().contains("private key"));
    }

    @Test void reportsExpiredAndCooldownStates() {
        OperatorStatus status = new OperatorStatus(true, Optional.of(AcmeEnvironment.PRODUCTION), "running",
                Optional.of(NOW.minusSeconds(1)), List.of(), Optional.empty(), "cooldown",
                Optional.of(NOW.plusSeconds(3600)));

        List<String> lines = status.format(CLOCK);

        assertEquals(5, lines.size());
        assertTrue(lines.get(1).contains("expired"));
        assertTrue(lines.get(2).endsWith("not available"));
        assertTrue(lines.get(3).contains("Last check: never"));
        assertTrue(lines.get(4).contains("2026-09-28T13:00:00Z"));
    }

    @Test void reportsOnlySafeConfigurationDriftFieldNames() {
        OperatorStatus status = new OperatorStatus(true, Optional.of(AcmeEnvironment.PRODUCTION),
                "blocked configuration drift", Optional.empty(), List.of(), Optional.of(NOW),
                "configuration_drift", Optional.empty(),
                List.of("automodpack.endpointHost", "cloudflare.zoneId", "token value\nsecret"));

        List<String> lines = status.format(CLOCK);

        assertEquals(5, lines.size());
        assertTrue(lines.get(0).contains("blocked configuration drift"));
        assertTrue(lines.get(4).contains("automodpack.endpointHost, cloudflare.zoneId"));
        assertFalse(String.join(" ", lines).contains("token value"));
    }
}
