# amp-autocert

[Русская версия](README_RU.md) · [Complete setup guide](docs/PRODUCTION_SETUP.md) · [CurseForge description](curseforge.md)

amp-autocert is a **server-only** NeoForge mod for Minecraft **1.21.1**, NeoForge **21.1.248**, Java **21**, and AutoModpack **5.0.0-rc.1/rc.2** (`automodpack_mod`). It obtains and renews publicly trusted Let's Encrypt certificates for AutoModpack's built-in TLS host through Cloudflare DNS-01. It is intended for Pterodactyl and similar managed hosting where Certbot/cron cannot run and DNSSEC is unavailable for AutoModpack's separate AMP1 trust method. DNSSEC is not required for this certificate method. Cloudflare must be authoritative for the zone; Cloudflare Origin CA is not publicly trusted for direct Minecraft clients.

Current `0.2.0-rc.1` is a **prerelease** of the discovery/reconciliation workflow; see the [changelog](CHANGELOG.md) for its known limitations. Stable `v0.1.0` remains the live-validated production-renewal reference.

> A reference server completed live production issuance, DNS-01 cleanup, AutoModpack 5.0.0-rc.2 activation and client verification on 2026-09-29. Each other server must be checked separately. New installations start disabled and need operator-confirmed staging validation before production issuance.

## Addresses and limits

| Role | Example | Configured in |
| --- | --- | --- |
| Address players type | `play.example.com` | `origin.hostname` in amp-autocert |
| Minecraft SRV target, if used | `node.example.com:25598` | DNS/Minecraft; not a certificate identifier |
| AutoModpack endpoint | `packs.example.com:25809` | AutoModpack `advertised-endpoint-*`; discovered by the mod |
| Certificate DNS SANs | `example.com`, `*.example.com` | Derived from `cloudflare.zoneName` |
| DNS-01 owner | `_acme-challenge.example.com` | Derived automatically |

The apex/wildcard pair covers the apex and exactly one subdomain label. The join and AutoModpack endpoint hostnames must be covered; IP literals, deeper names and external TLS termination are unsupported. Ports are network routing, not certificate names. AutoModpack must have `modpack-host: true` and `disable-internal-tls: false`; assign and expose actual Minecraft and AutoModpack ports in your hosting panel. Set direct-connection A/AAAA records to Cloudflare **DNS only**. See [AutoModpack network settings](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config).

## Quick setup

1. Install supported AutoModpack and amp-autocert JARs on the dedicated server. Activate the zone in Cloudflare and set registrar NS to Cloudflare's assigned nameservers. Create a token limited to that zone with `Zone / DNS / Edit`.
2. Copy [the example properties](config/amp-autocert.properties.example) to `<server-root>/automodpack/amp-autocert.properties`. Set `acme.email`, `origin.hostname`, `cloudflare.zoneName`, and `cloudflare.zoneId`. Put the token only in `AMP_AUTOCERT_CF_API_TOKEN` or the private file `automodpack/credentials/amp-autocert-cloudflare-token.txt`.
3. Exclude `automodpack/credentials/**`, `automodpack/amp-autocert.properties`, and `automodpack/amp-autocert-state/**` from **every** AutoModpack group's client file rules. Back up the complete credentials and enrollment-state directories privately before production work.
4. Start with `enabled=false`; run `/ampautocert doctor`. It reads local and Cloudflare/DNS state, but creates no ACME order or DNS record. A missing baseline is expected on a fresh installation.
5. When ready, set `enabled=true`, `acme.environment=production`, `host.restartAfterRenew=true` and restart. Run `/ampautocert reconcile confirm`: full preflight plus a real Let's Encrypt **staging** DNS-01 test. It cleans its own TXT records, discards staging material and accepts the baseline only on success. It does not itself issue a production certificate.
6. Run `/ampautocert check` for an ordinary due/not-due production check; use `/ampautocert renew` only for intentional early production rotation. Inspect `/ampautocert status`, certificate SANs and real client connectivity.

Routine renewal does not repeat staging. Relevant origin, zone, endpoint, mode/port or TLS changes block issuance and deployment until another successful reconciliation. The current deployed pair is preserved on failed checks; a valid certificate is not rotated just because a new baseline is missing. Existing manual client fingerprint pins may require one-time removal. The [full runbook](docs/PRODUCTION_SETUP.md) explains all fields, secret storage, migration, backup, recovery and troubleshooting ([Русский](docs/PRODUCTION_SETUP_RU.md)).

## Commands

Commands require permission level 3; network/certificate work runs on a background worker.

| Command | Effect |
| --- | --- |
| `/ampautocert status` | Cached environment, expiry/SANs, last result, cooldown and safe drift field names. |
| `/ampautocert doctor` | Read-only preflight; does not prove DNS write access or actual endpoint reachability. |
| `/ampautocert reconcile` | Explanation only. |
| `/ampautocert reconcile confirm` | Full preflight, staging issuance/cleanup, then baseline acceptance; never deploys staging. |
| `/ampautocert check` | Immediate ordinary check; does not force early renewal. |
| `/ampautocert renew` | Intentional early production renewal; all guards and cooldown apply. |
| `/ampautocert staging-issue` | Optional standalone staging probe only with `enabled=true`, the `staging` environment and an accepted baseline. |

## Build

Install JDK 21 and run `./gradlew test` and `./gradlew build` (Windows: `gradlew.bat`); the JAR appears in `build/libs/`. First builds need internet for Gradle/NeoForge. A POSIX-shell Java-only check is `sh scripts/offline-smoke-test.sh`. The repository does not contain production credentials, account state or certificates. See [dependency licenses](docs/THIRD_PARTY.md).

Sources: [AutoModpack security](https://moddedmc.wiki/en/project/automodpack/latest/docs/security), [AutoModpack configuration](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config), [Let's Encrypt DNS-01](https://letsencrypt.org/docs/challenge-types/), [Let's Encrypt staging](https://letsencrypt.org/docs/staging-environment/), [Cloudflare API tokens](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/).
