# Implementation log (short Codex handoff)

Update this file after each significant milestone. Avoid transcript dumps.

- [x] Starter skeleton: Java 21, mod ID, NeoForge properties, status-only expiry checker, disabled staging config.
- [x] Offline pure Java smoke test script (run and record result in this session).
- [x] A: Gradle 8.14.3 / ModDevGradle 2.0.147 / Java 21 / NeoForge 21.1.248 build and dedicated-server startup validated.
- [x] B: Secret-safe fixed-topology configuration, private paths and AutoModpack 5 exclusion behavior verified.
- [x] C: ACME v2 DNS-01 + Cloudflare real implementation with fake tests.
- [ ] D: PKIX/hostname/keypair validation, crash-safe deployment and AutoModpack activation.
- [ ] E: Commands, scheduling/retry, dedicated-server/integration tests and operator documentation.

## Current blockers / open questions

- The pipeline is connected, but no real ACME/Cloudflare request or AutoModpack certificate restart has been performed. Keep production disabled pending a separate test-zone issuance and dedicated-server activation exercise.
- Milestone E still needs command permission/runtime integration tests and the final Java 21 server exercise with the required AutoModpack dependency installed.

## Pterodactyl credential compatibility (2026-09-27)

- Added an explicit, default-off `credentials.allowInsecureTokenFilePermissions` fallback for panels that expose neither environment variables nor chmod. It permits panel-managed read bits such as `0644` for the fixed token file; group/other writes, writable credential directories, path escapes, symlinks, oversized reads, secret logging and AutoModpack inclusion remain prohibited.
- Focused security review found that the first draft also skipped writable-directory checks; the implementation now retains those checks, and re-review found no remaining actionable issue.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `\.\gradlew.bat test build --no-daemon`: PASS, 71 tests, 0 failures/errors/skips. The built JAR contains no credential/token/key/certificate entries.

## Milestone A evidence (2026-09-27)

