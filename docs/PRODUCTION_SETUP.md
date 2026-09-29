# Production setup and recovery

Русская версия этого руководства: [`PRODUCTION_SETUP_RU.md`](PRODUCTION_SETUP_RU.md).

This runbook applies only to Minecraft 1.21.1, NeoForge 21.1.248, and inspected
AutoModpack versions in `[5.0.0-rc.1,5.0.0-rc.2]`. The reference deployment
completed live Let's Encrypt production issuance, Cloudflare DNS-01 cleanup,
credential rotation, AutoModpack 5.0.0-rc.2 activation, restart persistence, and
client trust verification on 2026-09-29. Every new installation must still use
its own controlled rollout; production mode is never enabled by default.

## 1. DNS and network prerequisites

- `play.maicraft.tech` is the address players type.
- `_minecraft._tcp.play.maicraft.tech` points to `hm.maicraft.tech:25598`.
- `hm.maicraft.tech` resolves directly to the Minecraft server's public address.
- AutoModpack advertises `hm.maicraft.tech:25809`.
- The certificate contains `maicraft.tech` and `*.maicraft.tech`; the wildcard
  covers both `play.maicraft.tech` and `hm.maicraft.tech`.

Keep the `hm.maicraft.tech` A/AAAA records **DNS only** (grey cloud). Cloudflare's
HTTP proxy is not a transparent proxy for the Minecraft port or this
AutoModpack TCP endpoint. Remove an obsolete AAAA record if the server is not
actually reachable over IPv6. DNSSEC is not required for DNS-01.

## 2. Create a restricted Cloudflare token

Create a dedicated custom API token in Cloudflare with:

- permission: `Zone / DNS / Edit`;
- zone resource: include only the specific `maicraft.tech` zone;
- no account-wide or unrelated-zone access.

An optional client-IP restriction is useful only when the game server has a
stable outbound public IP. The mod already has the zone ID in its public config,
so it does not require a global API key or broad zone-list permission.

Supply the token by exactly one of these methods:

1. Preferred: set the container environment variable
   `AMP_AUTOCERT_CF_API_TOKEN`.
2. Otherwise, put only the token text in
   `automodpack/credentials/amp-autocert-cloudflare-token.txt`.

Never put the token in `amp-autocert.properties`, `server.conf`, a startup
command, a mod JAR, Git, or a support log. On normal Linux hosting the token file
must be private to the server process. If Pterodactyl prevents both environment
variables and permission changes, the explicit fallback is documented in
section 4.

## 3. Exclude all private material from AutoModpack

For every group in `automodpack/server.conf`, add both of these paths to that
group's `exclude` list:

```text
automodpack/credentials/**
automodpack/amp-autocert.properties
```

Review every `from-server` rule at the same time. A broad rule must not make the
credential directory, public config, token, ACME account state, transaction
journals, backups, certificate, or private key available to clients. These
exclusions are required defense in depth even though supported AutoModpack rc.1/rc.2
normally builds a group from its host-modpack directory and explicit
`from-server` entries.

Do not paste the full `server.conf` or the token into public issue reports. It is
safe to report the two exclusion patterns after removing unrelated private
paths.

## 4. Configure amp-autocert safely

Copy `config/amp-autocert.properties.example` to
`automodpack/amp-autocert.properties`. Confirm at least these values:

```properties
enabled=false
debugmode=false
acme.environment=staging
acme.allowStagingIssuance=false
acme.domains=maicraft.tech,*.maicraft.tech
dns.zoneName=maicraft.tech
dns.challengeRecord=_acme-challenge.maicraft.tech
dns.propagationInitialDelaySeconds=15
dns.dohResolvers=https://cloudflare-dns.com/dns-query,https://dns.google/resolve
cloudflare.zoneId=YOUR_ZONE_ID
minecraft.hostname=play.maicraft.tech
minecraft.srvTarget=hm.maicraft.tech:25598
automodpack.endpoint=hm.maicraft.tech:25809
host.restartAfterRenew=true
```

