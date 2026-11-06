# Production setup, renewal, and recovery

[Русское руководство](PRODUCTION_SETUP_RU.md) · [Project overview](../README.md)

This guide describes `0.2.1`; see the [changelog](../CHANGELOG.md) for compatibility and limitations.

Version `0.2.1` accepts inspected AutoModpack 5.0.0-rc.1 through rc.7. The operator has reported a successful production renewal and host activation with rc.7; other release candidates have source-level compatibility checks. Later AutoModpack releases require separate review.

This is an operator runbook for the **server-only** amp-autocert mod. It obtains and renews publicly trusted, CA-signed TLS certificates for AutoModpack using Let's Encrypt ACME DNS-01 and Cloudflare DNS. The intended host is a managed Minecraft server, often Pterodactyl, where the operator cannot install Certbot, cron, systemd timers, or other OS-level ACME tools and cannot enable DNSSEC for AutoModpack's AMP1 trust path. DNSSEC is **not required** for DNS-01 issuance. Cloudflare **must** be authoritative for the zone because the mod edits its DNS records through the Cloudflare API. A Cloudflare Origin CA certificate is not publicly trusted by direct Minecraft clients.

The mod uses AutoModpack's built-in TLS termination. It does not provision an external reverse proxy, tunnel, Cloudflare proxy, or a raw-IP certificate. An earlier reference deployment completed production issuance and activation on AutoModpack 5.0.0-rc.2 on 2026-09-29; your server still needs its own checks.

## 1. Requirements and address plan

| Requirement | What to verify |
| --- | --- |
| Server | Minecraft Java 1.21.1, NeoForge 21.1.248, Java 21, dedicated server. |
| AutoModpack | `automodpack_mod` version **5.0.0-rc.1 through 5.0.0-rc.7** in a build containing this update. Missing/other versions are rejected; later releases need a separate compatibility review. |
| DNS zone | Active Cloudflare zone; registrar NS delegation must equal that zone's assigned Cloudflare nameservers. A zone merely present in the dashboard is insufficient. |
| Credentials | Zone-scoped API token with `Zone / DNS / Edit`; server must read it and reach Cloudflare, Let's Encrypt, and public DNS-over-HTTPS resolvers over outbound HTTPS. |
| AutoModpack host | `modpack-host: true`, `disable-internal-tls: false`, supported `connection-mode`, readable effective/on-disk configuration, and safe writable credential targets. |
| Names | Player join hostname (`origin.hostname`) and AutoModpack advertised endpoint hostname must be the zone apex or **exactly one label** below it. No IP literal, unrelated zone, or multi-label subdomain. |
| Ports | Minecraft port and AutoModpack effective listener/advertised TCP port must be allocated, reachable, and correctly routed by the hosting panel. The certificate covers DNS names, **not ports**. |

Example: players enter `play.example.com`; the Minecraft server runs at `node.example.com:25598` (an optional `_minecraft._tcp.play.example.com` SRV record may point there); AutoModpack serves downloads at `packs.example.com:25809`. Configure public A/AAAA records to reach the actual hosting address. Keep direct game/download records in Cloudflare **DNS only** mode; the standard orange-cloud HTTP proxy does not forward arbitrary Minecraft/AutoModpack TCP ports. Do not publish an unusable AAAA record. On Pterodactyl, assign and expose both the Minecraft port and the actual AutoModpack listener port; an advertised port alone does not create a listener or panel allocation.

The mod requests exactly `example.com` and `*.example.com`, and writes temporary DNS-01 TXT values at `_acme-challenge.example.com`. The wildcard covers `play.example.com` and `packs.example.com` but not `a.b.example.com`; the apex SAN covers `example.com`. An SRV target and its port do not become certificate identifiers. Let's Encrypt DNS-01 may require **two simultaneous TXT values** for apex and wildcard; do not replace one with the other or create a permanent challenge value.

