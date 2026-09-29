package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DohDnsTxtResolverTest {
    private static final String RECORD = "_acme-challenge.maicraft.tech";
    private static final String SECRET_VALUE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final List<URI> ENDPOINTS = List.of(
            URI.create("https://cloudflare-dns.com/dns-query"), URI.create("https://dns.google/resolve"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    @Test void requiresConsensusAndDecodesQuotedTxtWithoutLoggingValues() throws Exception {
        FakeHttp http = new FakeHttp();
        String body = success("_acme-challenge.maicraft.tech.", "\"aaaaaaaaaaaaaaaaaaaa\" \"aaaaaaaaaaaaaaaaaaaaaaa\"");
        http.responses.put("cloudflare-dns.com", new HttpTransport.Response(200, body));
        http.responses.put("dns.google", new HttpTransport.Response(200, body));
        List<String> diagnostics = new ArrayList<>();

        Set<String> values = resolver(http, diagnostics::add).resolve(RECORD, Instant.parse("2026-09-29T00:01:00Z"));

        assertEquals(Set.of(SECRET_VALUE), values);
        String rendered = String.join("\n", diagnostics);
        assertTrue(rendered.contains("cloudflare-dns.com"));
        assertTrue(rendered.contains("dns.google"));
        assertTrue(rendered.contains("txtCount=1"));
        assertFalse(rendered.contains(SECRET_VALUE));
    }

    @Test void nxdomainFromOneResolverPreventsPrematurePropagation() throws Exception {
        FakeHttp http = new FakeHttp();
        http.responses.put("cloudflare-dns.com", new HttpTransport.Response(200, success(RECORD + ".", "\"" + SECRET_VALUE + "\"")));
        http.responses.put("dns.google", new HttpTransport.Response(200, "{\"Status\":3}"));

        assertEquals(Set.of(), resolver(http, ignored -> { }).resolve(RECORD,
                Instant.parse("2026-09-29T00:01:00Z")));
    }

    @Test void toleratesOneTransportFailureButFailsWhenAllResolversFail() throws Exception {
        FakeHttp partial = new FakeHttp();
        partial.failures.add("cloudflare-dns.com");
        partial.responses.put("dns.google", new HttpTransport.Response(200, success(RECORD, "\"" + SECRET_VALUE + "\"")));
        assertEquals(Set.of(SECRET_VALUE), resolver(partial, ignored -> { }).resolve(RECORD,
                Instant.parse("2026-09-29T00:01:00Z")));

        FakeHttp failed = new FakeHttp();
        failed.failures.addAll(List.of("cloudflare-dns.com", "dns.google"));
        IOException error = assertThrows(IOException.class, () -> resolver(failed, ignored -> { }).resolve(RECORD,
                Instant.parse("2026-09-29T00:01:00Z")));
        assertEquals("All configured DNS-over-HTTPS resolvers failed", error.getMessage());
    }

    @Test void ignoresAnswersForOtherOwnersAndRecordTypes() throws Exception {
        String body = "{\"Status\":0,\"Answer\":["
                + "{\"name\":\"other.maicraft.tech.\",\"type\":16,\"data\":\"\\\"" + SECRET_VALUE + "\\\"\"},"
                + "{\"name\":\"" + RECORD + ".\",\"type\":1,\"data\":\"\\\"" + SECRET_VALUE + "\\\"\"}]}";
        FakeHttp http = new FakeHttp();
        http.responses.put("cloudflare-dns.com", new HttpTransport.Response(200, body));
        http.responses.put("dns.google", new HttpTransport.Response(200, body));

        assertTrue(resolver(http, ignored -> { }).resolve(RECORD,
                Instant.parse("2026-09-29T00:01:00Z")).isEmpty());
    }

    @Test void boundsAnInFlightRequestByTheSharedDeadline() {
        HttpTransport blocking = (method, uri, headers, body) -> {
            try {
                Thread.sleep(5_000);
                return new HttpTransport.Response(200, "{\"Status\":3}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        };
        DohDnsTxtResolver resolver = new DohDnsTxtResolver(blocking, ENDPOINTS, Clock.systemUTC(), ignored -> { });
        long started = System.nanoTime();

        IOException failure = assertThrows(IOException.class,
                () -> resolver.resolve(RECORD, Instant.now().plusMillis(100)));

        assertEquals("DNS lookup deadline expired", failure.getMessage());
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).compareTo(java.time.Duration.ofSeconds(1)) < 0);
    }

    private static DohDnsTxtResolver resolver(FakeHttp http, java.util.function.Consumer<String> diagnostics) {
        return new DohDnsTxtResolver(http, ENDPOINTS, CLOCK, diagnostics);
    }

    private static String success(String name, String data) {
        return "{\"Status\":0,\"Answer\":[{\"name\":\"" + name
                + "\",\"type\":16,\"data\":" + quoteJson(data) + "}]}";
    }

    private static String quoteJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class FakeHttp implements HttpTransport {
        final Map<String, Response> responses = new java.util.HashMap<>();
        final Set<String> failures = new java.util.HashSet<>();
        @Override public Response send(String method, URI uri, Map<String, String> headers, String body) throws IOException {
            assertEquals("GET", method);
            assertEquals("application/dns-json", headers.get("Accept"));
            assertTrue(uri.getRawQuery().contains("type=TXT"));
            if (failures.contains(uri.getHost())) throw new IOException("sensitive provider detail");
            return responses.get(uri.getHost());
        }
    }
}