Leave the official ACME and Cloudflare HTTPS endpoints unchanged. The default
30-day renewal window, 24-hour check interval, 48-hour persisted attempt
cooldown, and bounded exponential retry settings are suitable starting values.
The cooldown is reserved before a production order, including an order that
later fails, to prevent rate-limit storms.

Keep `debugmode=false` for normal operation. Temporarily setting it to `true`
adds safe phase, resolver hostname/status/latency, TXT count, retry timing,
consensus and cleanup/recovery diagnostics. It never enables third-party raw
ACME/JWS/DNS payloads and never logs challenge values, API tokens, account state,
certificate bytes or private keys. Return it to `false` after troubleshooting.

The two Pterodactyl compatibility switches weaken file confidentiality and must
remain explicit:

- `credentials.allowInsecureTokenFilePermissions=true` accepts panel-managed
  read bits such as `0644` for the fixed token file. Symlinks, oversized files,
  and group/other-writable files or credential directories are still rejected.
- `credentials.allowInsecureDeploymentFilePermissions=true` prevents the mod
  from attempting chmod/ACL changes during certificate deployment. It does not
  permit group/other-writable paths.

Use these switches only when the panel gives no safer option. Restrict panel,
backup, console, and SFTP access to trusted administrators.

## 5. Back up before the first production attempt

With the Minecraft server stopped, make a private backup of the complete
`automodpack/credentials/` directory. Keep the backup outside every AutoModpack
group and outside web-accessible storage. It may contain the active private key,
ACME account keys, the Cloudflare token file, issuance state, and deployment
recovery data.

Never back up or restore only one of `certificate.crt` and `private-key.pem`.
They are a pair. Supported AutoModpack rc.1/rc.2 expects:

- `automodpack/credentials/certificate.crt`: leaf-first PEM full chain;
- `automodpack/credentials/private-key.pem`: unencrypted PKCS#8 PEM key.

## 6. Staging and production transition

First run the server on Java 21 with:

```properties
enabled=true
acme.environment=staging
```

Use `/ampautocert status` and `/ampautocert check`. Confirm the configured
environment, current expiry/SAN status, background-worker execution, and a clean
shutdown. `/ampautocert renew` must be rejected in staging. This build's staging
schedule is intentionally **inspection-only**: it does not create an ACME order.

For a separate controlled test zone, configure that zone's apex/wildcard pair,
challenge owner, hostnames, Cloudflare zone ID and a restricted test-zone token.
Then set `acme.allowStagingIssuance=true`, restart, and run exactly one
`/ampautocert staging-issue`. The command runs on the background worker, creates
and cleans only its owned TXT records, structurally inspects the returned
untrusted staging material in memory, and discards it. The environment-specific
persisted cooldown prevents repeated orders. It never writes `certificate.crt` or
`private-key.pem`, never restarts AutoModpack, and is rejected in production.
Return the switch to `false` after the exercise.

Before enabling a new production installation, complete a separate controlled
test-zone exercise using no production token or production domain. That exercise
should demonstrate DNS-01 record creation and cleanup, certificate validation,
AutoModpack activation, rollback, Java 21 shutdown, permission denial, and
NeoForge's dependency-check rejection when AutoModpack is absent, outside the
inspected rc.1/rc.2 range, or API-incompatible.
The repository has deterministic fake coverage and the reference installation
has completed one live production flow; neither substitutes for validating a
different server, provider token, network, or AutoModpack configuration.

Only after those checks pass:

1. Stop the server and refresh the private credentials backup.
2. Confirm AutoModpack is rc.1 or rc.2 and its host is configured to use
   its internal TLS files.
3. Confirm the restricted Cloudflare token is available and both exclusion
   patterns apply to every group.
4. Change `acme.environment=production`; keep `enabled=true` and
   `host.restartAfterRenew=true`.
5. Start the server once. Do not repeatedly restart or repeatedly issue
   `/ampautocert renew` after a failure.
