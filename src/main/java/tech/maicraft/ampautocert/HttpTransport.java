package tech.maicraft.ampautocert;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Minimal injectable HTTP boundary. Implementations must never log authorization headers or raw response bodies. */
public interface HttpTransport {
    Response send(String method, URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException;

    record Response(int statusCode, String body) {
        @Override public String toString() {
            return "Response[statusCode=" + statusCode + ", body=<redacted>]";
        }
    }
}
