# amp-autocert

Starter VS Code project for a **server-only** Minecraft **1.21.1** mod on **NeoForge 21.1.248**, using **Java 21**. Intended outcome: manage public Let's Encrypt certificates for AutoModpack via Cloudflare DNS-01 without OS-level Certbot or cron.

> **Status: EXPERIMENTAL RENEWAL LIFECYCLE, NOT YET PRODUCTION-VALIDATED.** The single-flight coordinator connects the tested ACME, Cloudflare DNS-01, validation, crash-safe replacement, and AutoModpack 5.0.0-rc.1 activation/rollback components. Scheduled staging mode remains inspection-only. A separate operator-only staging probe can issue and structurally inspect a certificate without trusting or deploying it, but its live test-zone exercise and the Java 21 dedicated-server pass are still required before production enablement.

## Open in VS Code

1. Install **JDK 21**, Visual Studio Code, and the Java/Gradle extensions suggested in `.vscode/extensions.json`.
2. Extract this archive and open the **`amp-autocert/` project folder**, not its parent.
3. Run the VS Code task **Gradle: test** or `./gradlew test` (Windows: `gradlew.bat test`). On first run, the supplied launcher downloads the **official Gradle 8.14.3 wrapper JAR** and verifies its published SHA-256; Gradle downloads NeoForge dependencies. Internet access is required for the first full build. The starter intentionally does not ship an unverified binary wrapper.
4. Run the offline Java-only check anytime: `sh scripts/offline-smoke-test.sh` (requires JDK 21 and POSIX shell). VS Code's default build task runs `./gradlew build`.
5. Open Codex in this repository. The root `AGENTS.md`, `.codex/config.toml`, `.codex/agents/` and `.agents/skills/` provide task context, budgeted subagents, security boundaries and reusable workflows. Paste the English prompt in `CODEX_TASK.md` §4 into Codex.

## Your topology

| Service | Name / port |
| --- | --- |
| Minecraft join hostname | `play.maicraft.tech` |
| DNS A record | `hm.maicraft.tech` -> actual public IP, Cloudflare DNS only |
| Minecraft SRV | `_minecraft._tcp.play.maicraft.tech` -> `hm.maicraft.tech:25598` |
| AutoModpack endpoint | `hm.maicraft.tech:25809` |
| Requested Let's Encrypt SANs | `maicraft.tech`, `*.maicraft.tech` |
| DNS validation | Cloudflare API, DNS-01; no DNSSEC required |

Cloudflare **Origin CA is not sufficient** for direct Minecraft clients; use Let's Encrypt or another publicly trusted CA. Wildcard `*.maicraft.tech` covers both `play` and `hm` hostnames.

## Server configuration (before production enablement)

- AutoModpack implementation mod ID `automodpack_mod` is a required server-side
  NeoForge dependency pinned to exactly `5.0.0-rc.1`. NeoForge rejects server
  startup during dependency validation if that mod is missing or has another
  version; amp-autocert does not provide an AutoModpack-absent operating mode.