- Corrected the wrapper bootstrap URLs: the JAR comes from Gradle's official `v8.14.3` repository tag and is checked against Gradle's published wrapper checksum.
- Wrapper JAR SHA-256: `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`; distribution ZIP remains pinned to `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.
- Windows-equivalent offline smoke: PASS after making its path assertion platform-independent.
- `.\gradlew.bat test build --no-daemon`: PASS, 6 JUnit tests, 0 failures/errors/skips; output JAR `amp-autocert-0.1.0-SNAPSHOT.jar`.
- `.\gradlew.bat runServer --no-daemon`: reached dedicated-server `Done`, listed `amp_autocert`, Minecraft 1.21.1 and NeoForge 21.1.248, and logged the expected disabled status-only message. The Gradle PTY did not forward `stop`, so the process was terminated with Ctrl+C; graceful shutdown remains unverified.

## Operator topology update (2026-09-27)

- Minecraft SRV target updated to `hm.maicraft.tech:25598`; AutoModpack endpoint updated to `hm.maicraft.tech:25809`.
- Public amp-autocert configuration moved from `config/amp-autocert.properties` to `automodpack/amp-autocert.properties`; disabled/staging defaults and secret separation are unchanged.
- AutoModpack version reported as `5.0.0-rc.1`; the operator-provided source ZIP and runtime JAR were inspected directly.
- Windows offline smoke and `.\gradlew.bat test build --no-daemon`: PASS after the topology/path update; 6 JUnit tests, 0 failures/errors/skips.

## AutoModpack 5.0.0-rc.1 verification (2026-09-27)

- Runtime JAR SHA-256: `90cb9800e1f65ef0d160bb313acd00d7e73b0fb79dd85a6bb59be62c07051979`.
- `StoragePaths` in source and runtime bytecode resolve `automodpack/credentials/certificate.crt` and `private-key.pem`; `certificate.cert` is not the runtime filename.
- Pack groups include their `automodpack/host-modpack/<id>/` directory plus explicit `from-server` matches. `exclude` is applied to both sources; document explicit exclusions for `automodpack/credentials/**` and `automodpack/amp-autocert.properties` in every group.
- `/automodpack host restart` exists and requires command permission level 3. Its implementation schedules stop/start work on AutoModpack's background executor; amp-autocert must invoke the Minecraft command on the server thread and observe host readiness separately.

## Milestone B evidence (2026-09-27)

- Public config is `automodpack/amp-autocert.properties`; it contains no token or private-key fields and defaults to disabled + staging.
- Config rejects domains outside the exact apex/wildcard pair, unexpected Minecraft/AutoModpack endpoints, ambiguous booleans, and an invalid Cloudflare zone ID when enabled.
- Cloudflare token source is limited to `AMP_AUTOCERT_CF_API_TOKEN` or `automodpack/credentials/amp-autocert-cloudflare-token.txt`. File loading rejects symlinks, group/other-readable token modes, group/other-writable parent directories, unsupported permission audits, and oversized files; the final open uses `NOFOLLOW_LINKS`.
- Certificate, key, and nested account-state paths remain under the normalized server root and reject existing symlink components and leaf paths.
- Built JAR contains no credential, token, config, certificate, or private-key material. AutoModpack source confirms root files ship only through explicit `from-server` rules; explicit per-group exclusions are documented as defense in depth.
- Focused security review: both medium findings fixed; no remaining actionable findings for Milestone B.
- Windows offline smoke: PASS. `.\gradlew.bat test build --no-daemon`: PASS, 18 tests, 0 failures/errors/skips.

Live boundary: the operator's effective `automodpack/server.conf` was not supplied, so deployment must still confirm the documented exclusions exist in every configured group before enabling issuance.

## Milestone C increment 1: dependencies and Cloudflare DNS boundary (2026-09-27)

- Pinned acme4j client 5.1.0 and explicit Jar-in-Jar transitives: jose4j 0.9.6 and Bouncy Castle 1.86 (`bcprov`, `bcpkix`, `bcutil`). Minecraft-provided SLF4J 2.0.9 replaces acme4j's newer API dependency to satisfy Minecraft's strict constraint.
- Verified the built mod contains all five nested JARs plus `META-INF/jarjar/metadata.json`; versions, licenses and SHA-256 hashes are recorded in `docs/THIRD_PARTY.md`.
- Added a zone-scoped Cloudflare v4 client that can mutate only `_acme-challenge.maicraft.tech`, retains returned record IDs, treats delete-404 as idempotent success, caps response size, applies a whole-response deadline, and never includes tokens/raw bodies in errors or `toString()`.
- Added `DnsChallengeSet`: two distinct apex/wildcard values coexist before validation; partial publication cleans created records; failed cleanup retains a retry handle; close is idempotent and attempts all owned IDs before restoring interruption.
- Deterministic fake tests cover allowed/denied record names, Cloudflare failure redaction, simultaneous values, partial rollback, retryable cleanup, unowned records, interruption, oversized responses and stalled response bodies. No external DNS or Cloudflare request is made.
- Focused security review found three medium issues in the first draft; all were fixed and re-review reported no remaining actionable findings.
- `.\gradlew.bat test build --no-daemon`: PASS, 32 tests, 0 failures/errors/skips. Dedicated `runServer` reached `Done` with the mod disabled; the PTY again required forced process termination, so this does not prove graceful shutdown or a fresh-server nested-JAR load.

Increment 1 stopped before account persistence and issuance orchestration; the next section records their subsequent implementation.

## Milestone C increment 2: private account state and fake issuance orchestration (2026-09-27)

- Added staging/production-separated ACME account identity persistence under the private state root. Account keys use EC P-256, PKCS#8/X.509 PEM encoding, bounded no-follow reads, atomic writes, strict POSIX/Windows ACL handling, and JVM plus cross-process creation locks.
- Added an injectable issuance coordinator for the fixed `maicraft.tech` + `*.maicraft.tech` order. It validates the exact DNS owner/domain pair, publishes both TXT values together, performs deadline-aware exponential propagation polling, uses a separate bounded ACME deadline, generates an EC P-256 certificate key, and returns material in memory without deployment.
- DNS cleanup failures now retain a retry handle even after successful issuance/finalization; late or stalled resolver results cannot trigger ACME after the propagation deadline.
- Deterministic fake tests cover the successful orchestration path, propagation/backoff and late-result timeouts, ACME failure cleanup, final-cleanup retry, challenge rejection before DNS writes, secret-redacted results, persistent environment separation, restrictive permissions, symlink rejection, and concurrent account creation.
- Focused security review found three medium issues (cross-process key creation, lost final-cleanup ownership, and resolver deadline enforcement); all were fixed, and re-review found no remaining actionable issue in this scope.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `.\gradlew.bat test build --no-daemon` with the workspace Gradle cache: PASS, 45 tests, 0 failures/errors/skips; output `amp-autocert-0.1.0-SNAPSHOT.jar`.

Increment 2 stopped before the production acme4j adapter and restart recovery; the next section records the adapter work.

## Milestone C increment 3: production acme4j gateway (2026-09-27)

- Added the acme4j 5.1 production adapter for fixed Let's Encrypt staging/production directories, persisted-account login or idempotent account creation, fixed apex/wildcard orders, DNS-01 extraction/triggering, CSR execution and full-chain download.
- Persisted account URLs are pinned to the selected official Let's Encrypt environment and `/acme/acct/` resource path before any signed request. Remote checked and lazy-loading exception details are redacted at the gateway boundary.
- Added deadline-safe explicit authorization/order polling around `PollableResource.fetch()`. It refreshes time after every response, honors future `Retry-After`, clamps expired values to a minimum delay, rejects late success, bounds complete response-body work, and cancels interrupted/timed-out virtual-thread operations.
- Existing valid ACME authorizations are supported: zero, one or two pending DNS-01 values may be published, while simultaneous apex/wildcard challenges still coexist and cleanup retains only provider IDs owned by this order.
- Deterministic tests cover gateway delegation and domain rejection, directory/account-origin pinning, exception redaction, expired deadlines, stalled ACME operations, slow fetches, past/future `Retry-After`, valid-authorization reuse, and zero/one/two DNS challenge cleanup.
- Focused security review found three medium issues in the first adapter draft (unbounded response bodies, acme4j polling deadline edge cases, and unredacted lazy-load failures); all were fixed, and re-review found no remaining actionable issue in this increment.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `.\gradlew.bat test build --no-daemon` with the workspace Gradle cache: PASS, 59 tests, 0 failures/errors/skips. The built JAR still contains acme4j, jose4j and all pinned Bouncy Castle nested JARs.

Increment 3 stopped before restart recovery; the next section records the recovery journal and configuration work.

## Milestone C increment 4: crash recovery and configurable topology (2026-09-27)

- Added an atomically written, private, staging/production-separated issuance journal containing the ACME resource locations, transaction phase, challenges and returned Cloudflare IDs. Certificate private keys are deliberately not journaled before validation; an interrupted order is abandoned and cleaned rather than resumed with a lost key.
- Cloudflare TXT records carry a random per-order `amp-autocert:<UUID>` comment. Recovery uses Cloudflare's official exact-comment list filter to rediscover records created in the POST-response crash window, and deletes only journaled/marker-owned IDs. Existing operator records are preserved.
- Recovery covers prepared, partially published, triggered, ready, finalized and cleanup-interrupted states. DNS deletion remains idempotent; failed cleanup keeps the journal, attempts all owned IDs, and restores interruption afterward.
- A JVM plus OS file lock serializes the entire environment-specific recovery/issuance/cleanup transaction across service instances and processes.
- Moved deployment topology and operational values out of Java into the canonical packaged `config/amp-autocert.properties.example`: zone, apex/wildcard SANs, DNS owner, ACME directory URLs/network timeout, Cloudflare API root/TTL/zone ID, Minecraft hostname/SRV target, AutoModpack endpoint, propagation/renewal/check timings and restart policy.
- Configuration remains constrained: apex/wildcard pairing is derived from the zone; certificate-consuming hosts must be exactly one wildcard-covered label below it; provider endpoints require official HTTPS hosts; staging and production URLs cannot be swapped; timeout, TTL and port ranges are bounded.
- Focused security review found one high and three medium issues (environment URL swap, missing transaction lock, wildcard-depth mismatch and interrupted cleanup behavior). All were fixed; re-review found no remaining actionable issue in this increment.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `.\gradlew.bat test build --no-daemon`: PASS, 67 tests, 0 failures/errors/skips. The built JAR contains the canonical public configuration template and all pinned nested ACME/crypto JARs.

Milestone C is complete: operator dedicated-server logs confirmed discovery of all five nested ACME/crypto JARs, and the validation boundary is recorded below. That operator run used Java 25 rather than the required Java 21, so the final target-runtime deployment check remains outstanding. The backend remains intentionally disconnected from `AutoCertService`; no real ACME, Cloudflare or certificate deployment request was made.

## Milestone D increment 1: issued-certificate validation boundary (2026-09-27)

- Added a pure, non-writing deployment validator that accepts only production material with the exact configured apex/wildcard domain set. It parses bounded PEM/PKCS#8 input, requires an EC key of at least 256-bit strength, proves the key matches the first leaf certificate, and requires a leaf-first cryptographically linked chain.
- Validation requires current leaf validity, end-entity constraints, digital-signature usage, TLS server-auth EKU, exact DNS SANs, and coverage for both the typed Minecraft hostname and AutoModpack endpoint.
- PKIX path building uses the running JVM's default X.509 trust roots in production; deterministic tests inject a private test trust anchor and make no network requests. Staging ACME material is rejected before deployment validation.
- Focused security review found one medium issue in the first draft: a root-first bundle could pass independent PKIX building while remaining unsafe for AutoModpack's first-certificate behavior. Leaf-first matching plus issuer-order/signature checks and a regression test fixed it; re-review found no remaining actionable issue.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `\.\gradlew.bat test build --no-daemon`: PASS, 78 tests, 0 failures/errors/skips; output `amp-autocert-0.1.0-SNAPSHOT.jar`.

This validation increment itself does not write certificate files or activate AutoModpack. The replacement core is recorded below; server-thread host restart remains later Milestone D work.

## Milestone D increment 2: crash-safe credential replacement core (2026-09-27)

- Added a validator-gated two-file deployment transaction. New certificate/key material is written and forced inside the destination filesystem, existing complete pairs are copied to bounded backups, and key/certificate replacements require atomic moves. A JVM plus OS lock prevents concurrent deployment transactions.
- An atomically replaced non-secret journal records the transaction and phase. Uncommitted work rolls back automatically; restart recovery restores the previous pair (or removes a partial first installation). Committed/rolled-back cleanup is idempotent across a crash between transaction-directory removal and journal removal.
- The returned pending deployment commits only after a future activation/readiness step; closing it without commit restores the old pair. The lifecycle and AutoModpack activation remain disconnected.
- Added explicit default-off `credentials.allowInsecureDeploymentFilePermissions` for Pterodactyl installations that cannot change modes. It performs no chmod/ACL changes, still rejects POSIX paths writable by group/others, and documents that panel-default read access may expose private material to other container users.
- Focused security review found one high and three medium crash/concurrency/path issues across the initial drafts: commit-journal failure resolution, cross-thread lock ownership, writable ancestors, and a committed-journal rename/fsync rollback window. The implementation now uses a cross-thread semaphore plus OS lock, rejects writable POSIX ancestors, persists `ROLLING_BACK` before restoration, and distinguishes durable commit from cleanup; final re-review found no remaining actionable issue.
- Deterministic tests cover validation-before-write, first install/commit, implicit rollback, partial replacement failure, crash recovery after one or both replacements, committed-cleanup recovery, pre/post commit-journal failures, cross-thread finalization, incomplete existing pairs and symlinks.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `\.\gradlew.bat test build --no-daemon`: PASS, 91 tests, 0 failures/errors/skips. The built JAR contains no credentials, token, key, certificate or deployment-journal entries.

## Milestone D increment 3: version-pinned AutoModpack activation and rollback (2026-09-28)

- Re-verified the operator-provided AutoModpack 5.0.0-rc.1 source and runtime bytecode. `/automodpack host restart` requires permission level 3, returns before its background stop/start completes, and exposes no completion callback. The exact release does expose `Constants.hostServer`, `NettyServer.isRunning()` and `getCertificateFingerprint()`.
- Added a version-pinned host controller. It dispatches the verified command through the Minecraft server executor and rejects execution if the runnable is not on the server thread. Queued commands are cancellation-, deadline- and shutdown-gated.
- Readiness does not assume that configured port 25809 is directly reachable: magic-packet and hole-punch modes are supported by requiring AutoModpack's own running state plus the exact SHA-256 fingerprint of the newly installed leaf certificate.
- Added a background-only activation transaction. It commits the pending deployment only after readiness; failure restores the previous files and, when the server is not stopping, restarts and verifies the previously running host. Durable-commit cleanup errors remain committed for crash recovery rather than rolling back active credentials.
- AutoModpack start/stop are individually synchronized but asynchronous relative to the command result. Rollback therefore acquires the exact `hostServer` monitor before restoring the two-file pair, preventing TLS loading from observing a half-restored key/certificate. Non-shutdown interruption reconciles the old host before restoring the interrupt status.
- Added bounded public settings `host.activationTimeoutSeconds=60` and `host.readinessPollSeconds=1`; invalid or inverted bounds are rejected.
- Deterministic tests cover exact-fingerprint success, server-thread dispatch, absent/incompatible and external-TLS modes, rejected restart, readiness timeout, shutdown, non-shutdown interruption, durable cleanup failure, cancelled queued commands, and a delayed AutoModpack transition racing rollback.
- Focused security review found one high and two medium async race/cancellation findings. The host-monitor serialization, queued-command gate and interruption reconciliation fixed them; focused re-review found none remaining.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `\.\gradlew.bat test build --no-daemon`: PASS, 103 tests, 0 failures/errors/skips. Artifact `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar` is 8,445,628 bytes and contains no credential/token/key/certificate/deployment-journal material.

This increment is still intentionally disconnected from `AutoCertService`; the next step is the single-flight renewal coordinator and clean lifecycle shutdown. The operator's observed runtime remains Java 25, so the required Java 21 dedicated-server activation test is not yet verified.

## Milestone D increment 4: single-flight lifecycle coordinator (2026-09-28)

- Connected recovery, expiry policy, production ACME issuance, validator-gated deployment and exact-fingerprint AutoModpack activation through a single background renewal coordinator.
- Startup receives the actual `MinecraftServer`; network, DNS, certificate and filesystem work remains on the private worker, while the verified restart command alone is dispatched to the server thread. Server stop gates queued work and interrupts the worker; issuance/deployment journals provide restart recovery.
- Recovery now runs before every expiry decision, so a still-valid certificate cannot leave an interrupted order's owned DNS records or an unfinished deployment transaction orphaned. Deployment rollback is two-phase: its durable `ROLLED_BACK` marker is cleared only after AutoModpack reloads the restored fingerprint; initialized stopped hosts are also reconciled.
- Added a bounded system DNS TXT resolver with a hard deadline around JNDI, NXDOMAIN-as-not-yet-visible handling and deterministic TXT decoding tests.
- Scheduled staging mode never issues or deploys a certificate. Production renewal additionally requires `host.restartAfterRenew=true`, a successful AutoModpack compatibility/internal-TLS preflight, and a cross-process persisted issuance-attempt reservation before an ACME order is created. `renew.failureCooldownHours` defaults to 48 across restarts, including for old configs without the new key.
- Cancellation now begins at `ServerStoppingEvent`; `ServerStoppedEvent` retains idempotent cleanup.
- Focused security review reported recovery/reconciliation, shutdown timing and rate-limit-storm findings. All reported high/medium paths were addressed with durable host reconciliation, early cancellation, persisted cooldown and conservative prepare-failure rollback markers; targeted regression tests pass.
- Git Bash `scripts/offline-smoke-test.sh`: PASS. `\.\gradlew.bat test build --no-daemon`: PASS, 119 tests, 0 failures/errors/skips. Artifact `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar` is 8,468,026 bytes and contains no credential/token/key/certificate/deployment-journal entries.
- Java 21.0.9 `runServer` reached dedicated-server `Done` with Minecraft 1.21.1, NeoForge 21.1.248 and the mod loaded. An older run config missing the two host timeout keys first reproduced the operator's startup error; migration-safe defaults now load that same old config successfully. The Gradle PTY still did not forward `stop`, so the process required Ctrl+C and graceful `ServerStoppingEvent` runtime observation remains unverified.

Milestone D remains unchecked until the connected lifecycle passes the required Java 21 dedicated-server test. No real ACME, Cloudflare or AutoModpack restart request was made during deterministic tests.

## Milestone D shutdown observability (2026-09-28)

- Added one idempotent, secret-free lifecycle message when `ServerStoppingEvent` cancels scheduled checks and interrupts background renewal work. The later `ServerStoppedEvent` cleanup does not log a duplicate.
- This is an observability-only change: issuance, deployment, activation and scheduling behavior are unchanged.
- Verification was intentionally scoped for this short iteration: Git Bash `scripts/offline-smoke-test.sh` passed, and `\.\gradlew.bat compileJava jar --no-daemon` completed successfully without running the 119-test JUnit suite.
- Packaged artifact: `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`, 8,468,069 bytes, SHA-256 `7e2876925802aedf385e2c925f9c36c4d1b7a63c6e3c45ce9e3c477d261d2ec8`.
- Runtime observation is still required: install this JAR on the Java 21 server, allow the initial check to run, then perform a normal restart and confirm the new stopping message appears.

Operator follow-up confirmed the stopping message appeared exactly once between Minecraft's two shutdown messages. This proves the `ServerStoppingEvent` handler ran and cancelled the scheduled lifecycle without preventing normal shutdown. The supplied excerpt did not identify the JVM version and did not prove interruption of an in-flight ACME operation, so those runtime boundaries remain open.

## Milestone E increment 1: status and manual check commands (2026-09-28)

- Added permission-level-3 `/ampautocert status` and `/ampautocert check` through NeoForge 21.1.248's `RegisterCommandsEvent`.
- Status reads an immutable cached snapshot only: lifecycle/configured environment, certificate expiry and days remaining, deployed DNS SANs, last check/outcome, and persisted cooldown expiry. It exposes no token, private-key, account-key or raw provider response data and performs no command-thread disk/network work.
- Manual checks run on the existing private renewal executor and retain every staging, production, cooldown, validation, deployment and rollback gate. A coalescing flag rejects repeated commands while a check is queued or running.
- Documented that `/ampautocert renew` remains unavailable until its force/rate-limit semantics and runtime permission tests are implemented.
- Focused verification only: Git Bash `scripts/offline-smoke-test.sh` passed; `\.\gradlew.bat test --tests tech.maicraft.ampautocert.OperatorStatusTest --tests tech.maicraft.ampautocert.RenewalCoordinatorTest jar --no-daemon` passed 12 tests with 0 failures/errors/skips. The complete test suite and dedicated-server command exercise were intentionally not run for this short iteration.
- Artifact `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`: 8,479,076 bytes; SHA-256 `6db50ac43f6db76cf560ee1932e63f5652c46361ee4085a397eb6ae98e5f9669`.

Operator runtime follow-up (`latest (8).log`) confirmed both commands on NeoForge 21.1.248 and AutoModpack 5.0.0-rc.1: the initial and manual checks ran on `amp-autocert-renewal`, `/status` updated its timestamp/outcome, and shutdown cancelled the lifecycle cleanly. The existing AutoModpack-generated certificate parsed with expiry `2027-09-27T16:11:13Z` but contains no DNS SAN entries, so `Deployed DNS SANs: not available` is accurate. The host was still using Java 25, not the required Java 21, and staging-safe mode made no DNS or credential changes.

## Milestone E increment 2: guarded renewal and retry scheduling (2026-09-28)

- Added permission-level-3 `/ampautocert renew`. It may override only the normal certificate-validity decision; staging mode rejects it before queueing, while the coordinator independently retains staging separation, AutoModpack preflight, the persisted production-attempt cooldown, validation, rollback and single-flight gates.
- Added migration-safe public retry settings: `retry.initialMinutes=15`, `retry.maxHours=12`, and `retry.jitterPercent=20`, with bounded validation. Failed checks retry with capped exponential backoff and symmetric jitter.
- A failure after a production attempt reservation cannot use exponential retry to evade the 48-hour persisted cooldown. The first retry observes the cooldown and schedules no earlier than the recorded cutoff plus bounded jitter.
- Added a generation-protected one-entry retry queue. It releases its slot before invoking a retry so consecutive failures can schedule successors, rejects stale callbacks, keeps the earlier of competing retries, cancels on shutdown, and preserves a pending forced-renewal retry across ordinary `NOT_DUE` checks.
- Focused security review found two medium retry-chain issues in the first draft: the executing task blocked its successor, and a periodic valid-certificate check cancelled a pending forced retry. Both were fixed; follow-up review found no additional high/medium issues.
- Scoped verification: Git Bash `scripts/offline-smoke-test.sh` passed; focused configuration, retry-policy, retry-queue, coordinator and operator-status tests passed (31 tests, 0 failures/errors/skips); compilation and JAR packaging passed. The complete suite and dedicated-server command/permission exercise were intentionally deferred.
- Packaged JAR retains the expected acme4j, jose4j and Bouncy Castle nested dependencies and contains no credential, token, private-key, certificate, account-key or deployment-journal entry.
- Artifact `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`: 8,487,326 bytes; SHA-256 `6b593dfe501540ba1e0db28b3dabe2a265104373b79a1551a09494ac71965dc3`.

## Milestone E increment 3: production operator runbook (2026-09-28)

- Added `docs/PRODUCTION_SETUP.md` covering the zone-scoped Cloudflare token, DNS-only origin routing, per-group AutoModpack exclusions, Pterodactyl permission fallbacks, complete private backup/restore, staging-to-production gates, rotation/rollback, persisted cooldown, troubleshooting, and the one-time migration impact of existing client fingerprint pins.
- The runbook preserves the current truth boundary: this build's staging mode is inspection-only, and production remains conditional on a separate test-zone exercise plus a Java 21 dedicated-server integration pass. It does not claim that deterministic fake tests prove live Cloudflare, ACME, or AutoModpack behavior.
- Documentation-only verification used targeted content/link/config checks; no Java code changed, so Gradle/JUnit and a server run were intentionally not repeated for this short increment.

## Milestone E increment 4: required AutoModpack dependency (2026-09-28)

- Declared AutoModpack's loaded NeoForge implementation mod ID/version `automodpack_mod` `5.0.0-rc.1` as a required server-side dependency, ordered before amp-autocert. The outer bootstrap JAR declares `automodpack`, but its staged `1.21.1-neoforge.jar` and the NeoForge runtime mod list both identify the dependency-visible implementation as `automodpack_mod`. The version is sourced from `gradle.properties` into generated NeoForge metadata rather than duplicated in the template.
- Per operator direction, a missing or differently versioned AutoModpack installation is now a NeoForge dependency-validation startup failure. This supersedes the earlier graceful-absence integration requirement; runtime reflection checks remain defense in depth for an initialized but incompatible host API.
- Initial integration exposed that the outer bootstrap ID is not the NeoForge runtime mod ID; depending on `automodpack` still failed even with the JAR installed. The dependency was corrected to `automodpack_mod` from the staged 1.21.1 implementation metadata. Focused verification below covers both the corrected missing-dependency and installed-dependency paths.
- Optional JUnit/full-build checks were omitted because no Java code changed. The packaged JAR retains all five expected ACME/crypto nested dependencies: `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`, 8,487,351 bytes, SHA-256 `5212942d43202565527dfea1959c31e736a2f7b51fe92c2c23b6552e4c6f48a0`.

## Milestone E increment 5: explicit staging issuance probe (2026-09-29)

- Added permission-level-3 `/ampautocert staging-issue`, accepted only when the lifecycle is enabled with `acme.environment=staging` and the new migration-safe, default-off `acme.allowStagingIssuance=true` gate.
- The probe uses the existing background worker and ACME/Cloudflare DNS-01 issuer. Owned TXT cleanup remains inside the issuance transaction. Returned staging material is structurally inspected in memory for key match, leaf-first chain signatures/order, validity, exact SANs, and configured host coverage, then discarded; it is never PKIX-trusted, written to AutoModpack credentials, activated, or reported as production-valid.
- Before opening an order, the probe reserves the existing persisted attempt gate under the separate staging environment. Repeated commands and restarts therefore cannot bypass the configured `renew.failureCooldownHours` interval. Scheduled staging checks remain inspection-only and create no orders.
- Focused security review initially found misleading trust wording and a missing staging rate-limit guard. Both were corrected; the follow-up found no remaining high/medium issue.
- Scoped verification only: focused configuration, staging structural-inspection, probe/cooldown, and operator-status tests passed (25 tests, 0 failures/errors). Compilation and JAR packaging passed. The complete suite, dedicated server, and all live ACME/Cloudflare operations were intentionally omitted for this short iteration.
- Artifact `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`: 8,496,713 bytes; SHA-256 `66e99504d89f88c30b027635fecabb3bf3ff47caaa2f6bdd646cf53ebb86aa0b`. Archive scan found no token, private key, certificate, account-key, issuance-journal, or deployment-journal runtime files.

Live staging issuance is still unverified. Use only a separately configured test zone and restricted test-zone token; do not enable this probe against the production zone merely because deterministic tests pass.

## Milestone E increment 6: safe diagnostic mode and live ACME owner fix (2026-09-29)

- Operator logs from the first live staging attempt proved Java/NeoForge/AutoModpack startup, Let's Encrypt staging account creation and order creation for the configured apex/wildcard pair. The attempt failed before Cloudflare was contacted because acme4j 5.1 returns the DNS owner as an absolute FQDN with one trailing dot while the strict configured owner is canonical without that dot. No TXT record or certificate file was changed.
- ACME challenge validation now removes exactly one terminal FQDN dot and then retains the existing exact comparison against the configured `_acme-challenge` owner. A focused regression test covers both apex and wildcard challenges and verifies publication still uses the canonical configured name.
- Added migration-safe `debugmode=false`. When true, amp-autocert emits safe phase/count/configured-owner/propagation/cleanup diagnostics. It never includes challenge values, contacts, provider tokens, ACME account/order locations, raw responses, certificate bytes or private keys.
- Raw acme4j connector/JWS and jose4j DEBUG output is suppressed in both modes through explicit runtime logger policy. The initial operator `debug.log` contained transient ACME protocol payloads but no Cloudflare token; future dedicated-server verification must confirm those third-party lines are absent.
- Focused security review found no actionable high/medium issue. Scoped configuration and ACME issuance tests passed (29 tests, 0 failures/errors), including debug-redaction assertions; compilation and JAR packaging passed. The full suite and live retry were intentionally omitted.
- Artifact `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`: 8,499,024 bytes; SHA-256 `d97c88adde47cd0c6ffb3a9779be45abc62b6a4278bce3aba74dba531b60ef9e`. Archive scan found no token, private key, certificate, account/issuance state, cooldown state or deployment journal runtime files.

The live server still reported Java 25 rather than the required Java 21. Its staging attempt reservation used the configured 48-hour cooldown, so another order must not be attempted until the cooldown expires (or the configured interval naturally permits it); do not delete private state to bypass the guard.

## Milestone E increment 7: public DoH propagation consensus and expanded safe diagnostics (2026-09-29)

- Replaced the runtime container/JNDI TXT lookup with bounded JSON DNS-over-HTTPS checks against the configured, strictly allowlisted Cloudflare and Google endpoints. Propagation uses the intersection of successful resolver answers, so disagreement cannot trigger ACME prematurely; one resolver transport failure is tolerated, while failure of all resolvers is retried only within the existing overall deadline.
- Added migration-safe `dns.propagationInitialDelaySeconds=15` and `dns.dohResolvers=https://cloudflare-dns.com/dns-query,https://dns.google/resolve`. Arbitrary resolver URLs, credentials, ports, queries and incomplete lists are rejected to prevent configuration-driven SSRF.
- Expanded `debugmode=true` with resolver hostname, DNS/HTTP outcome category, elapsed milliseconds, TXT counts, consensus count, lookup attempts, bounded retry delays, and cleanup/recovery phases. TXT contents, raw response bodies, token/key material, ACME locations and certificate bytes remain excluded.
- Scoped verification only: configuration, DoH resolver and ACME issuance tests passed (36 tests, 0 failures/errors). Tests cover quoted/chunked TXT decoding, exact-owner/type filtering, cross-resolver disagreement, partial/all resolver failure, deadline enforcement, initial delay, transient retry and diagnostic redaction. The full suite and live DoH/ACME calls were intentionally omitted.
- Security review found that a DoH request begun near expiry could outlive the shared propagation deadline by the transport timeout. Each in-flight request is now interrupted at the remaining shared deadline, with a focused regression test; no SSRF bypass or raw TXT/token leakage was found.
- Packaged artifact: `build/libs/amp-autocert-0.1.0-SNAPSHOT.jar`, 8,508,277 bytes, SHA-256 `9c3cd1da1ae3d4f5a07dfba7281a4ed5cb1d14b8706711be3a96631d732d8cd6`.

Live verification remains required after the staging cooldown permits another attempt. Cloudflare TTL controls downstream caching, not how long amp-autocert owns the record; the transaction still deletes its TXT records after success or failure.
