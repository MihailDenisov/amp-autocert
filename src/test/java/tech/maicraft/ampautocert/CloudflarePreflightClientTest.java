package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CloudflarePreflightClientTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test void verifiesActiveTokenExactFullZoneAndAssignedNameservers() throws Exception {
        QueueHttp http = new QueueHttp(response(token("active")), response(zone("example.com", "active", "full")));
        CloudflarePreflightClient client = new CloudflarePreflightClient(http, config(), "secret-value");
        client.verifyToken();
        CloudflarePreflightClient.Zone zone = client.verifyZone();
        assertEquals(Set.of("ada.ns.cloudflare.com", "bob.ns.cloudflare.com"), zone.assignedNameservers());
        assertTrue(http.authorizationValues.stream().allMatch(value -> value.equals("Bearer secret-value")));
        assertFalse(http.toString().contains("secret-value"));
    }

    @Test void rejectsInactiveTokenZoneMismatchAndNonAuthoritativeZone() {
        assertFailure("not active", new QueueHttp(response(token("expired"))), client -> client.verifyToken());
        assertFailure("name does not match", new QueueHttp(response(zone("other.example", "active", "full"))),
                client -> client.verifyZone());
        assertFailure("not active", new QueueHttp(response(zone("example.com", "pending", "full"))),
                client -> client.verifyZone());
        assertFailure("not a full", new QueueHttp(response(zone("example.com", "active", "partial"))),
                client -> client.verifyZone());
    }

    @Test void requiresExactPublicNsAgreementWithCloudflareAssignment() throws Exception {
        String answer = dns("ada.ns.cloudflare.com.", "bob.ns.cloudflare.com.");
        QueueHttp http = new QueueHttp(response(answer), response(answer));
        DohDnsNsResolver resolver = new DohDnsNsResolver(http, AutoCertConfig.DOH_RESOLVERS,
                Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));
        Set<String> actual = resolver.resolve("example.com", Instant.parse("2026-10-19T00:01:00Z"));
        assertEquals(Set.of("ada.ns.cloudflare.com", "bob.ns.cloudflare.com"), actual);

        QueueHttp mismatch = new QueueHttp(response(answer), response(dns("eve.ns.cloudflare.com.")));
        DohDnsNsResolver failing = new DohDnsNsResolver(mismatch, AutoCertConfig.DOH_RESOLVERS,
                Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));
        assertThrows(java.io.IOException.class, () -> failing.resolve("example.com",
                Instant.parse("2026-10-19T00:01:00Z")));
    }

    private static AutoCertConfig config() {
        return new AutoCertConfig(true, false, false, "ops@example.com", "example.com", ID,
                "play.example.com", 60, 30, 120, 15, 30, 24, 60, 48, 15, 12, 20,
                true, 60, 1, false, false, Optional.empty(), Optional.empty(), List.of());
    }
    private static HttpTransport.Response response(String body) { return new HttpTransport.Response(200, body); }
    private static String token(String status) {
        return "{\"success\":true,\"result\":{\"id\":\"" + ID + "\",\"status\":\"" + status + "\"}}";
    }
    private static String zone(String name, String status, String type) {
        return "{\"success\":true,\"result\":{\"id\":\"" + ID + "\",\"name\":\"" + name
                + "\",\"status\":\"" + status + "\",\"type\":\"" + type
                + "\",\"name_servers\":[\"ada.ns.cloudflare.com\",\"bob.ns.cloudflare.com\"]}}";
    }
    private static String dns(String... names) {
        String answers = java.util.Arrays.stream(names).map(value ->
                "{\"name\":\"example.com.\",\"type\":2,\"data\":\"" + value + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"Status\":0,\"Answer\":[" + answers + "]}";
    }
    private static void assertFailure(String expected, QueueHttp http, Checked action) {
        CloudflarePreflightClient client = new CloudflarePreflightClient(http, config(), "secret-value");
        Exception failure = assertThrows(java.io.IOException.class, () -> action.run(client));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
    @FunctionalInterface interface Checked { void run(CloudflarePreflightClient client) throws Exception; }

    private static final class QueueHttp implements HttpTransport {
        private final java.util.ArrayDeque<Response> responses = new java.util.ArrayDeque<>();
        private final List<String> authorizationValues = new ArrayList<>();
        QueueHttp(Response... responses) { this.responses.addAll(List.of(responses)); }
        @Override public Response send(String method, URI uri, Map<String, String> headers, String body) {
            if (headers.containsKey("Authorization")) authorizationValues.add(headers.get("Authorization"));
            return responses.removeFirst();
        }
        @Override public String toString() { return "QueueHttp[responses=" + responses.size() + "]"; }
    }
}
