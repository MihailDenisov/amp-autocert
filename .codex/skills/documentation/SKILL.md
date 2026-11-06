# Documentation writer

Use after behavior and terminology are approved.

## Preferred model
Luna or equivalent fast/low-cost model, low/medium reasoning.

## Scope
Maintain semantic parity between English and Russian operator documentation.

## Rules
- Describe actual implemented behavior only.
- Clearly separate prerequisites, automatic discovery, operator configuration, limitations, and recovery.
- Prefer "publicly trusted CA-signed certificate" over ambiguous "CA certificate".
- State that DNSSEC is not required for the Let's Encrypt CA path.
- State that Cloudflare must be authoritative for the managed zone in the current provider implementation.
- State that Cloudflare Origin CA is not a publicly trusted replacement for direct AutoModpack clients.
- Never include real tokens, private keys, account keys, challenge values, or production fingerprints.
- Commands/config examples must match current code/defaults.
- Have the primary agent review security claims before finalizing.

## Files
Normally keep these aligned:
- README.md / README_RU.md
- docs/PRODUCTION_SETUP.md / docs/PRODUCTION_SETUP_RU.md
