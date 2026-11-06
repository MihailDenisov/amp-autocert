package tech.maicraft.ampautocert;

import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only local/provider preflight. It never creates ACME orders, DNS records, or files. */
final class PreflightService {
    private final AutoCertConfig config;
    private final Path root;
    private final Clock clock;
    private final AutoModpackConfigSource autoModpack;
    private final HttpTransport http;
    private final TokenLoader tokenLoader;
    private final RunnableCheck credentialTargetCheck;

    PreflightService(AutoCertConfig config, Path root, Clock clock,
                     AutoModpackConfigSource autoModpack, HttpTransport http,
                     TokenLoader tokenLoader, RunnableCheck credentialTargetCheck) {
        this.config = config;
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.autoModpack = autoModpack;
        this.http = http;
        this.tokenLoader = tokenLoader;
        this.credentialTargetCheck = credentialTargetCheck;
    }

    PreflightReport run() throws InterruptedException {
        return inspect(false).report();
    }

    ReconciliationPreflight runForReconciliation() throws InterruptedException {
        return inspect(true);
    }

    private ReconciliationPreflight inspect(boolean reconciliation) throws InterruptedException {
        List<PreflightReport.Check> checks = new ArrayList<>();
        checks.add(pass("configuration", "minimal configuration is valid for " + config.originHostname()));

        ConfigurationBaseline current = inspectAutoModpack(checks);
        inspectCredentialTargets(checks);
        inspectCertificate(checks, current, reconciliation);
        inspectEnrollmentState(checks, current, reconciliation);
        inspectCloudflare(checks);
        return new ReconciliationPreflight(new PreflightReport(checks), Optional.ofNullable(current));
    }

    private ConfigurationBaseline inspectAutoModpack(List<PreflightReport.Check> checks)
            throws InterruptedException {
        try {
            AutoModpackIdentitySnapshot effective = autoModpack.effective(config);
            AutoModpackIdentitySnapshot onDisk = autoModpack.onDisk(config);
            ConfigurationBaseline effectiveBaseline = ConfigurationBaseline.from(config, effective);
            ConfigurationBaseline diskBaseline = ConfigurationBaseline.from(config, onDisk);
            List<String> differences = effectiveBaseline.differences(diskBaseline);
            if (!differences.isEmpty()) {
                checks.add(fail("AutoModpack", "loaded and on-disk settings differ: "
                        + String.join(", ", differences)));
                return effectiveBaseline;
            }
            checks.add(pass("AutoModpack", effective.autoModpackVersion() + " "
                    + effective.connectionMode().name().toLowerCase(java.util.Locale.ROOT)
                    + " endpoint " + effective.endpoint() + "; internal TLS owns termination"));
            return effectiveBaseline;
        } catch (IOException | RuntimeException failure) {
            checks.add(fail("AutoModpack", "effective or on-disk configuration is unavailable or unsupported"));
            return null;
        }
    }

    private void inspectCredentialTargets(List<PreflightReport.Check> checks) throws InterruptedException {
        try {
            credentialTargetCheck.run();
            checks.add(pass("credential target", "paths and existing certificate/key pair are structurally safe"));
        } catch (IOException | RuntimeException failure) {
            checks.add(fail("credential target", "path, pair consistency, writability, or permissions are unsafe"));
        }
    }

    private void inspectCertificate(List<PreflightReport.Check> checks, ConfigurationBaseline current,
                                    boolean reconciliation) {
        try {
            CertificatePaths paths = CertificatePaths.inside(root);
            Optional<CertificateInspector.Summary> summary = CertificateInspector.summary(paths.fullChain());
            if (summary.isEmpty()) {
                checks.add(warning("certificate", "no deployed certificate is present"));
                return;
            }
            List<String> sans = summary.get().dnsSans();
            boolean originCovered = coveredBy(sans, config.originHostname());
            boolean endpointCovered = current != null && coveredBy(sans, current.endpointHost());
            if (!originCovered || !endpointCovered) {
                checks.add(reconciliation
                        ? warning("certificate", "deployed DNS SANs do not yet cover the reconciled origin and endpoint")
                        : fail("certificate", "deployed DNS SANs do not cover current origin and endpoint"));
                return;
            }
            checks.add(pass("certificate", "deployed certificate expires " + summary.get().expiresAt()
                    + " and covers the current origin and endpoint"));
        } catch (IOException | CertificateException | RuntimeException failure) {
            checks.add(fail("certificate", "deployed certificate metadata is unavailable or invalid"));
        }
    }

