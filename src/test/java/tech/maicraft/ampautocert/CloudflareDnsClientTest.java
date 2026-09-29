package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareDnsClientTest {
    private static final String ZONE = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN = "fake-token-never-log";
    private static final String VALUE = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE";
    private static final String RECORD = "_acme-challenge.maicraft.tech";
    private static final String MARKER = "amp-autocert:12345678-1234-4234-8234-123456789abc";

    @Test void createsOnlyAllowedTxtAndReturnsProviderId() throws Exception {
        FakeHttp http = new FakeHttp(new HttpTransport.Response(200,
                "{\"success\":true,\"result\":{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}"));
        CloudflareDnsClient client = client(http);

        String id = client.createTxt(RECORD, VALUE);

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", id);
        FakeHttp.Call call = http.calls.getFirst();
        assertEquals("POST", call.method());
        assertEquals("https://fake.invalid/client/v4/zones/" + ZONE + "/dns_records", call.uri().toString());
        assertEquals("Bearer " + TOKEN, call.headers().get("Authorization"));
        assertTrue(call.body().contains("\"type\":\"TXT\""));
        assertTrue(call.body().contains("\"name\":\"" + RECORD + "\""));
        assertTrue(call.body().contains("\"content\":\"" + VALUE + "\""));
        assertTrue(call.body().contains("\"ttl\":60"));
    }

    @Test void refusesAnyOtherDnsOwnerNameBeforeHttp() {
        FakeHttp http = new FakeHttp(new HttpTransport.Response(500, "unused"));
        assertThrows(IllegalArgumentException.class,
                () -> client(http).createTxt("_acme-challenge.evil.example", VALUE));
        assertTrue(http.calls.isEmpty());
    }

    @Test void createsAndRediscoversOnlyTransactionMarkedRecords() throws Exception {
        FakeHttp create = new FakeHttp(new HttpTransport.Response(200,
                "{\"success\":true,\"result\":{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}"));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", client(create).createOwnedTxt(RECORD, VALUE, MARKER));
        assertTrue(create.calls.getFirst().body().contains("\"comment\":\"" + MARKER + "\""));

        FakeHttp list = new FakeHttp(new HttpTransport.Response(200,
                "{\"success\":true,\"result\":[{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                        + "\"type\":\"TXT\",\"name\":\"" + RECORD + "\",\"comment\":\"" + MARKER + "\"}],"
                        + "\"result_info\":{\"total_pages\":1}}"));
        assertEquals(List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), client(list).findOwnedTxt(RECORD, MARKER));
        String uri = list.calls.getFirst().uri().toString();
        assertTrue(uri.contains("type=TXT"));
        assertTrue(uri.contains("comment.exact=amp-autocert%3A"));
    }

    @Test void treatsMissingDeleteAsIdempotentSuccess() throws Exception {
        FakeHttp http = new FakeHttp(new HttpTransport.Response(404, "{\"success\":false}"));
        client(http).deleteTxt("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertEquals("DELETE", http.calls.getFirst().method());
    }

    @Test void errorDoesNotExposeTokenOrRawCloudflareBody() {
        String raw = "raw-response-containing-" + TOKEN;
        FakeHttp http = new FakeHttp(new HttpTransport.Response(403, raw));
        IOException failure = assertThrows(IOException.class,
                () -> client(http).createTxt(RECORD, VALUE));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains(raw));
        assertEquals("Cloudflare DNS create failed with HTTP 403", failure.getMessage());
        assertFalse(http.response.toString().contains(raw));
    }

    private static CloudflareDnsClient client(FakeHttp http) {
        return new CloudflareDnsClient(http, URI.create("https://fake.invalid/client/v4/"), ZONE, TOKEN, RECORD, 60);
    }

    private static final class FakeHttp implements HttpTransport {
        private final Response response;
        private final List<Call> calls = new ArrayList<>();

        private FakeHttp(Response response) { this.response = response; }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body) {
            calls.add(new Call(method, uri, headers, body));
            return response;
        }

        private record Call(String method, URI uri, Map<String, String> headers, String body) { }
    }
}
