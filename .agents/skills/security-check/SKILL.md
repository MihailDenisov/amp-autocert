---
name: security-check
description: Use before merging code involving token handling, ACME private keys, Cloudflare access, TLS certificate issuance, filesystem operations, AutoModpack host control, or release packaging.
---
# Focused security gate
- Explicitly enumerate sensitive data sources, log paths, AutoModpack sync paths, and jar/archive contents.
- Assert the Cloudflare token never lives in `config/`, the repo, or client-synced files. Do not log full command lines or secret-containing HTTP responses.
- Reject absolute-path overrides, symlink traversal of the credentials directory, and output paths outside the Minecraft server root.
- Validate issued certificate chains/SANs and matching PKCS#8 key before touching the live files. Preserve old certificate on validation/IO/restart failure.
- Verify staging and production state separation, allowlist domains, reasonable renewal frequency and retry backoff; no insecure fallback to self-signed on failed production renewal.
- Ask `security_reviewer` for one narrow final read-only diff review if the change touches keys, tokens, HTTP, DNS or TLS; resolve high-severity findings.
