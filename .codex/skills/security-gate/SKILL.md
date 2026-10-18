# Security gate review

Use for any change affecting trust, certificates, secrets, ACME, deployment, rollback, configuration drift, or concurrency.

## Model
Primary capable model only. Use medium/high reasoning. Fast subagents may gather evidence but must not be final authority.

## Review checklist
- Does uncertainty fail closed?
- Can staging material ever reach production credential paths?
- Can a production order start before all required gates?
- Can a config change race issuance/deployment?
- Are origin and endpoint both covered by the certificate SAN policy?
- Is external TLS termination correctly rejected for this milestone?
- Can provider responses or exceptions leak the API token/challenge/key material?
- Are certificate and private key always treated as a matching atomic pair?
- Are rollback and crash-recovery guarantees preserved?
- Are DNS records deleted only by IDs owned by this issuance?
- Can retries/restarts bypass cooldowns or single-flight?
- Can migration rotate a currently valid certificate unnecessarily?
- Does reconciliation require fresh preflight + staging?
- Are security-relevant tests present and still strict?

Return blocking findings first. Do not approve based only on happy-path tests.