- Copy `config/amp-autocert.properties.example` to the actual Minecraft server's `automodpack/amp-autocert.properties`. It is a non-secret operational file. Keep `enabled=false`, `acme.environment=staging`, and `acme.allowStagingIssuance=false` until a separate test zone and restricted token are ready. Scheduled staging mode never creates or deploys a certificate. The explicit staging probe structurally inspects and discards its untrusted result; production issuance requires `enabled=true`, `acme.environment=production`, and `host.restartAfterRenew=true`.
- The properties file is the canonical source for the DNS zone/challenge owner, certificate domains, Let's Encrypt directory URLs, Cloudflare API root/TTL, public DNS-over-HTTPS resolvers, Minecraft hostname/SRV target, AutoModpack endpoint and bounded network/propagation timings. Apex/wildcard pairing, same-zone one-label hosts and official HTTPS provider endpoints are validated before use. Propagation waits `dns.propagationInitialDelaySeconds=15`, then requires the TXT values to agree across Cloudflare and Google DoH; one temporarily unavailable resolver is tolerated.
- `debugmode=false` is the default concise logging mode. Setting it to `true` adds safe ACME phase, resolver hostname/status/latency, TXT count, retry timing, consensus and cleanup/recovery diagnostics. Third-party raw ACME/JWS/DNS responses, challenge values, provider tokens, account state, certificate bytes and private keys remain suppressed in both modes.
- `host.activationTimeoutSeconds` and `host.readinessPollSeconds` bound the verified AutoModpack restart/readiness wait; readiness requires AutoModpack to report both a running host and the exact SHA-256 fingerprint of the newly installed leaf certificate.
- `renew.failureCooldownHours` defaults to 48 and is persisted across restarts before a production issuance attempt. This prevents repeated activation failures or server restarts from creating an ACME order storm. Older configuration files safely use 48 hours when the key is absent.
- Failed background checks use bounded exponential retry configured by `retry.initialMinutes`, `retry.maxHours`, and `retry.jitterPercent` (defaults: 15 minutes, 12 hours, 20%). Once a production issuance attempt has been reserved, retries wait until after the persisted issuance cooldown instead of bypassing it.
- Existing configurations may add the new keys from the example to customize them. If omitted, `dns.propagationInitialDelaySeconds=15`, the two supported DoH endpoints, `host.activationTimeoutSeconds=60`, `host.readinessPollSeconds=1`, and `renew.failureCooldownHours=48` are applied as migration-safe defaults. Secrets still do not belong in this file.
- Configure Cloudflare API token using **only** `AMP_AUTOCERT_CF_API_TOKEN` or a file at `automodpack/credentials/amp-autocert-cloudflare-token.txt` readable only by the server process. The token should have zone-scoped `Zone / DNS / Edit` permission. The `.gitignore` excludes this file.
- If a Pterodactyl provider permits neither environment variables nor permission changes, set `credentials.allowInsecureTokenFilePermissions=true` and use that same fixed token file. This deliberately accepts panel-managed read permissions such as `0644`; writable-by-other token files/directories, alternate paths and symlinks remain rejected. Restrict panel/SFTP access to trusted administrators and keep `automodpack/credentials/**` excluded from every modpack group. Leave this option `false` everywhere else.
- On the same restricted hosting, `credentials.allowInsecureDeploymentFilePermissions=true` prevents the future deployer from calling `chmod` or rewriting ACLs for staged certificates, keys, backups and journals. It still refuses POSIX paths writable by group/others, but may leave private material readable according to the panel/umask defaults. Restrict panel/SFTP access and leave this option `false` when permission management works.
- Never put secrets in the public amp-autocert properties file. Explicitly exclude `automodpack/amp-autocert.properties` as well as every credential/state directory from AutoModpack's file rules; do not rely on undocumented exclusion behavior.
- AutoModpack `5.0.0-rc.1` source and runtime JAR both use `automodpack/credentials/certificate.crt` for the full chain and `automodpack/credentials/private-key.pem` for an unencrypted PKCS#8 private key. The bytecode does not use `certificate.cert`.
- In `automodpack/server.conf`, add `automodpack/credentials/**` and `automodpack/amp-autocert.properties` to every group's `exclude` list. Version 5 includes only `automodpack/host-modpack/<group>/` plus paths selected by that group's `from-server`; these exclusions are defense in depth against future or operator-added broad rules.
- Do not publish a staging cert to users. Existing players with manual certificate pins must clear the old pin once when moving to public CA; subsequent public CA renewals should not require re-pinning on new clients.

## Operator commands

The dedicated server registers these permission-level-3 commands:

