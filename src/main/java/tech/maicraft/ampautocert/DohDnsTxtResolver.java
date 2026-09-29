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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Bounded DNS-over-HTTPS lookup through the explicitly allowlisted public resolvers. */
final class DohDnsTxtResolver implements DnsTxtResolver {
    private static final int TXT_TYPE = 16;

    private final HttpTransport http;
    private final List<URI> resolvers;
    private final Clock clock;
    private final Consumer<String> diagnosticSink;

    DohDnsTxtResolver(HttpTransport http, List<URI> resolvers, Clock clock, Consumer<String> diagnosticSink) {
        this.http = java.util.Objects.requireNonNull(http, "http");
        this.resolvers = List.copyOf(resolvers);
        if (this.resolvers.isEmpty()) throw new IllegalArgumentException("At least one DNS resolver is required");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.diagnosticSink = java.util.Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    @Override
    public Set<String> resolve(String recordName, Instant deadline) throws IOException, InterruptedException {
        String owner = canonicalOwner(recordName);
        if (!owner.matches("(?i)_acme-challenge\\.(?:[a-z0-9-]+\\.)+[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid DNS-01 owner name");
        }
        Set<String> consensus = null;
        IOException failures = null;
        int successful = 0;
        for (URI resolver : resolvers) {
            if (!clock.instant().isBefore(deadline)) throw new IOException("DNS lookup deadline expired");
            Instant started = clock.instant();
            try {
                QueryResult answer = queryWithinDeadline(resolver, owner, deadline);
                Set<String> result = answer.values();
                successful++;
                if (consensus == null) consensus = new LinkedHashSet<>(result);
                else consensus.retainAll(result);
                diagnostic(resolver.getHost(), "dnsStatus=" + answer.status() + " txtCount=" + result.size(), started);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException failure) {
                diagnostic(resolver.getHost(), "failed=" + safeCategory(failure), started);
                if (failures == null) failures = new IOException("All configured DNS-over-HTTPS resolvers failed");
                failures.addSuppressed(failure);
            }
        }
        if (successful == 0) throw failures == null
                ? new IOException("All configured DNS-over-HTTPS resolvers failed") : failures;
        diagnosticSink.accept("DoH consensus used " + successful + " successful resolver(s); visibleTxtCount="
                + (consensus == null ? 0 : consensus.size()) + ".");
        return consensus == null ? Set.of() : Set.copyOf(consensus);
    }

    private QueryResult query(URI resolver, String owner) throws IOException, InterruptedException {
        URI query = URI.create(resolver + "?name=" + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "&type=TXT");
        HttpTransport.Response response = http.send("GET", query, Map.of("Accept", "application/dns-json"), "");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DNS-over-HTTPS returned HTTP " + response.statusCode());
        }
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!body.has("Status") || !body.get("Status").isJsonPrimitive()) {
                throw new IOException("DNS-over-HTTPS response omitted DNS status");
            }
            int status = body.get("Status").getAsInt();
            if (status == 3) return new QueryResult(status, Set.of());
            if (status != 0) throw new IOException("DNS-over-HTTPS returned DNS status " + status);
            JsonArray answers = body.has("Answer") && body.get("Answer").isJsonArray()
                    ? body.getAsJsonArray("Answer") : new JsonArray();
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonElement element : answers) {
                if (!element.isJsonObject()) continue;
                JsonObject answer = element.getAsJsonObject();
                if (!answer.has("type") || answer.get("type").getAsInt() != TXT_TYPE
                        || !answer.has("name") || !canonicalOwner(answer.get("name").getAsString()).equalsIgnoreCase(owner)
                        || !answer.has("data")) continue;
                String decoded = SystemDnsTxtResolver.decodeTxt(answer.get("data").getAsString());
                if (decoded.length() > 1024) throw new IOException("DNS TXT answer exceeded safe size limit");
                values.add(decoded);
            }
            return new QueryResult(status, Set.copyOf(values));
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new IOException("DNS-over-HTTPS returned malformed JSON");
        }
    }

    private QueryResult queryWithinDeadline(URI resolver, String owner, Instant deadline)
            throws IOException, InterruptedException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) throw new IOException("DNS lookup deadline expired");
        FutureTask<QueryResult> lookup = new FutureTask<>(() -> query(resolver, owner));
        Thread worker = Thread.ofVirtual().name("amp-autocert-doh-lookup").start(lookup);
        try {
            return lookup.get(Math.max(1L, remaining.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            lookup.cancel(true);
            worker.interrupt();
            throw new IOException("DNS lookup deadline expired");
        } catch (InterruptedException interrupted) {
            lookup.cancel(true);
            worker.interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof InterruptedException interrupted) throw interrupted;
            if (cause instanceof IOException io) throw io;
            throw new IOException("DNS-over-HTTPS lookup failed");
        }
    }

    private void diagnostic(String resolver, String outcome, Instant started) {
        long elapsed = Math.max(0L, Duration.between(started, clock.instant()).toMillis());
        diagnosticSink.accept("DoH resolver " + resolver + " " + outcome + " elapsedMs=" + elapsed + ".");
    }

    private static String safeCategory(IOException failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("DNS-over-HTTPS returned (?:HTTP|DNS status) [0-9]+")) return message;
        if (message != null && message.contains("deadline")) return "deadline";
        if (message != null && message.contains("malformed")) return "malformed-response";
        return "transport-error";
    }

    private static String canonicalOwner(String value) {
        if (value == null) return "";
        String normalized = value.strip().toLowerCase();
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record QueryResult(int status, Set<String> values) { }
}