    private void inspectEnrollmentState(List<PreflightReport.Check> checks, ConfigurationBaseline current,
                                        boolean reconciliation) {
        try {
            EnrollmentStateStore.State state = new EnrollmentStateStore(root).load();
            if (state.baseline().isEmpty()) {
                checks.add(warning("enrollment state", "no accepted configuration baseline exists"));
                return;
            }
            if (current == null) {
                checks.add(fail("enrollment state", "current AutoModpack configuration could not be compared"));
                return;
            }
            ConfigurationDriftDetector.Result drift = ConfigurationDriftDetector.compare(
                    state.baseline().orElseThrow(), current);
            if (!drift.stable()) {
                PreflightReport.Check changed = reconciliation
                        ? warning("enrollment state", "operator is reconciling configuration fields: "
                        + String.join(", ", drift.changedFields()))
                        : fail("enrollment state", "configuration drift: "
                        + String.join(", ", drift.changedFields()));
                checks.add(changed);
                return;
            }
            if (state.phase() != EnrollmentStateStore.Phase.ACTIVE
                    && state.phase() != EnrollmentStateStore.Phase.STAGING_VALIDATED) {
                checks.add(reconciliation
                        ? warning("enrollment state", "operator is reconciling persisted phase " + state.phase().name())
                        : fail("enrollment state", "persisted phase is " + state.phase().name()));
                return;
            }
            checks.add(pass("enrollment state", "baseline matches; phase is " + state.phase().name()));
        } catch (IOException | RuntimeException failure) {
            checks.add(fail("enrollment state", "persisted enrollment state is unavailable or invalid"));
        }
    }

    private void inspectCloudflare(List<PreflightReport.Check> checks) throws InterruptedException {
        String token;
        try {
            token = tokenLoader.load();
        } catch (IOException | RuntimeException failure) {
            checks.add(fail("Cloudflare credential", "token is missing, empty, or stored unsafely"));
            return;
        }
        CloudflarePreflightClient cloudflare;
        try {
            cloudflare = new CloudflarePreflightClient(http, config, token);
            token = null;
            cloudflare.verifyToken();
            checks.add(pass("Cloudflare token", "token is active"));
        } catch (IOException | RuntimeException failure) {
            token = null;
            checks.add(fail("Cloudflare token", "token verification failed or token is inactive"));
            return;
        }
        CloudflarePreflightClient.Zone zone;
        try {
            zone = cloudflare.verifyZone();
            checks.add(pass("Cloudflare zone", "zone ID, name, active status, and authoritative type match"));
        } catch (IOException | RuntimeException failure) {
            checks.add(fail("Cloudflare zone", "configured zone access or authoritative state could not be verified"));
            return;
        }
        try {
            DohDnsNsResolver resolver = new DohDnsNsResolver(http, config.dnsDohResolvers(), clock);
            Instant deadline = clock.instant().plus(config.acmeNetworkTimeout().multipliedBy(2));
            Set<String> observed = resolver.resolve(config.zoneName(), deadline);
            if (!observed.equals(zone.assignedNameservers())) {
                checks.add(fail("public NS delegation", "public delegation does not match Cloudflare assignment"));
            } else {
                checks.add(pass("public NS delegation", "independent resolvers match Cloudflare assignment"));
            }
        } catch (IOException | RuntimeException failure) {
            checks.add(fail("public NS delegation", "independent public delegation could not be verified"));
        }
    }

    private static boolean coveredBy(List<String> sans, String hostname) {
        String host = AutoCertConfig.normalizedDnsName(hostname, "hostname", false);
        for (String raw : sans) {
            String san = raw.toLowerCase(java.util.Locale.ROOT);
            if (san.equals(host)) return true;
            if (san.startsWith("*.") && AutoCertConfig.covers(host, san.substring(2)) && !host.equals(san.substring(2))) {
                return true;
            }
        }
        return false;
    }

    private static PreflightReport.Check pass(String name, String detail) {
        return new PreflightReport.Check(PreflightReport.Level.PASS, name, detail);
    }
    private static PreflightReport.Check warning(String name, String detail) {
        return new PreflightReport.Check(PreflightReport.Level.WARNING, name, detail);
    }
    private static PreflightReport.Check fail(String name, String detail) {
        return new PreflightReport.Check(PreflightReport.Level.FAIL, name, detail);
    }

    @FunctionalInterface interface TokenLoader { String load() throws IOException; }
    @FunctionalInterface interface RunnableCheck { void run() throws IOException, InterruptedException; }

    record ReconciliationPreflight(PreflightReport report, Optional<ConfigurationBaseline> candidate) {
        ReconciliationPreflight {
            if (report == null) throw new IllegalArgumentException("Preflight report is required");
            candidate = candidate == null ? Optional.empty() : candidate;
        }
    }
}