AutoModpack owns its endpoint settings in `automodpack/server.conf`. `advertised-endpoint-host` names the public download host; when empty, this mod uses `origin.hostname`. `advertised-endpoint-port` names the public download port; when `-1`, it uses Minecraft's connected/server port. `connection-mode: HOLEPUNCH` uses the Minecraft port, `MAGIC` uses the Minecraft port or a configured `bind-port`, and `HTTP` requires a dedicated `bind-port`. The configured bind port and advertised public port can differ only if the hosting network forwards them correctly; verify client connectivity yourself. The mod reads both loaded and on-disk values and blocks if relevant values differ. See [AutoModpack's server configuration](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config).

## 2. Prepare Cloudflare and the token

1. Add the actual apex zone (for example `example.com`) to Cloudflare. Copy its **Zone ID** from the zone overview; this is a 32-character ID, not an account ID or API token. At the domain registrar, set the zone's assigned nameservers. Wait until Cloudflare reports **Active** and public NS delegation agrees. A nameserver suffix alone is not proof: the mod compares the actual assigned set with independent public DNS responses.
2. In Cloudflare, create a **custom API token** (Profile → API Tokens → Create Token). Grant `Zone / DNS / Edit` to **only this zone**; avoid the global API key and account-wide scope. Optionally restrict source IP only if the Minecraft server has a stable outbound IP. Copy the token when created; Cloudflare shows it only once. The mod verifies token activity and zone access during preflight; the staging DNS-01 operation proves it can actually write and remove TXT records.
3. Supply the token through exactly one source: preferred container environment variable `AMP_AUTOCERT_CF_API_TOKEN`, or a UTF-8 text file at `<server-root>/automodpack/credentials/amp-autocert-cloudflare-token.txt` containing **only the token text** (a trailing newline is fine). The environment variable takes precedence if both exist. Use the panel's private environment/secret facility, never an exposed startup command or public configuration field.
4. On POSIX hosts, keep the token file readable only by the server process (for example mode `0600`), and keep credential directories unwritable by other users. Symlinks are refused. If the panel cannot provide a private environment variable or control read permissions, use `credentials.allowInsecureTokenFilePermissions=true` only after limiting panel, SFTP, backup, and shell access to trusted administrators. This compatibility mode may permit panel-managed read bits such as `0644`; it still rejects group/other-writable paths and symlinks. Leave it `false` elsewhere.

