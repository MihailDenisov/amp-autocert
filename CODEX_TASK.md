# Codex implementation task: amp-autocert

**Language: English.** Implement the feature described below in this repository. This is a ready-to-open VS Code / NeoForge **starter**, not a completed auto-renewal mod. Start by reading `AGENTS.md`, this file, and only the skill(s) relevant to your current milestone. Keep dependencies and patches minimal. Keep the primary agent on the model selected by the user; default narrow subagents are fast and economical as configured in `.codex/config.toml`.

## 0. Target environment and network topology (fixed requirements)

| Item | Value |
| --- | --- |
| Project / distributable | `amp-autocert` |
| NeoForge mod ID | `amp_autocert` (a hyphen cannot be used in a mod ID) |
| Language | Java 21 |
| Minecraft | 1.21.1 |
| NeoForge | **21.1.248**, do not upgrade without approval |
| AutoModpack | **5.0.0-rc.1**, verified from operator-provided source ZIP and runtime JAR |
| Runtime | Dedicated Minecraft server, no OS-level cron, sudo or external Certbot |
| DNS provider | Cloudflare (registrar REG.RU, DNS in Cloudflare) |
| Zone | `maicraft.tech` |
| Server hostname / typed origin | `play.maicraft.tech` |
| Existing A record | `hm.maicraft.tech` -> public Minecraft host IP, DNS only |
| Existing Minecraft SRV | `_minecraft._tcp.play.maicraft.tech` -> `hm.maicraft.tech:25598` |
| AutoModpack advertised host | `hm.maicraft.tech` |
| AutoModpack TCP port | `25809` |
| Requested certificate SANs | `maicraft.tech` and `*.maicraft.tech` |
| DNSSEC | Not enabled and **not required** |
| Certificate issuer | Publicly trusted Let's Encrypt, ACME v2 DNS-01 |

The mod runs *only* server-side. Clients do NOT install it. Both `play.maicraft.tech` (entered by the player) and `hm.maicraft.tech` (advertised endpoint) are covered by the requested wildcard certificate. Cloudflare Origin CA certificates are NOT publicly trusted by Java clients connecting directly to an origin: do not use Origin CA. Do not create SRV records for the modpack endpoint.

