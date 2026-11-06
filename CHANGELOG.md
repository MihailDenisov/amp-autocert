# Changelog

## 0.2.1 — 2026-11-06

- Added `/ampautocert help` (also shown by `/ampautocert`) and expanded English/Russian command and permission documentation.
- On managed hosting, automatically tighten existing ACME state-file permissions before reading them. With `credentials.allowInsecureDeploymentFilePermissions=true`, read-only access for other users can remain when the panel cannot restrict it; the mod warns and continues. Shared write access and symbolic links remain blocked. With the default `false`, private permissions are required.

- Extended inspected AutoModpack 5.0.0 release-candidate compatibility from rc.1/rc.2 to rc.1–rc.7. Verified the configuration fields, parser signature, host-state methods, and certificate paths in upstream rc.1–rc.7 source; checked the published rc.7 JAR signatures.
- The operator reported a successful production renewal, AutoModpack host restart and activation on rc.7 on 2026-11-06. This live result does not validate every supported release candidate. Later AutoModpack versions remain blocked pending review.
- Upstream evidence: [rc.7 release](https://github.com/Skidamek/AutoModpack/releases/tag/v5.0.0-rc.7), [configuration fields](https://github.com/Skidamek/AutoModpack/blob/v5.0.0-rc.7/core/src/main/java/pl/skidam/automodpack_core/config/ServerConfigJsons.java), [config parser](https://github.com/Skidamek/AutoModpack/blob/v5.0.0-rc.7/core/src/main/java/pl/skidam/automodpack_core/config/ReconfConfigs.java), [host state and TLS loading](https://github.com/Skidamek/AutoModpack/blob/v5.0.0-rc.7/core/src/main/java/pl/skidam/automodpack_core/protocol/netty/NettyServer.java), [certificate paths](https://github.com/Skidamek/AutoModpack/blob/v5.0.0-rc.7/core/src/main/java/pl/skidam/automodpack_core/storage/StoragePaths.java), and [implementation mod ID](https://github.com/Skidamek/AutoModpack/blob/v5.0.0-rc.7/gradle.properties). The same integration points and mod ID were checked in all seven tagged release candidates.

## 0.2.0-rc.1 — 2026-11-03 (prerelease)

This is a development prerelease for operators testing AutoModpack discovery and explicit staging reconciliation. Stable `v0.1.0` remains the reference release with a live-validated production renewal pipeline. No live Cloudflare/Let's Encrypt issuance was performed for this prerelease.

### Added

- Read-only `/ampautocert doctor` preflight for effective and on-disk AutoModpack settings, credential targets, Cloudflare token/zone, and public NS delegation.
- Derived certificate apex/wildcard SANs and DNS-01 owner from the Cloudflare zone; derived AutoModpack endpoint and port from its own configuration.
- Persisted non-secret configuration baseline, local semantic drift detection, and fail-closed issuance/deployment gates.
- `/ampautocert reconcile confirm` to perform full preflight plus real Let's Encrypt staging DNS-01 validation before accepting a baseline. Staging material is discarded and never deployed.
- Detailed English and Russian operator guides, migration/troubleshooting instructions, and a short bilingual CurseForge description.

### Compatibility and limitations

- Requires Java 21, Minecraft 1.21.1, NeoForge 21.1.248, and AutoModpack `5.0.0-rc.1` or `5.0.0-rc.2` (`automodpack_mod`).
- First enrollment and configuration reconciliation require an explicit operator `/ampautocert reconcile confirm`; startup does not automatically initiate the staging exercise. Routine renewals do not repeat staging after an accepted baseline.
- Existing valid certificates remain in place while baseline is missing or a gate fails. A long-lived self-signed certificate can be `not_due` because the ordinary renewal decision uses expiry; after successful reconciliation use `/ampautocert renew` for an intentional early move to public CA trust.
- Legacy `minecraft.srvTarget` and `automodpack.endpoint` values are deprecated. The old endpoint is parsed but is not compared to the AutoModpack-discovered endpoint; compare manually during migration and remove the legacy property.
- External TLS termination, IP endpoint names, names outside the configured apex/one-label wildcard coverage, and other AutoModpack versions remain unsupported.

Before using this prerelease on a live server, read [Production setup](docs/PRODUCTION_SETUP.md) or [Настройка production](docs/PRODUCTION_SETUP_RU.md), back up the complete credential and enrollment-state directories, and verify the mod on your own zone and host.
