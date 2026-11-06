# Codex task: automatic enrollment, AutoModpack discovery, and fail-closed configuration reconciliation

## Objective

Implement the next amp-autocert milestone without rewriting the already validated ACME/deployment core.

The project should explicitly target Minecraft server operators on restricted managed hosting (especially Pterodactyl) who cannot run Certbot/cron/OS-level ACME tooling and cannot use DNSSEC for the Minecraft origin, but can delegate the relevant DNS zone to Cloudflare nameservers and provide a restricted Cloudflare API token.

amp-autocert must automatically:
1. discover and validate relevant AutoModpack network/TLS settings;
2. validate its own minimal configuration;
3. verify Cloudflare API authentication and that the configured zone is the authoritative Cloudflare zone;
4. perform a real Let's Encrypt **staging** DNS-01 issuance/cleanup test that is never deployed;
5. only after successful gates, perform production issuance and use the existing validated deployment/activation/rollback pipeline;
6. continuously detect security-relevant AutoModpack/configuration drift and fail closed until the operator explicitly reconciles it.

Target stack remains:
- Minecraft Java 1.21.1
- NeoForge 21.1.248
- Java 21
- AutoModpack versions currently supported by the repository only, unless a separate source/runtime review justifies widening the range.

Follow `AGENTS.md`.

## Product wording and documentation goal

Update README/operator documentation so the purpose is unambiguous:

> amp-autocert is a server-side NeoForge mod that obtains and renews publicly trusted CA-signed TLS certificates for AutoModpack using Let's Encrypt DNS-01 and Cloudflare DNS. It is designed for managed hosting where Certbot/cron are unavailable and DNSSEC cannot be used for silent AutoModpack trust. Cloudflare must be authoritative for the managed DNS zone because DNS-01 activation is performed through the Cloudflare API.

Avoid the ambiguous phrase "CA certificate" when "publicly trusted CA-signed certificate" is meant. Explicitly explain that Cloudflare Origin CA is not a substitute for a publicly trusted certificate in the direct-client AutoModpack trust path.

Document features, prerequisites, limitations, supported topology, failure behavior, secret handling, staging/production separation, and migration.

## Design constraints

### 1. Minimize duplicated configuration

The current properties file duplicates information already owned by AutoModpack. Remove duplication where it is safe.

Keep operator-owned values that cannot be reliably derived:
- `origin.hostname`: Minecraft hostname players type, e.g. `play.example.com`. This is the trust origin and cannot in general be derived from AutoModpack routing/SRV settings.
- `acme.email`.
- `cloudflare.zoneName`.
- `cloudflare.zoneId`.
- renewal/check and Pterodactyl compatibility settings that are truly amp-autocert behavior.

Derive instead of configuring where possible:
- certificate identifiers: `zoneName` and `*.zoneName`;
- DNS-01 owner: `_acme-challenge.<zoneName>`;
- AutoModpack advertised endpoint host/port;
- AutoModpack connection mode;
- AutoModpack bind port when relevant;
- AutoModpack `disable-internal-tls`;
- AutoModpack `modpack-host`;
- SRV route may be discovered from DNS for diagnostics, but it is not the certificate identity.

Remove `minecraft.srvTarget` and `automodpack.endpoint` from the user configuration after a migration-safe transition.

The Let's Encrypt directory URLs, Cloudflare API root, and supported public DoH endpoints are implementation/provider constants, not normal operator knobs. Keep them injectable in tests if needed, but remove them from the normal public configuration unless there is a strong tested reason not to.

### 2. AutoModpack configuration adapter

Introduce a narrow adapter such as:
- `AutoModpackConfigSource`
- `AutoModpackIdentitySnapshot`

Prefer reading AutoModpack's loaded/effective configuration through a stable inspected API when one exists. If the supported versions do not expose a safe stable object, use a version-pinned parser/adapter for `automodpack/server.conf`.

Do **not** parse HOCON with regexes. Reuse a compatible parser already present in the dependency graph when possible; otherwise add the smallest justified parser dependency and document it.

Keep version-specific reflection/parsing isolated similarly to the existing `AutoModpackHostControl`.

The adapter must expose enough information to validate:
- modpack host enabled/available;
- connection mode;
- bind port when relevant;
- effective advertised endpoint host and port;
- whether AutoModpack owns TLS (`disable-internal-tls=false`);
- any other value required to safely derive the client endpoint.

If an empty advertised host/port means "use the hostname/port the player connected to", resolve that semantic explicitly using `origin.hostname` and the actual AutoModpack rules. Do not invent a value.

### 3. Supported topology and hard blockers

This milestone supports direct AutoModpack TLS termination. Treat at least these cases as hard blockers:
- `disable-internal-tls=true` (TLS terminates elsewhere);
- effective endpoint cannot be determined safely;
- endpoint hostname is an IP when the configured certificate model is DNS wildcard/apex only;
- origin or effective endpoint is not covered by the requested apex/wildcard SAN set;
- zone is not active/authoritative at Cloudflare;
- public NS delegation does not match the Cloudflare zone's assigned nameservers;
- Cloudflare token is missing, invalid/expired, cannot access the configured zone, or staging cannot edit DNS;
- supported AutoModpack version/API/config shape is unavailable;
- AutoModpack credential target is unsafe/unwritable under the existing permission policy.

