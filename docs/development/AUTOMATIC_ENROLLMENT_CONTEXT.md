# Automatic enrollment milestone context

This document records the intent behind the next milestone. `CODEX_TASK.md` is the implementation brief.

## Problem

Some Minecraft hosting providers expose only a Pterodactyl-style panel. Operators can upload mods and edit server files but cannot install/run Certbot, cron jobs, systemd timers, or arbitrary host-level services. If DNSSEC is unavailable for the Minecraft origin, AutoModpack's DNSSEC AMP1 trust path cannot remove the first-join fingerprint prompt.

A publicly trusted certificate is the alternative: AutoModpack can silently trust it when the TLS chain validates for the advertised endpoint and the certificate SANs also cover the Minecraft hostname the player typed.

amp-autocert exists to automate that certificate lifecycle inside the Minecraft server process.

## Current provider scope

The milestone intentionally targets Cloudflare authoritative DNS:
- the domain/zone is delegated to Cloudflare nameservers;
- the operator supplies a narrowly scoped Cloudflare API token;
- Let's Encrypt DNS-01 is used for issuance;
- DNSSEC is not required for this CA path;
- AutoModpack performs its own TLS termination.

This is not yet a generic DNS-provider framework from the operator's perspective. Keep interfaces clean enough for future providers, but do not widen scope at the cost of the Cloudflare path.

## Why AutoModpack is the source of routing truth

AutoModpack owns its download listener and advertised endpoint. Duplicating host/port/mode/TLS settings in amp-autocert creates configuration drift that can silently issue or deploy a certificate for the wrong topology.

The Minecraft origin is different: it is the hostname players type and is the trust anchor in AutoModpack's CA rules. It may be reached through SRV and is not reliably derivable from the download endpoint. Therefore the origin remains explicit in amp-autocert.

## Intended enrollment

For a fresh enabled install:
1. validate local amp-autocert config;
2. discover supported AutoModpack effective network/TLS settings;
3. verify origin/endpoint certificate coverage;
4. verify Cloudflare token and exact zone;
5. verify public NS delegation matches that Cloudflare zone;
6. perform a real Let's Encrypt staging DNS-01 issuance and cleanup, never deploy it;
7. perform production issuance;
8. validate the returned key/chain/SANs;
9. use the existing crash-safe credential deployment;
10. restart AutoModpack through the existing server-thread path;
11. verify AutoModpack reports the exact new leaf fingerprint;
12. persist a non-secret accepted semantic baseline.

Routine renewals should not repeat staging unless reconciliation is required.

## Drift behavior

Only security-relevant semantic changes should suspend automation. Do not hash the whole AutoModpack config.

Examples that should block until explicit reconciliation:
- origin changes;
- Cloudflare zone or zone ID changes;
- effective advertised endpoint changes;
- relevant listener/connection mode changes;
- TLS ownership changes;
- the supported AutoModpack config becomes unreadable/incompatible.

Unrelated modpack group or content changes must not block certificate renewal.

While blocked:
- keep serving the existing certificate;
- do not issue staging/production orders;
- do not deploy credentials;
- do not restart the AutoModpack host on behalf of amp-autocert;
- surface a clear status/warning;
- require `/ampautocert reconcile`, which reruns preflight and staging before accepting the new baseline.

## Authoritative references

Re-check these during implementation because they may change:
- https://moddedmc.wiki/en/project/automodpack/latest/docs/security
- https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config
- https://moddedmc.wiki/en/project/automodpack/latest/docs/how-it-works/hosting
- https://developers.cloudflare.com/api/resources/user/subresources/tokens/methods/verify/
- https://developers.cloudflare.com/api/resources/zones/methods/get/
- https://letsencrypt.org/docs/
