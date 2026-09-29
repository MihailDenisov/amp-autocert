---
name: acme-cloudflare
description: Use when implementing or testing Let's Encrypt ACME v2 DNS-01 challenges, Cloudflare DNS API integration, wildcard certificates, renewal scheduling, or staging-to-production transition.
---
# ACME / Cloudflare workflow
1. Prefer acme4j plus its documented transport dependencies; pin compatible versions and document licenses. Avoid hand-rolling ACME cryptography.
2. Account registration and challenge lifecycle are idempotent and persist across Minecraft restarts. Support Let's Encrypt staging first; NEVER deploy staging trust to public players.
3. Request SANs `maicraft.tech` and `*.maicraft.tech`. DNS-01 for wildcard and apex can require *simultaneous* TXT values at the same `_acme-challenge.maicraft.tech` name. Track provider record IDs per challenge and delete only records this mod created.
4. Use a Cloudflare token scoped to Zone DNS Edit and the maicraft.tech zone; read only from environment or private credential file. Avoid printing tokens, raw Cloudflare JSON, or ACME account state in logs.
5. Poll DNS and authorization status with bounded retries/timeouts/backoff. Respect ACME rate limits and Retry-After. Keep old certificate on any partial failure.
6. For tests, mock ACME, Cloudflare HTTP, and DNS resolvers. Never require real credentials for unit tests.
References: https://github.com/shred/acme4j and https://developers.cloudflare.com/api/resources/dns/subresources/records/
