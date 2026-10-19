package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreflightServiceTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path root;

    @Test
    void completeReadOnlyPreflightPassesWithWarningsForFreshEnrollment() throws Exception {
        QueueHttp http = successfulHttp();
        PreflightReport report = service(http, identity("packs.example.com"), identity("packs.example.com"),
                () -> "super-secret-token", () -> { }).run();

        assertTrue(report.passed(), String.join("\n", report.format()));
        assertTrue(report.format().stream().anyMatch(line -> line.contains("Cloudflare token: token is active")));
        assertTrue(report.format().stream().anyMatch(line -> line.contains("no accepted configuration baseline")));
        assertFalse(String.join("\n", report.format()).contains("super-secret-token"));
        assertTrue(http.authorizationHeaders.stream().allMatch(value -> value.equals("Bearer super-secret-token")));
        assertFalse(Files.exists(root.resolve("automodpack/amp-autocert-state")));
    }

    @Test
    void loadedAndOnDiskAutoModpackDriftBlocksDoctor() throws Exception {
        PreflightReport report = service(successfulHttp(), identity("packs.example.com"),
                identity("cdn.example.com"), () -> "secret", () -> { }).run();

        assertFalse(report.passed());
        assertTrue(report.format().stream().anyMatch(line -> line.contains("loaded and on-disk settings differ")));
    }

    @Test
    void reconciliationPreflightAllowsExpectedAcceptedBaselineDriftButReturnsCandidate() throws Exception {
        EnrollmentStateStore store = new EnrollmentStateStore(root);
        store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT,
                ConfigurationBaseline.from(config(), identity("packs.example.com")),
                "automodpack.endpointHost"));

        PreflightService.ReconciliationPreflight result = service(successfulHttp(),
                identity("cdn.example.com"), identity("cdn.example.com"),
                () -> "super-secret-token", () -> { }).runForReconciliation();

        assertTrue(result.report().passed(), String.join("\n", result.report().format()));
        assertTrue(result.report().format().stream()
                .anyMatch(line -> line.contains("operator is reconciling configuration fields")));
        assertTrue(result.candidate().isPresent());
        assertTrue(result.candidate().orElseThrow().endpointHost().equals("cdn.example.com"));
    }

    @Test
    void inactiveTokenBlocksWithoutLeakingTokenOrProviderBody() throws Exception {
        QueueHttp http = new QueueHttp(response(token("expired", "provider-secret-body")));
        PreflightReport report = service(http, identity("packs.example.com"), identity("packs.example.com"),
                () -> "super-secret-token", () -> { }).run();
        String rendered = String.join("\n", report.format());

        assertFalse(report.passed());
        assertTrue(rendered.contains("token verification failed or token is inactive"));
        assertFalse(rendered.contains("super-secret-token"));
        assertFalse(rendered.contains("provider-secret-body"));
    }

    @Test
    void credentialExceptionMessageIsNeverRendered() throws Exception {
        PreflightReport report = service(new QueueHttp(), identity("packs.example.com"),
                identity("packs.example.com"),
                () -> { throw new IOException("super-secret-token from credential provider"); },
                () -> { }).run();
        String rendered = String.join("\n", report.format());

        assertFalse(report.passed());
        assertTrue(rendered.contains("token is missing, empty, or stored unsafely"));
        assertFalse(rendered.contains("super-secret-token"));
        assertFalse(rendered.contains("credential provider"));
    }

    @Test
    void credentialInspectorRejectsIncompletePairWithoutChangingIt() throws Exception {
        Path credentials = root.resolve("automodpack/credentials");
        Files.createDirectories(credentials);
        Path certificate = credentials.resolve("certificate.crt");
        Files.writeString(certificate, "unchanged");

        IOException failure = assertThrows(IOException.class, () -> CredentialTargetInspector.inspect(root, false));
        assertTrue(failure.getMessage().contains("incomplete"));
        assertTrue(Files.exists(certificate));
        assertFalse(Files.exists(credentials.resolve("private-key.pem")));
    }

    private PreflightService service(QueueHttp http, AutoModpackIdentitySnapshot effective,
                                     AutoModpackIdentitySnapshot onDisk,
                                     PreflightService.TokenLoader tokens,
                                     PreflightService.RunnableCheck targetCheck) {
        AutoModpackConfigSource source = new AutoModpackConfigSource() {
            @Override public AutoModpackIdentitySnapshot effective(AutoCertConfig ignored) { return effective; }
            @Override public AutoModpackIdentitySnapshot onDisk(AutoCertConfig ignored) { return onDisk; }
        };
        return new PreflightService(config(), root, CLOCK, source, http, tokens, targetCheck);
    }

    private static AutoModpackIdentitySnapshot identity(String endpoint) {
        return new AutoModpackIdentitySnapshot("5.0.0-rc.2", true,
                AutoModpackIdentitySnapshot.ConnectionMode.MAGIC, 25570, endpoint, 443, false);
    }

    private static AutoCertConfig config() {
        return new AutoCertConfig(true, false, false, "ops@example.com", "example.com", ID,
                "play.example.com", 60, 30, 120, 15, 30, 24, 60, 48, 15, 12, 20,
                true, 60, 1, false, false, Optional.empty(), Optional.empty(), List.of());
    }

    private static QueueHttp successfulHttp() {
        String dns = dns("ada.ns.cloudflare.com.", "bob.ns.cloudflare.com.");
        return new QueueHttp(response(token("active", "ignored")), response(zone()),
                response(dns), response(dns));
    }

    private static HttpTransport.Response response(String body) { return new HttpTransport.Response(200, body); }
    private static String token(String status, String extra) {
        return "{\"success\":true,\"private\":\"" + extra + "\",\"result\":{\"id\":\"" + ID
                + "\",\"status\":\"" + status + "\"}}";
    }
    private static String zone() {
        return "{\"success\":true,\"result\":{\"id\":\"" + ID
                + "\",\"name\":\"example.com\",\"status\":\"active\",\"type\":\"full\","
                + "\"name_servers\":[\"ada.ns.cloudflare.com\",\"bob.ns.cloudflare.com\"]}}";
    }
    private static String dns(String... names) {
        String answers = java.util.Arrays.stream(names).map(value ->
                "{\"name\":\"example.com.\",\"type\":2,\"data\":\"" + value + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"Status\":0,\"Answer\":[" + answers + "]}";
    }

    private static final class QueueHttp implements HttpTransport {
        private final ArrayDeque<Response> responses = new ArrayDeque<>();
        private final java.util.ArrayList<String> authorizationHeaders = new java.util.ArrayList<>();
        QueueHttp(Response... responses) { this.responses.addAll(List.of(responses)); }
        @Override public Response send(String method, URI uri, Map<String, String> headers, String body) {
            if (headers.containsKey("Authorization")) authorizationHeaders.add(headers.get("Authorization"));
            return responses.removeFirst();
        }
    }
}
