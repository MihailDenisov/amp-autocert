package tech.maicraft.ampautocert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Read-only Cloudflare authentication and exact-zone validation. Raw provider bodies stay private. */
final class CloudflarePreflightClient {
    private final HttpTransport http;
    private final URI apiRoot;
    private final String zoneId;
    private final String expectedZoneName;
    private final String authorization;

    CloudflarePreflightClient(HttpTransport http, AutoCertConfig config, String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Cloudflare API token is missing");
        this.http = http;
        this.apiRoot = config.cloudflareApiRoot();
        this.zoneId = config.cloudflareZoneId().toLowerCase();
        this.expectedZoneName = config.zoneName();
        this.authorization = "Bearer " + token.strip();
    }

    void verifyToken() throws IOException, InterruptedException {
        HttpTransport.Response response = http.send("GET", apiRoot.resolve("user/tokens/verify"), headers(), "");
        JsonObject result = resultObject(response, "Cloudflare token verification");
        String status = string(result, "status", "Cloudflare token verification response omitted status");
        if (!"active".equals(status)) throw new IOException("Cloudflare API token is not active");
        String id = string(result, "id", "Cloudflare token verification response omitted token ID");
        if (!id.matches("(?i)[0-9a-f]{32}")) {
            throw new IOException("Cloudflare token verification response contained an invalid token ID");
        }
    }

    Zone verifyZone() throws IOException, InterruptedException {
        HttpTransport.Response response = http.send("GET", apiRoot.resolve("zones/" + zoneId), headers(), "");
        JsonObject result = resultObject(response, "Cloudflare zone lookup");
        String returnedId = string(result, "id", "Cloudflare zone lookup omitted zone ID").toLowerCase();
        if (!zoneId.equals(returnedId)) throw new IOException("Cloudflare zone ID does not match configuration");
        String name;
        try { name = AutoCertConfig.normalizedDnsName(string(result, "name",
                "Cloudflare zone lookup omitted zone name"), "Cloudflare zone name", false); }
        catch (IllegalArgumentException invalid) { throw new IOException("Cloudflare zone lookup returned an invalid zone name"); }
        if (!expectedZoneName.equals(name)) throw new IOException("Cloudflare zone name does not match configuration");
        if (!"active".equals(string(result, "status", "Cloudflare zone lookup omitted status"))) {
            throw new IOException("Cloudflare zone is not active");
        }
        if (!"full".equals(string(result, "type", "Cloudflare zone lookup omitted type"))) {
            throw new IOException("Cloudflare zone is not a full authoritative zone");
        }
        Set<String> regular = nameservers(result.get("name_servers"), "name_servers");
        Set<String> vanity = result.has("vanity_name_servers") && !result.get("vanity_name_servers").isJsonNull()
                ? nameservers(result.get("vanity_name_servers"), "vanity_name_servers") : Set.of();
        Set<String> assigned = vanity.isEmpty() ? regular : vanity;
        if (assigned.isEmpty()) throw new IOException("Cloudflare zone lookup returned no assigned nameservers");
        return new Zone(returnedId, name, assigned, !vanity.isEmpty());
    }

    private JsonObject resultObject(HttpTransport.Response response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(operation + " failed with HTTP " + response.statusCode());
        }
        try {
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!envelope.has("success") || !envelope.get("success").getAsBoolean()
                    || !envelope.has("result") || !envelope.get("result").isJsonObject()) {
                throw new IOException(operation + " was rejected");
            }
            return envelope.getAsJsonObject("result");
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new IOException(operation + " returned malformed JSON");
        }
    }

    private static Set<String> nameservers(JsonElement element, String field) throws IOException {
        if (element == null || !element.isJsonArray()) throw new IOException("Cloudflare zone lookup omitted " + field);
        JsonArray array = element.getAsJsonArray();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive()) throw new IOException("Cloudflare zone lookup returned invalid " + field);
            String value = canonicalDnsName(entry.getAsString());
            if (value.isEmpty()) throw new IOException("Cloudflare zone lookup returned invalid " + field);
            values.add(value);
        }
        return Set.copyOf(values);
    }

    static String canonicalDnsName(String value) {
        if (value == null) return "";
        String normalized = value.strip().toLowerCase();
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        try { return AutoCertConfig.normalizedDnsName(normalized, "nameserver", false); }
        catch (IllegalArgumentException invalid) { return ""; }
    }

    private static String string(JsonObject object, String field, String error) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) throw new IOException(error);
        return object.get(field).getAsString();
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", authorization, "Accept", "application/json");
    }

    record Zone(String id, String name, Set<String> assignedNameservers, boolean vanityNameservers) {
        Zone { assignedNameservers = Set.copyOf(assignedNameservers); }
    }
}