Never paste a token into `amp-autocert.properties`, `server.conf`, a mod JAR, Git, logs, or a support request. The account private key and certificate private key are generated and managed by the mod; do not create or paste them manually. [Cloudflare token creation](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/), [token verification API](https://developers.cloudflare.com/api/resources/user/subresources/tokens/methods/verify/), and [zone details API](https://developers.cloudflare.com/api/resources/zones/methods/get/) document these checks.

## 3. Install and protect server files

Install the supported AutoModpack JAR and amp-autocert JAR in the dedicated server's `mods/` directory. If building from source, use JDK 21: `./gradlew test` and `./gradlew build` (Windows: `gradlew.bat`); find the mod in `build/libs/`. Restart the server after changing the amp-autocert properties: the mod reads them during startup. AutoModpack configuration edits require its supported reload/restart procedure; the mod blocks when loaded and on-disk identity differs.

For **every** AutoModpack modpack group, review `from-server` and add these exclusions to the group's `exclude` list in `automodpack/server.conf`:

```text
automodpack/credentials/**
automodpack/amp-autocert.properties
automodpack/amp-autocert-state/**
```

Keep `automodpack/credentials/`, `automodpack/amp-autocert-state/`, and backups outside all `host-modpack` directories and any broad client file-selection rules. Credentials can contain the Cloudflare token file, ACME account state, issued private key, active certificate, deployment journal, and recovery backups. `amp-autocert-state/enrollment.properties` contains the non-secret accepted baseline and phase; keep it server-side because it controls the issuance gate. Check every group rather than only the default group. AutoModpack normally distributes its group directory plus explicit `from-server` paths; the exclusions add defense against future rule changes.

On a **stopped** server, make a private backup of the **entire** `automodpack/credentials/` directory and `automodpack/amp-autocert-state/` before enabling production or migrating an existing deployment. Store it outside client sync and web-accessible locations. Keep `certificate.crt` and `private-key.pem` together: supported AutoModpack builds read `automodpack/credentials/certificate.crt` (leaf-first PEM full chain) and `automodpack/credentials/private-key.pem` (unencrypted PKCS#8). Do not manually overwrite one file. ACME account state lives under `automodpack/credentials/amp-autocert/`; the non-secret enrollment baseline lives under `automodpack/amp-autocert-state/`. Do not edit or delete either to bypass a block.

The mod first tries to restrict ACME state files to owner-only `0600` on POSIX or an owner-only ACL. No shell command is needed. With `credentials.allowInsecureDeploymentFilePermissions=false`, a failed restriction blocks issuance and preserves the deployed pair. With that setting `true`, the mod warns and continues if the filesystem leaves ACME state or deployed credentials readable by other users. Group/other write access to ACME state or credential paths and symbolic links still block operation. The ACME account key, pending DNS challenge state and deployment private key may be exposed to other panel/container users in compatibility mode; restrict panel users, SFTP and backups accordingly. This switch does not affect Cloudflare token-file checks; use its separate setting only if needed.

## 4. Fill in `amp-autocert.properties`

Copy `config/amp-autocert.properties.example` into `<server-root>/automodpack/amp-autocert.properties`. This is an **operational, non-secret** properties file; place it in the server root's `automodpack/` directory, not in `config/` or your local IDE folder. Replace the four identity/provider placeholders with your own values:

```properties
enabled=false
debugmode=false
acme.environment=staging
acme.email=admin@example.com
origin.hostname=play.example.com
cloudflare.zoneName=example.com
cloudflare.zoneId=0123456789abcdef0123456789abcdef

acme.networkTimeoutSeconds=30
dns.propagationTimeoutSeconds=120
dns.propagationInitialDelaySeconds=15
cloudflare.ttlSeconds=60
renew.beforeDays=30
check.intervalHours=24
drift.pollSeconds=60
renew.failureCooldownHours=48
retry.initialMinutes=15
retry.maxHours=12
retry.jitterPercent=20
host.restartAfterRenew=true
host.activationTimeoutSeconds=60
host.readinessPollSeconds=1
credentials.allowInsecureTokenFilePermissions=false
credentials.allowInsecureDeploymentFilePermissions=false
```

| Key | Where the value comes from / meaning |
| --- | --- |
| `acme.email` | Your reachable address for Let's Encrypt account notices; not the Cloudflare account password. |
| `origin.hostname` | Exact DNS hostname players type into Minecraft, including the name with an SRV record if used. It cannot reliably be discovered from AutoModpack. |
| `cloudflare.zoneName` | Apex of the authoritative Cloudflare zone, without a scheme, wildcard, trailing dot, or port. |
| `cloudflare.zoneId` | Zone ID from that same zone's Cloudflare overview; not its nameservers or token. |
| `enabled`, `acme.environment` | Start disabled. Use `production` only when the real zone/token are ready and the staging validation procedure below is planned. The configured environment selects **ordinary** issuance; reconciliation always tests Let's Encrypt staging. |
| `debugmode` | `false` for normal logs; `true` for additional bounded diagnostic phases without raw credential/provider payloads. Restart after changing it. |
| `acme.networkTimeoutSeconds`, `dns.propagationTimeoutSeconds`, `dns.propagationInitialDelaySeconds`, `cloudflare.ttlSeconds` | Bounded request/propagation timings and TXT TTL. Defaults: 30 s, 120 s, 15 s, 60 s. Adjust only for a diagnosed network/DNS issue. |
| `renew.beforeDays`, `check.intervalHours`, `drift.pollSeconds` | Renew inside the 30-day window; scheduled check every 24 h; local relevant-config poll every 60 s by default. The local drift poll makes no network request. |
| `renew.failureCooldownHours`, `retry.initialMinutes`, `retry.maxHours`, `retry.jitterPercent` | Persisted issuance cooldown (48 h) and bounded retry (15 min, max 12 h, 20% jitter). Repeated commands/restarts do not bypass the cooldown. |
| `host.restartAfterRenew`, `host.activationTimeoutSeconds`, `host.readinessPollSeconds` | Automatic host activation must be enabled for production. Defaults: true, 60 s, 1 s; success requires the running host to report the exact new certificate fingerprint. |
| `credentials.allowInsecureTokenFilePermissions` | Allows a panel-created Cloudflare token file to be readable by other users when private permissions cannot be set. Does not allow group/other write access or symlinks. Prefer a private panel environment variable. |
| `credentials.allowInsecureDeploymentFilePermissions` | Defaults to `false`. When `true`, deployment does not change file permissions and ACME state attempts to restrict permissions but may continue with read-only access for other users. Emits a warning; group/other write access and symlinks still block operation. Use only if the panel cannot enforce private file modes. |

Do **not** add `acme.domains`, `dns.challengeRecord`, `minecraft.srvTarget`, `automodpack.endpoint`, ACME directory URLs, Cloudflare API root, or DoH resolver URLs to a new file. Domains/challenge are derived; network/TLS identity is read from AutoModpack; provider endpoints are pinned in code. The old `acme.allowStagingIssuance` flag is deprecated and has no gating effect in this version.

## 5. Verify, validate staging, and issue production

1. With `enabled=false`, start the server and run `/ampautocert doctor` (operator permission level 3). It checks the supported AutoModpack version, effective **and** on-disk host settings, origin/endpoint coverage, credential targets, Cloudflare token activity, exact zone ID/name/active state, assigned nameservers, public NS agreement, and existing certificate metadata. It can read over the network, but creates no ACME order, TXT record, or deployed credential. On a fresh install, `no accepted configuration baseline exists` is expected. Doctor cannot prove DNS **write** rights or that a client can reach the advertised endpoint.
2. Back up credentials, confirm panel ports/DNS, then set `enabled=true`, `acme.environment=production`, `host.restartAfterRenew=true` and restart. A scheduled production check starts shortly after startup, but a **missing baseline blocks it**. Keep the current valid certificate in place during an upgrade. Do not assume a previous `/ampautocert staging-issue` alone creates a baseline.
3. Run `/ampautocert reconcile` to read the explanation, then `/ampautocert reconcile confirm`. This performs full preflight, places temporary TXT records, obtains a real Let's Encrypt **staging** certificate for the actual configured apex/wildcard, checks it in memory, deletes only its own Cloudflare records, and accepts the discovered configuration baseline if everything succeeds. It can be run while `acme.environment=production`; the staging attempt has a separate persisted cooldown. It never deploys a staging certificate, restarts AutoModpack, or places a production order. If it fails, read its report, correct the cause, respect cooldown, and rerun after changes; the old certificate stays in place.
4. After a successful reconciliation, run `/ampautocert status` and `/ampautocert check`. The ordinary check issues a production certificate only if none is deployed or the current one's **expiry date** is inside the configured renewal window. It does not use the current certificate's CA trust to decide whether renewal is due: a long-lived existing **self-signed** certificate may yield `not_due`. If you are deliberately replacing that certificate with public CA trust, run `/ampautocert renew` after successful reconciliation. Likewise, a valid old public certificate beyond the window should not rotate merely because you upgraded. `renew` forces the **expiry decision**, but never bypasses the accepted baseline, AutoModpack checks, cooldown, new certificate/key/chain validation, or rollback. Production may also be attempted by the scheduled check when due; do not treat reconciliation as a hold on subsequent automatic work.
5. Confirm expected DNS SANs, expiry, status, AutoModpack host readiness, actual downloads and a clean Minecraft client join. The certificate covers the DNS names; also verify the panel's exposed ports and the public route. A success from unit tests or `doctor` does not prove a real player can connect.

`/ampautocert staging-issue` is an **optional separate** probe when `enabled=true`, the configured environment is `staging`, **and an accepted baseline already exists**. Normal scheduled staging mode does not issue or deploy. There is no `acme.allowStagingIssuance=true` requirement. First enrollment and reconciliation use `/ampautocert reconcile confirm` instead. Let's Encrypt staging certificates are not publicly trusted and are never installed as AutoModpack's active credentials. See [Let's Encrypt staging](https://letsencrypt.org/docs/staging-environment/) and [DNS-01 challenge documentation](https://letsencrypt.org/docs/challenge-types/).

### Command reference

All commands require operator permission level 3. `/ampautocert` and `/ampautocert help` print the short list. Long-running work is queued on the background worker; read the completion message and then check `status`.

| Command | Preconditions and effect |
| --- | --- |
| `/ampautocert help` | Lists the commands and their short descriptions; no network or certificate changes. |
| `/ampautocert status` | Shows cached lifecycle state, environment, deployed expiry/SANs, last outcome and cooldown/drift information; does not run a fresh preflight. |
| `/ampautocert doctor` | Runs bounded local, Cloudflare and public DNS reads; never issues or deploys a certificate or writes DNS. |
| `/ampautocert reconcile` | Explains the confirmation step; makes no changes. |
| `/ampautocert reconcile confirm` | Requires an initialized enabled lifecycle. Runs preflight and real staging DNS-01 issuance, cleans owned TXT records and persists the accepted configuration baseline. It never deploys staging material or creates a production order. |
| `/ampautocert check` | Requires a running lifecycle. Runs the normal due-date decision; production issuance can start when due, subject to all gates and cooldown. |
| `/ampautocert renew` | Requires a running lifecycle and `acme.environment=production`. Requests early production issuance, subject to baseline, preflight, cooldown, validation and activation checks. This can replace the active certificate. |
| `/ampautocert staging-issue` | Requires `enabled=true`, `acme.environment=staging` and an accepted baseline. Runs a separate staging exercise, discards the certificate and never deploys it. |

## 6. Renewal, migration, drift, and recovery

Normal production renewal checks on the configured schedule and does **not** repeat staging for an unchanged accepted baseline. Production attempts reserve a persisted cooldown before an ACME order. The mod validates the full chain, exact apex/wildcard SANs, coverage of origin and endpoint, validity, server usage, system trust, and matching PKCS#8 private key before touching live files. It stages both files, retains an old pair for rollback, restarts AutoModpack, and requires it to report the exact installed leaf fingerprint. Failures preserve or restore the previous pair where possible; crash recovery runs before the next certificate decision.

On an upgrade from v0.1.0, `minecraft.hostname` and `dns.zoneName` are accepted as temporary aliases for `origin.hostname` and `cloudflare.zoneName` if not conflicting. Matching old derived/provider values may load with deprecation warnings; replace them with the new minimal file. Remove old `minecraft.srvTarget` and `automodpack.endpoint` after confirming AutoModpack's actual settings. **Current limitation:** those legacy endpoint strings are parsed but not compared with the discovered AutoModpack endpoint; do this comparison manually during migration. An existing valid production certificate survives missing baseline; `/ampautocert reconcile confirm` is still required before future automated issuance. The mod does not rewrite the old properties file for you.

Changes to origin, zone, AutoModpack endpoint host/port, mode/bind port, or TLS ownership are checked locally and before issuance/deployment. A relevant change, loaded/on-disk mismatch, unreadable settings, or persisted drift block stops automatic issuance/deployment/host restart. Changing unrelated modpack group content does not affect the baseline. Reverting the file alone does not clear a persisted drift block. Run `doctor`, resolve the cause, then explicitly accept the new identity through successful `reconcile confirm`; do not edit private enrollment state.

For manual emergency restore: set `enabled=false`, stop Minecraft, retain the failed/current credentials and enrollment-state directories privately for diagnosis, and restore the **complete** pre-change backups together (matching certificate **and** key, account and accepted-baseline state). Start the server, verify AutoModpack serves the restored fingerprint, and leave renewal disabled until the root cause is resolved. Never delete DNS records unless you know their exact record ID and ownership. A client's saved manual fingerprint pin overrides CA trust; ask that player to remove the old pin once after independently validating the server, using AutoModpack's documented client UI.

## 7. Troubleshooting

| Symptom | Check / action |
| --- | --- |
| Mod missing, no commands | Verify Java/NeoForge version, both server-side JARs, and supported AutoModpack rc.1–rc.7 dependency. |
| Config load fails | Confirm required email, origin, zone name/ID and booleans; remove conflicting legacy aliases or derived values; restart after edits. |
| `doctor` says baseline missing | Expected before enrollment; inspect all other checks, then enable production and run `reconcile confirm` when ready. Ordinary production is blocked until success. |
| AutoModpack unsupported or on-disk/loaded mismatch | Check version, `server.conf`, reload/restart, `modpack-host`, connection mode, bind/advertised ports, and internal TLS. Do not bypass the mismatch. |
| Origin/endpoint not covered | Both hostnames must be apex or one-label names under `cloudflare.zoneName`; change routing intentionally and reconcile. IPs/deep/unrelated names are unsupported. |
| Token missing/inactive or zone lookup fails | Check private env/file source, token status, zone-scoped DNS Edit, zone ID/name and panel file permissions. Never print the token to test it. |
| Public NS mismatch or zone not active | Fix registrar NS to match the zone's assigned Cloudflare NS and wait for public delegation; `doctor` must see agreement. |
| Staging failed or DNS propagation timed out | Check token DNS write access, `_acme-challenge` CNAME/record conflicts, assigned NS, outbound HTTPS, resolver reachability and cooldown. Do not install staging material or manually add permanent TXT values. |
| Staging command rejected | `staging-issue` needs `enabled=true`, `acme.environment=staging`, and an accepted baseline; first enrollment uses `reconcile confirm`. |
| `not_due`, renewal expected | Check expiry versus `renew.beforeDays`; use `renew` only for deliberate early production rotation. |
| Existing self-signed certificate remains active | Ordinary checks use its expiry date and can report `not_due`; after successful staging reconciliation, intentionally run `renew` to move to a publicly trusted certificate. |
| Production cooldown active | Wait for the reported next attempt; restart/commands do not erase it. |
| Activation or readiness failed | Check internal TLS, actual panel listener/advertised port, AutoModpack startup and the deployed fingerprint. Keep old credentials/backup; do not replace only one file. |
| Existing client reports fingerprint mismatch | Verify the real server and public certificate, then have that client remove its old manually saved pin. |

Use `/ampautocert status` for cached last outcome, expiry/SANs, cooldown and safe drift fields; use `/ampautocert doctor` for current bounded preflight. `debugmode=true` adds diagnostic phases after restart, but support bundles should still be reviewed for server topology. Never share token values, account/private keys, private credential directories, raw provider responses, or unredacted panel environment settings.

Sources: [AutoModpack configuration](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config), [AutoModpack security and pins](https://moddedmc.wiki/en/project/automodpack/latest/docs/security), [Cloudflare token creation](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/), [Cloudflare zone details](https://developers.cloudflare.com/api/resources/zones/methods/get/), [Let's Encrypt DNS-01](https://letsencrypt.org/docs/challenge-types/), [Let's Encrypt staging](https://letsencrypt.org/docs/staging-environment/).