Do not silently broaden the certificate to unrelated domains. This milestone may keep the existing limitation that origin and endpoint are the apex or exactly one label beneath the configured zone; document that clearly.

### 4. Preflight / doctor

Add a command:

`/ampautocert doctor`

It may perform bounded network reads but must not create an ACME order and must not modify DNS or deployed credentials.

Preflight should return structured internal results and a concise operator rendering. At minimum validate:
- amp-autocert config;
- current supported AutoModpack version;
- AutoModpack relevant effective config;
- origin and endpoint SAN coverage;
- internal TLS ownership;
- credential paths/permissions;
- Cloudflare token using the current official token verification endpoint;
- exact Cloudflare zone access by configured zone ID;
- zone name/status from Cloudflare matches the configured zone;
- Cloudflare-assigned authoritative nameservers;
- public NS delegation observed through the existing independent DoH approach agrees with the Cloudflare zone;
- optional SRV information for diagnostics;
- current certificate metadata/coverage when a certificate exists;
- current configuration-drift/enrollment state.

Do not log the API token or raw provider bodies.

Cloudflare token verification proves authentication; zone lookup proves access to the intended zone; the subsequent staging DNS-01 exercise proves actual DNS write capability.

### 5. Automatic first enrollment

When a fresh installation is explicitly enabled, use a guarded state machine conceptually equivalent to:

`VALIDATE_LOCAL -> DISCOVER_AUTOMODPACK -> VERIFY_CLOUDFLARE -> VERIFY_DELEGATION -> STAGING_DNS01 -> PRODUCTION_ISSUANCE -> VALIDATE -> DEPLOY -> ACTIVATE -> VERIFY_FINGERPRINT -> ACTIVE`

Requirements:
- no production order before every preceding gate passes;
- staging must use the configured real identifiers/zone so it validates the actual Cloudflare DNS-01 path;
- staging certificate stays in memory/private temporary state only and is discarded;
- staging DNS records are cleaned using owned record IDs even on failure;
- never write staging material to AutoModpack credential paths;
- persist enough non-secret enrollment state to avoid repeating staging on every normal renewal;
- keep existing production cooldown/rate-limit protections;
- reuse existing issuance, certificate validation, crash-safe deployment, activation, rollback, and host-fingerprint verification components.

A successful staging exercise is required for first automatic production enrollment and after an explicitly accepted identity/provider reconciliation. It is not required for every routine renewal.

### 6. Existing installation migration

The repository already has a validated live production deployment. Upgrading must not rotate or destroy a currently valid deployed production certificate merely because the new enrollment-state file does not exist.

For a valid existing CA-signed certificate that covers the current origin and effective endpoint:
- preserve it;
- run the new non-destructive preflight;
- establish a migration/reconciliation state without immediate production issuance;
- complete the required staging validation before the next production renewal or before accepting changed identity/provider settings;
- if staging/preflight fails, keep serving the existing certificate and block future automated issuance with a clear warning.

Design this migration deterministically and test it.

### 7. Semantic configuration baseline and drift protection

Persist a non-secret canonical snapshot/baseline of security-relevant identity/routing/provider configuration. Store human-readable normalized fields plus a deterministic hash/version.

Candidate fields:
- origin hostname;
- Cloudflare zone name and zone ID;
- effective advertised endpoint host and port;
- connection mode;
- relevant bind port;
- `disable-internal-tls`;
- `modpack-host`;
- schema/version marker.

Do not hash the entire AutoModpack file. Modpack groups, mod lists, bandwidth settings, or unrelated changes must not suspend certificates.

Detect semantic drift regularly and before every staging/production/deployment operation. A lightweight local poll is preferable to relying only on a 24-hour renewal interval; polling is acceptable for Pterodactyl portability. It must perform no network/ACME work merely to detect local file drift.

If a relevant value changes or the AutoModpack config becomes unreadable:
- enter a state such as `BLOCKED_CONFIGURATION_DRIFT`;
- stop automatic staging, production issuance, deployment, and host restart;
- preserve the current certificate/key;
- log a rate-limited warning that names changed fields but no secrets;
- expose the reason in `/ampautocert status`;
- require explicit operator reconciliation.

Changing unrelated AutoModpack settings must not trigger this state.

### 8. Reconciliation command

Add:

`/ampautocert reconcile`

This command means the operator has intentionally accepted the newly discovered configuration. It must **not** blindly write a new baseline and resume production.

It must:
1. rediscover current AutoModpack settings;
2. run full preflight;
3. run the required staging DNS-01 issuance/cleanup;
4. only after successful checks persist the new accepted baseline;
5. allow subsequent production enrollment/renewal according to normal gates.

If any step fails, remain blocked and preserve the active credential pair.

Provide a clear command response and status output.

### 9. Status model

Extend operator status to make these states understandable without reading logs. Distinguish at least:
- disabled;
- ready/preflight-valid;
- staging validation pending/running/failed;
- active;
- renewal due/running;
- blocked configuration drift;
- blocked unsupported AutoModpack/TLS topology;
- cooldown;
- stopping/error.