AutoModpack `5.0.0-rc.1` source and runtime bytecode define `automodpack/credentials/certificate.crt` (PEM fullchain) and `automodpack/credentials/private-key.pem` (PKCS#8 PEM private key). Do not substitute `certificate.cert` or rely on old `.private/cert.crt` instructions. Re-verify these constants before upgrading AutoModpack.

Source references:
- https://moddedmc.wiki/en/project/automodpack/latest/docs/security
- https://moddedmc.wiki/en/project/automodpack/latest/docs/quick-start
- https://moddedmc.wiki/en/project/automodpack/latest/docs/hosting
- https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- https://github.com/shred/acme4j
- https://developers.cloudflare.com/api/resources/dns/subresources/records/

## 1. Deliverable

A reproducible production-quality Java 21 NeoForge 21.1.248 server mod which, while the game server is running, securely obtains and automatically renews Let's Encrypt certificates for `maicraft.tech` and `*.maicraft.tech` using Cloudflare DNS-01, installs them into the active AutoModpack credential path and activates new credentials without a full server shutdown whenever supported by the installed AutoModpack version.

Add unit tests, deterministic fake ACME/Cloudflare integration tests, error handling, a deploy/rollback flow, operator-facing configuration, status commands and concise documentation. Never require Cloudflare credentials during CI or unit tests.

## 2. Mandatory implementation milestones

### Milestone A: inspect & prove the build
1. Confirm the ModDevGradle plugin and NeoForge artifact are compatible with Gradle 8.14.3, Java 21, Minecraft 1.21.1 and `21.1.248`. Keep the project pinned.
2. Bootstrap the official Gradle wrapper via the supplied scripts if wrapper JAR is absent; validate the downloaded binary against the published official SHA-256. Optionally commit a verified official wrapper binary after provenance review.
3. Fix any incompatibilities in server-only `@Mod` bootstrap, NeoForge lifecycle handlers, Gradle metadata and tests.
4. Run `./gradlew test build` in a network-enabled environment. Document dependency hashes/versions as appropriate.

### Milestone B: configuration and secrets
1. Extend `AutoCertConfig` without putting secrets in public `automodpack/amp-autocert.properties`. Keep `enabled=false` and `acme.environment=staging` as defaults.
2. Use env `AMP_AUTOCERT_CF_API_TOKEN` OR `automodpack/credentials/amp-autocert-cloudflare-token.txt` with strict file access controls; never echo token values, even on HTTP errors.
3. Limit DNS writes to configured Cloudflare zone ID and only `_acme-challenge.maicraft.tech`. Validate domains against explicit allowlist, including wildcard/apex pairing and original Minecraft hostname. Reject arbitrary client-specified domains.
4. Persist ACME account identity and issuance state under `automodpack/credentials/amp-autocert/state` or another clearly private directory; make state survive server restarts. Avoid symlinks and accidental inclusion in AutoModpack packs.
5. Validate the actual AutoModpack pack exclusion rules in the user's installed release. Ensure `automodpack/credentials/**`, all private ACME files, and `automodpack/amp-autocert.properties` are NOT shipped to clients. The entire private credentials directory must never be packaged in the distributable JAR.

### Milestone C: ACME DNS-01 issuer
1. Integrate current compatible acme4j (verify version/API and any required provider/transport dependencies). Use the supported NeoForge Jar-in-Jar packaging so the built mod runs with dependencies on a *fresh* server, not only Gradle `runServer`.
2. Implement account creation/loading and the ACME v2 order/authorization/challenge/finalize workflow using Let's Encrypt staging and production directory URLs selected from configuration.
3. Use Cloudflare v4 API to create **and remove** only TXT records generated by this order; retain Cloudflare record IDs. Wildcard + apex may require two simultaneous TXT records with the same name and different values. Do not delete the other challenge's TXT or existing operator records.
4. Wait for DNS propagation with bounded exponential backoff; poll ACME challenge/order state with timeouts and Retry-After, obey CA rate limits. Ensure an interrupted order can recover safely, or fail cleanly without orphaned records.
5. Generate private keys using a secure provider and key size/algorithm accepted by Let's Encrypt and the installed AutoModpack JVM. PEM private key **must be PKCS#8** per the checked AutoModpack version. Verify CA-produced chain, SANs, certificate validity and private-key match before staging installation.
6. Add injectable abstractions for ACME, Cloudflare HTTP, DNS lookups, clock and filesystem so tests run with zero external requests.

### Milestone D: deployment and AutoModpack activation
1. Verify the new chain using normal Java PKIX trust roots. Verify hostname coverage for both `play.maicraft.tech` and `hm.maicraft.tech`, and that the key matches the leaf certificate. Public CA chain validation must not be bypassed.
2. Keep old known-good files until validation and I/O complete. Stage new files in the destination filesystem, set restrictive permissions, use safe replacement and deterministic backup/rollback if either file replacement or host restart fails. Avoid a half-updated certificate/key pair.
3. Preserve a valid existing production certificate on any DNS/ACME/IO/restart error. Never replace it with a staging cert. The starter must remain non-destructive until the final deployment flow is reviewed.
4. Confirm the installed AutoModpack host restart command/API. If supported, schedule `/automodpack host restart` or its verified API equivalent on the **Minecraft server thread**, not a scheduled IO worker thread. Observe readiness and restore previous files plus restart host on failure. Do not silently fall back to shutting down Minecraft.
5. Avoid concurrent renewals. Keep long-running ACME/Cloudflare calls on a background scheduler; start/stop cleanly with NeoForge server lifecycle. Handle server stop while a transaction is in progress.

### Milestone E: operators, observability and tests
1. Add permission-restricted server-side `/ampautocert status`, `/ampautocert check`, and `/ampautocert renew` (optional `--force` guarded by operator permission). Never display token, account key, raw Cloudflare responses or private key.
2. Check at startup and every configured interval (default 24h), renew when expiration is within 30 days, apply bounded exponential retry with jitter when failures occur, and prevent Let's Encrypt rate-limit storms. Never run an unchecked hourly creation loop.
3. Report which issuer/environment is active, expiry, days left, last check/outcome, and currently deployed SANs; redact secrets. Declare AutoModpack `5.0.0-rc.1` implementation mod ID `automodpack_mod` as an exact required server-side NeoForge dependency, so a missing or incompatible AutoModpack installation is rejected during mod dependency validation, and cover that failure in the dedicated-server integration test.
4. Tests must cover missing/valid/due/expired cert, DST/time boundaries, bad SAN, mismatched private key, invalid chain, staging-production separation, concurrent wildcard/apex TXT records, Cloudflare HTTP failure, partial rollback, idempotent DNS cleanup, restart on the server thread, missing permissions and no-secret logging.
5. Verify Gradle `test`, `build`, dedicated `runServer`, jar-in-jar dependency resolution and actual test connection to a separately configured AutoModpack test server. Do not use the real domain or real API token in integration tests.
6. Write a production setup guide with Cloudflare API token permissions, DNS-only origin routing, config exclusions, staging-to-production process, backup/restore, certificate rotation behavior and troubleshooting. Warn that *existing* player fingerprint pins override CA trust until the player removes the old pin.

## 3. Definition of done

- Builds and loads on **NeoForge 21.1.248 + Minecraft 1.21.1 + Java 21** dedicated server with no client dependency.
- With dummy provider/ACME fixture, complete DNS-01 -> install -> activate -> rollback tests are deterministic and pass.
- On an opt-in staging test with real DNS (separate test zone/domain; never the production token in CI), can issue a certificate, then demonstrate safe production issuance after explicit operator enablement.
- Certificate full chain and PKCS#8 private key stored ONLY in `automodpack/credentials/` and are never shipped to players.
- Cloudflare DNS records are cleaned up correctly; production certificates are automatically renewed before expiry without external cron/Certbot.
- On failure, game server stays up and last good certificate continues to serve players where possible. Expose an actionable error and retry safely.
- No manual fingerprint prompt for *new* AutoModpack clients when public CA certificate covers the typed Minecraft address and endpoint, subject to the installed AutoModpack version's trust rules. Existing pinned clients may need one manual unpin.
- Build/test evidence and any external limitations documented honestly in `docs/IMPLEMENTATION_LOG.md`.

## 4. Recommended first Codex prompt

> Implement `CODEX_TASK.md` milestone by milestone in this repository. Start with Milestone A and the minimum supporting tests, then continue through E while you have working context. Follow `AGENTS.md`; keep the root agent on my selected model and use fast cheap subagents only for narrow independent exploration or test triage. Use the repo skills when relevant. Treat any uncertain NeoForge / AutoModpack / acme4j API as unverified until inspected. Do not request a real Cloudflare token. Keep staging and auto-renewal disabled until deployment and rollback tests pass. Finish each milestone with concise test results and update `docs/IMPLEMENTATION_LOG.md`.

## 5. Cost and safety stop rules

- Before a broad investigation, ask whether one targeted file, one exact API reference, or one short external doc will answer it.
- A cheap read-only agent must not issue certs, edit private-key code, receive real secrets, or make production Cloudflare API calls.
- Escalate the security review just once after the security-relevant diff is ready; do not delegate the same review in parallel.
- If the installed AutoModpack API cannot be verified, implement a truthful manual-activation fallback with a status message. Do not fabricate a compatible `host restart` call.