- `/ampautocert status` reads the cached lifecycle snapshot and reports the configured ACME environment, certificate expiry/days remaining, deployed DNS SANs, last check/outcome, and any issuance cooldown. It never reads credentials or performs network I/O.
- `/ampautocert check` queues one immediate check on the existing background worker. Repeated requests are coalesced while a check is queued or running. All normal staging, production, cooldown, validation, deployment, and rollback gates still apply; this command does not force issuance.
- `/ampautocert staging-issue` is accepted only with `acme.environment=staging` and `acme.allowStagingIssuance=true`. It creates the configured DNS-01 records, obtains a Let's Encrypt staging certificate, inspects its key/chain structure, SANs and hostname coverage in memory, cleans up its owned TXT records, and discards the untrusted result. A persisted environment-specific cooldown prevents repeated orders. It never writes or activates AutoModpack credentials.
- `/ampautocert renew` requests renewal even when the deployed certificate is not yet due. It is rejected outside production mode and cannot bypass the persisted issuance cooldown, AutoModpack preflight, certificate validation, deployment rollback, or the single-flight gate. Use it only for an intentional production rotation.

All commands inherit the parent permission-level-3 requirement. Dedicated-server permission-denial testing remains part of the final integration pass.

The complete preparation, restricted Cloudflare token, backup, staging-to-production,
rotation, rollback, fingerprint-pin, and troubleshooting procedure is in
[`docs/PRODUCTION_SETUP.md`](docs/PRODUCTION_SETUP.md). Do not enable production
from the short configuration notes alone.

## Project map

| Path | Purpose |
| --- | --- |
| `src/main/java/.../AmpAutoCertMod.java` | server lifecycle entrypoint |
| `docs/THIRD_PARTY.md` | pinned Jar-in-Jar artifacts, licenses and hashes |
| `.../AutoCertService.java` | background single-flight renewal lifecycle and dependency wiring |
| `.../RenewalCoordinator.java` | recovery, due decision, production gate and pipeline orchestration |
| `.../DohDnsTxtResolver.java` | bounded two-provider public DNS-over-HTTPS TXT consensus lookup |
| `.../RenewalPolicy.java` | testable expiry decision |
| `.../CertificateInspector.java` | reads certificate expiry and DNS SAN metadata from PEM |
| `.../CertificatePaths.java` | AutoModpack credential file paths |
| `.../CertificateDeploymentService.java` | validated crash-safe two-file replacement |
| `.../CertificateActivationService.java` | exact-fingerprint activation and rollback orchestration |
| `.../AutoModpackHostControl.java` | version-pinned server-thread restart/readiness adapter |
| `.../CloudflareCredentialSource.java` | env/private-file token lookup |
| `src/test/` | initial JUnit tests |
| `CODEX_TASK.md` | full **English** implementation brief and acceptance criteria |
| `AGENTS.md` | root project policies for Codex |
| `.codex/config.toml` | <= 2 subagents; cheap/fast model defaults (edit for availability) |
| `.codex/agents/` | read-only fast scanner, log triage, focused security reviewer |
| `.agents/skills/` | NeoForge, ACME/Cloudflare, TLS deployment, security, token-budget skills |
| `docs/IMPLEMENTATION_LOG.md` | compact milestone handoff |

## Important limits of this delivered archive

The current workspace has deterministic coverage for the connected coordinator, including persisted issuance cooldown and two-phase crash recovery that retains its rollback marker until AutoModpack reloads the restored fingerprint. No real ACME order, Cloudflare mutation, or AutoModpack host restart was performed by those tests. A dedicated Java 21 server integration run and an opt-in issuance against a separate test zone remain required before production enablement. No user API keys, real certificates or pre-issued ACME accounts are embedded.

References: [NeoForge 1.21.1](https://docs.neoforged.net/docs/1.21.1/gettingstarted/) · [AutoModpack security](https://moddedmc.wiki/en/project/automodpack/latest/docs/security) · [acme4j](https://github.com/shred/acme4j) · [Codex subagents](https://developers.openai.com/codex/subagents) · [Codex skills](https://developers.openai.com/codex/skills).
