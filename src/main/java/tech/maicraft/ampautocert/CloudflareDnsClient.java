package tech.maicraft.ampautocert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Zone-scoped Cloudflare v4 DNS client for the single permitted ACME TXT owner name. */
public final class CloudflareDnsClient implements RecoverableDnsProvider {
    private static final String JSON = "application/json";

    private final HttpTransport http;
    private final URI apiRoot;
    private final String zoneId;
    private final String recordName;
    private final int ttlSeconds;
    private final String authorization;

    public CloudflareDnsClient(HttpTransport http, AutoCertConfig config, String apiToken) {
        this(http, config.cloudflareApiRoot(), config.cloudflareZoneId(), apiToken,
                config.challengeRecord(), config.cloudflareTtlSeconds());
    }

    CloudflareDnsClient(HttpTransport http, URI apiRoot, String zoneId, String apiToken,
                        String recordName, int ttlSeconds) {
        if (!zoneId.matches("(?i)[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid Cloudflare zone ID");
        if (apiToken == null || apiToken.isBlank()) throw new IllegalArgumentException("Cloudflare API token is missing");
        this.http = http;
        this.apiRoot = apiRoot;
        this.zoneId = zoneId;
        if (recordName == null || !recordName.matches("(?i)_acme-challenge\\.(?:[a-z0-9-]+\\.)+[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid configured DNS-01 record name");
        }
        this.recordName = recordName.toLowerCase();
        if (ttlSeconds < 60 || ttlSeconds > 300) throw new IllegalArgumentException("Invalid DNS-01 TTL");
        this.ttlSeconds = ttlSeconds;
        this.authorization = "Bearer " + apiToken.strip();
    }

    @Override
    public String createTxt(String recordName, String value) throws IOException, InterruptedException {
        return createTxt(recordName, value, null);
    }

    @Override
    public String createOwnedTxt(String recordName, String value, String ownershipMarker)
            throws IOException, InterruptedException {
        requireOwnershipMarker(ownershipMarker);
        return createTxt(recordName, value, ownershipMarker);
    }

    private String createTxt(String recordName, String value, String ownershipMarker)
            throws IOException, InterruptedException {
        requireRecordName(recordName);
        if (value == null || !value.matches("[A-Za-z0-9_-]{20,255}")) {
            throw new IllegalArgumentException("Invalid DNS-01 TXT value");
        }
        JsonObject request = new JsonObject();
        request.addProperty("type", "TXT");
        request.addProperty("name", this.recordName);
        request.addProperty("content", value);
        request.addProperty("ttl", ttlSeconds);
        if (ownershipMarker != null) request.addProperty("comment", ownershipMarker);
        HttpTransport.Response response = http.send("POST", recordsUri(), headers(), request.toString());
        JsonObject result = successfulResult(response, "create");
        String id = result.has("id") ? result.get("id").getAsString() : "";
        if (!id.matches("(?i)[0-9a-f]{32}")) throw new IOException("Cloudflare create response omitted a valid record ID");
        return id;
    }

    @Override
    public List<String> findOwnedTxt(String recordName, String ownershipMarker)
            throws IOException, InterruptedException {
        requireRecordName(recordName);
        requireOwnershipMarker(ownershipMarker);
        String query = "?type=TXT&name=" + encode(recordName) + "&comment.exact=" + encode(ownershipMarker)
                + "&per_page=100";
        HttpTransport.Response response = http.send("GET", URI.create(recordsUri() + query), headers(), "");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Cloudflare DNS ownership lookup failed with HTTP " + response.statusCode());
        }
        try {
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!envelope.has("success") || !envelope.get("success").getAsBoolean()
                    || !envelope.has("result") || !envelope.get("result").isJsonArray()) {
                throw new IOException("Cloudflare DNS ownership lookup was rejected");
            }
            if (envelope.has("result_info") && envelope.get("result_info").isJsonObject()) {
                JsonObject info = envelope.getAsJsonObject("result_info");
                if (info.has("total_pages") && info.get("total_pages").getAsInt() > 1) {
                    throw new IOException("Cloudflare DNS ownership lookup returned too many pages");
                }
            }
            JsonArray results = envelope.getAsJsonArray("result");
            if (results.size() > 2) throw new IOException("Cloudflare returned too many owned DNS-01 records");
            List<String> ids = new ArrayList<>(results.size());
            for (var element : results) {
                JsonObject record = element.getAsJsonObject();
                String id = record.has("id") ? record.get("id").getAsString() : "";
                String type = record.has("type") ? record.get("type").getAsString() : "";
                String name = record.has("name") ? record.get("name").getAsString() : "";
                String comment = record.has("comment") ? record.get("comment").getAsString() : "";
                if (!id.matches("(?i)[0-9a-f]{32}") || !"TXT".equals(type)
                        || !this.recordName.equalsIgnoreCase(name) || !ownershipMarker.equals(comment)) {
                    throw new IOException("Cloudflare returned an invalid owned DNS-01 record");
                }
                ids.add(id);
            }
            return List.copyOf(ids);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new IOException("Cloudflare DNS ownership lookup returned malformed JSON");
        }
    }

    @Override
    public void deleteTxt(String recordId) throws IOException, InterruptedException {
        if (recordId == null || !recordId.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid Cloudflare record ID");
        }
        HttpTransport.Response response = http.send("DELETE", URI.create(recordsUri() + "/" + recordId), headers(), "");
        if (response.statusCode() == 404) return; // Already absent is a successful idempotent cleanup.
        successfulResult(response, "delete");
    }

    private JsonObject successfulResult(HttpTransport.Response response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Cloudflare DNS " + operation + " failed with HTTP " + response.statusCode());
        }
        try {
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!envelope.has("success") || !envelope.get("success").getAsBoolean()) {
                throw new IOException("Cloudflare DNS " + operation + " was rejected");
            }
            if (!envelope.has("result") || !envelope.get("result").isJsonObject()) {
                throw new IOException("Cloudflare DNS " + operation + " returned no result");
            }
            return envelope.getAsJsonObject("result");
        } catch (RuntimeException malformed) {
            throw new IOException("Cloudflare DNS " + operation + " returned malformed JSON");
        }
    }

    private URI recordsUri() {
        return apiRoot.resolve("zones/" + zoneId + "/dns_records");
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", authorization, "Accept", JSON, "Content-Type", JSON);
    }

    private void requireRecordName(String recordName) {
        if (!this.recordName.equals(recordName)) {
            throw new IllegalArgumentException("DNS writes are limited to the configured DNS-01 owner");
        }
    }

    private static void requireOwnershipMarker(String marker) {
        if (marker == null || !marker.matches("amp-autocert:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid DNS ownership marker");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