6. Watch the server log for the final redacted outcome, then use
   `/ampautocert status`. Confirm production environment, the expected expiry,
   and deployed SANs. Verify both player and AutoModpack connections separately.

`/ampautocert renew` intentionally forces only the expiry decision. It cannot
bypass staging separation, the persisted production-attempt cooldown,
AutoModpack compatibility, certificate/key/PKIX validation, single-flight
execution, or rollback.

## 7. Rotation and rollback behavior

During renewal the mod creates both DNS-01 TXT values when the apex and wildcard
authorization overlap. It tracks their Cloudflare record IDs and removes only
records owned by that order. It validates the CA chain, exact SAN set, hostname
coverage, validity, TLS server usage, and key match before touching the live
pair.

The new key and certificate are staged on the destination filesystem. The old
pair is retained inside a private transaction until AutoModpack reports that it
is running with the exact new leaf-certificate fingerprint. A failed write,
validation, restart, or readiness check restores the old pair where possible.
Crash recovery runs before the next certificate decision.

For manual emergency recovery:

1. Set `enabled=false` and stop Minecraft.
2. Preserve the failed/current credentials directory privately for diagnosis.
3. Restore the complete pre-change `automodpack/credentials/` backup, including
   the matching certificate/key pair; do not merge individual transaction files.
4. Start Minecraft and confirm AutoModpack serves the restored fingerprint.
5. Keep renewal disabled while investigating. Do not delete DNS records unless
   their exact record IDs and ownership are known.

## 8. Existing AutoModpack fingerprint pins

A publicly trusted certificate avoids a manual fingerprint prompt for new
clients only when the certificate covers the address they use and AutoModpack's
trust rules accept it. An existing player's saved manual fingerprint pin takes
precedence over CA trust. That player may need to remove the old pin once during
the migration; certificate renewal cannot silently override it.

## 9. Troubleshooting

| Symptom | Action |
| --- | --- |
| `acme.environment must be production` | Expected guard for `/ampautocert renew`; use the separately gated staging probe only on the controlled test zone. |
| Staging issuance not allowed | Both `acme.environment=staging` and `acme.allowStagingIssuance=true` are required; restart after editing the file. |
| More diagnostics required | Temporarily set `debugmode=true` and restart. Do not publish `debug.log`; protected protocol data remains suppressed, but server topology may appear. |
| Token missing/unreadable | Set `AMP_AUTOCERT_CF_API_TOKEN` or check the fixed credential file. Never print the token to test it. |
| Token-file permissions too broad | Fix panel permissions, or explicitly enable only the documented Pterodactyl fallback and restrict panel/SFTP users. |
| NeoForge rejects a missing/incompatible `automodpack_mod` dependency | Install AutoModpack in `[5.0.0-rc.1,5.0.0-rc.2]`. New releases require source/runtime inspection before the supported range is widened. |
| AutoModpack host is not initialized | Check AutoModpack's own host configuration and startup errors; renewal remains blocked rather than guessing another API. |
| `host.restartAfterRenew=false` | Set it to `true` only after restart/readiness integration has been accepted. |
| Production cooldown active | Wait until `/ampautocert status` reports the next allowed attempt; restarts and repeated commands do not clear it. |
| DNS propagation timeout | Check authoritative Cloudflare nameservers, the exact zone ID, token scope, and `_acme-challenge` records. Do not add permanent TXT values as a workaround. |
| Activation/readiness timeout | Confirm AutoModpack internal TLS is enabled, port 25809 is configured, and no other process owns the endpoint. The old pair should remain or be restored. |
| Certificate still appears self-signed | Check the status outcome and server log first. Do not overwrite only one credential file. Confirm clients reach the configured AutoModpack endpoint. |
| Existing client reports fingerprint mismatch | Remove that client's old manual pin only after independently verifying the server and new public certificate. |

Logs and support bundles must not include the token, ACME account keys, private
key, raw Cloudflare responses, the complete credentials directory, or unredacted
panel environment settings.
