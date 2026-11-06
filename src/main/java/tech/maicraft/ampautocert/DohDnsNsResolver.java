package tech.maicraft.ampautocert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent public NS delegation lookup requiring exact agreement from every pinned resolver. */
final class DohDnsNsResolver {
    private static final int NS_TYPE = 2;
    private final HttpTransport http;
    private final List<URI> resolvers;
    private final Clock clock;

    DohDnsNsResolver(HttpTransport http, List<URI> resolvers, Clock clock) {
        this.http = http;
        this.resolvers = List.copyOf(resolvers);
        this.clock = clock;
        if (this.resolvers.size() < 2) throw new IllegalArgumentException("Two independent DoH resolvers are required");
    }

    Set<String> resolve(String zoneName, Instant deadline) throws IOException, InterruptedException {
        String zone = AutoCertConfig.normalizedDnsName(zoneName, "cloudflare.zoneName", false);
        Set<String> consensus = null;
        for (URI resolver : resolvers) {
            if (!clock.instant().isBefore(deadline)) throw new IOException("NS lookup deadline expired");
            Set<String> answer = query(resolver, zone);
            if (answer.isEmpty()) throw new IOException("Public DNS returned no authoritative NS records");
            if (consensus == null) consensus = answer;
            else if (!consensus.equals(answer)) throw new IOException("Independent public DNS resolvers disagree on NS delegation");
        }
        return consensus == null ? Set.of() : Set.copyOf(consensus);
    }

    private Set<String> query(URI resolver, String zone) throws IOException, InterruptedException {
        URI uri = URI.create(resolver + "?name=" + URLEncoder.encode(zone, StandardCharsets.UTF_8) + "&type=NS");
        HttpTransport.Response response = http.send("GET", uri, Map.of("Accept", "application/dns-json"), "");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DNS-over-HTTPS NS lookup returned HTTP " + response.statusCode());
        }
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!body.has("Status") || body.get("Status").getAsInt() != 0) {
                throw new IOException("DNS-over-HTTPS NS lookup was not successful");
            }
            JsonArray answers = body.has("Answer") && body.get("Answer").isJsonArray()
                    ? body.getAsJsonArray("Answer") : new JsonArray();
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonElement element : answers) {
                if (!element.isJsonObject()) continue;
                JsonObject answer = element.getAsJsonObject();
                if (!answer.has("type") || answer.get("type").getAsInt() != NS_TYPE || !answer.has("data")) continue;
                String value = CloudflarePreflightClient.canonicalDnsName(answer.get("data").getAsString());
                if (value.isEmpty()) throw new IOException("DNS-over-HTTPS returned an invalid NS hostname");
                values.add(value);
            }
            return Set.copyOf(values);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new IOException("DNS-over-HTTPS NS lookup returned malformed JSON");
        }
    }
}