Do not expose secrets in status.

### 10. Config migration

Provide migration-safe handling for v0.1.0 properties:
- recognize deprecated `minecraft.hostname` as an alias for `origin.hostname` for at least one migration path;
- detect/remove dependence on `minecraft.srvTarget`, `automodpack.endpoint`, `acme.domains`, and `dns.challengeRecord`;
- derive values and warn about deprecated keys rather than silently using conflicting duplicates;
- if an old explicit value conflicts with newly discovered AutoModpack state, fail closed and require reconciliation instead of choosing one silently.

Update the example config to be substantially smaller.

### 11. Cloudflare authoritative DNS requirement

Use official Cloudflare API behavior.

Preflight should:
- verify the API token is active via `GET /user/tokens/verify` or the currently documented equivalent;
- fetch `GET /zones/{zone_id}`;
- verify the returned zone name and active state;
- obtain the zone's assigned nameservers;
- query public NS records for `cloudflare.zoneName` through independent DoH resolvers;
- require delegation to match the zone's assigned nameservers after canonical normalization.

Do not merely check that NS hostnames end with `cloudflare.com`; Cloudflare custom/vanity nameserver arrangements should be judged against the zone object and public delegation, not a suffix heuristic.

The staging DNS-01 flow is the write-permission proof. Avoid creating a separate dummy DNS record solely for preflight unless there is a compelling reason.

### 12. Documentation

Update:
- `README.md`
- `README_RU.md`
- `docs/PRODUCTION_SETUP.md`
- `docs/PRODUCTION_SETUP_RU.md`
- config example and relevant third-party/security notes if dependencies change.

Document:
- exact purpose/use case;
- Cloudflare authoritative NS prerequisite;
- DNSSEC is not required for this CA path;
- Certbot/cron are not required;
- Cloudflare Origin CA is not supported as a public-trust substitute;
- what is auto-discovered from AutoModpack;
- what the operator still configures;
- token scope/storage;
- first-enrollment staging -> production sequence;
- routine renewal behavior;
- drift blocking/reconciliation;
- external TLS/reverse-proxy limitation;
- apex/wildcard/one-label limitation if retained;
- existing manually pinned clients may still need pin removal;
- recovery behavior.

Keep EN/RU docs semantically aligned.

## External references to verify during implementation

Use current official sources, not these notes alone:
- AutoModpack security: https://moddedmc.wiki/en/project/automodpack/latest/docs/security
- AutoModpack server config: https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config
- AutoModpack hosting: https://moddedmc.wiki/en/project/automodpack/latest/docs/how-it-works/hosting
- Cloudflare API: https://developers.cloudflare.com/api/
- Cloudflare user token verification: https://developers.cloudflare.com/api/resources/user/subresources/tokens/methods/verify/
- Cloudflare zone details: https://developers.cloudflare.com/api/resources/zones/methods/get/
- Let's Encrypt documentation: https://letsencrypt.org/docs/
- acme4j: https://github.com/shred/acme4j

If an official source disagrees with this task because an API changed, preserve the security intent and document the discrepancy before changing behavior.

## Required tests

At minimum add deterministic tests for:
- new minimal config and v0.1.0 migration;
- derived apex/wildcard/challenge owner;
- effective AutoModpack endpoint discovery for supported modes/default semantics;
- unsupported/malformed AutoModpack config;
- `disable-internal-tls=true` blocker;
- endpoint/origin outside SAN coverage;
- token inactive/invalid response handling;
- Cloudflare zone ID/name mismatch;
- public NS mismatch;
- staging failure prevents production order;
- staging success permits exactly one guarded transition into production enrollment;
- staging material is never deployed;
- owned DNS records are cleaned on staging failure/success;
- current valid certificate migration does not trigger unnecessary production rotation;
- canonical snapshot stability;
- unrelated AutoModpack changes do not trigger drift;
- relevant endpoint/domain/TLS changes do trigger drift;
- drift blocks renewal/deployment/restart;
- reconcile failure remains blocked;
- successful reconcile requires preflight + staging before baseline acceptance;
- status/command permission behavior;
- secret redaction;
- shutdown/single-flight behavior is preserved.

Use fakes for Cloudflare/ACME/network in unit tests. CI must not require real credentials or real certificate issuance.

Run:
- targeted tests during development;
- `./gradlew test`;
- `sh scripts/offline-smoke-test.sh` when applicable.

## Definition of done

The milestone is done only when:
- first enrollment can automatically progress from verified preflight through staging to safe production deployment;
- routine renewals do not repeat staging unnecessarily;
- AutoModpack routing/TLS values are no longer duplicated in amp-autocert config where derivation is safe;
- a relevant AutoModpack/domain/provider change blocks automation before ACME/deployment side effects;
- the operator can diagnose with `doctor` and intentionally recover with `reconcile`;
- an existing valid deployment survives upgrade and all failed checks;
- tests cover failure gates and migration;
- English/Russian docs clearly state capabilities, prerequisites, and limitations;
- no secrets are added to Git, logs, public state, or test fixtures.
