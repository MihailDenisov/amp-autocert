# amp-autocert agent instructions

These instructions apply to the whole repository.

## Mission

amp-autocert is a server-only NeoForge mod for Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. Its purpose is to obtain, validate, deploy, activate, and renew publicly trusted CA-signed TLS certificates for AutoModpack on managed hosting where the operator cannot run Certbot, cron, systemd timers, or other OS-level ACME tooling.

The primary supported deployment for the current milestone is Pterodactyl-style hosting with:
- no usable DNSSEC path for the Minecraft origin;
- Cloudflare authoritative DNS for the certificate zone;
- Cloudflare API access using a narrowly scoped API token;
- Let's Encrypt ACME DNS-01;
- AutoModpack's built-in TLS termination.

Read `CODEX_TASK.md` before making implementation changes.

## Safety invariants

These are non-negotiable:
- Never log, print, commit, serialize into public state, or expose the Cloudflare token, ACME account private key, certificate private key, DNS-01 challenge values, provisioning secrets, or raw credential directory contents.
- Never deploy a Let's Encrypt staging certificate.
- Never start a production ACME order unless the current production gate says the configuration is safe and, for first enrollment or reconciliation, the required staging validation has succeeded.
- Preserve the currently deployed certificate/key pair on every preflight, staging, configuration-drift, network, parsing, or provider failure.
- Configuration uncertainty fails closed. Do not guess AutoModpack endpoints, TLS ownership, domains, or ports.
- Security-sensitive state transitions, deployment/rollback, certificate validation, ACME behavior, secret handling, and concurrency changes require primary-agent review.
- Do not widen the supported AutoModpack version range without inspecting and testing the target release.
- Do not merge, publish a release, rotate a real production certificate, or use real credentials unless the human operator explicitly asks for that action.

## Work style

Prefer the smallest safe change. Reuse the existing issuance, validation, crash-safe deployment, activation, rollback, cooldown, and single-flight components. The next milestone should add discovery/orchestration around them rather than rewrite validated cryptographic/deployment code.

Keep provider-specific and AutoModpack-version-specific behavior behind narrow interfaces. Keep pure parsing, canonicalization, state decisions, and validation easy to unit test without Minecraft, Cloudflare, or Let's Encrypt.

## Token-efficient subagent policy

Use subagents deliberately. The primary agent owns the final design and all security-critical decisions.

When the runtime exposes Terra, Luna, or equivalent fast/low-cost models, prefer them for bounded non-critical tasks. If those names are unavailable, choose the cheapest/fastest available subagent and the lowest reasoning level that can reliably complete the task.

Good candidates for Terra/Luna or low reasoning:
- repository/file discovery and call-site mapping;
- reading official documentation and extracting exact configuration/API facts;
- checking whether a symbol/path/string exists;
- running already-defined tests and summarizing deterministic failures;
- drafting or synchronizing English/Russian documentation from an approved design;
- mechanical test-case expansion after the primary agent defines the invariant;
- formatting, typo fixes, link checks, and release-note drafts.

Use Luna/medium reasoning (or equivalent) when a small amount of synthesis is needed:
- compare two documented API/config versions;
- summarize several failing tests;
- propose a test matrix from already-approved acceptance criteria;
- review documentation for consistency with implemented behavior.

Do NOT delegate the following to a cheap/low-reasoning agent as the final authority:
- ACME order/challenge/account lifecycle design;
- certificate/key parsing or validation rules;
- trust-model or hostname/SAN decisions;
- secret-storage or permission decisions;
- production/staging gates;
- AutoModpack TLS ownership decisions;
- deployment, rollback, crash recovery, locking, retry/cooldown semantics;
- configuration-drift acceptance/reconciliation;
- concurrency or server-thread behavior;
- deciding whether a failing security test may be weakened or removed.

For those tasks, use the primary capable model with medium/high reasoning. A fast subagent may gather facts, but the primary agent must verify the sources and make the decision.

### Delegation budget

- Prefer at most 2-3 parallel read-only subagents at a time.
- Give each subagent one narrow question with explicit output limits.
- Ask for conclusions + evidence, not a long narrative.
- Reuse prior research; do not ask several agents to reread the same source unless doing an intentional cross-check.
- Do not recursively spawn subagents.
- Stop escalating once the fact is resolved from an authoritative source.
- Do not spend a high-reasoning agent on deterministic test execution, grep-like discovery, or documentation transcription.

## Research policy

For external behavior that can change, use current authoritative sources. Priority:
1. AutoModpack official documentation/source for AutoModpack behavior.
2. Cloudflare official API documentation for Cloudflare behavior.
3. Let's Encrypt official documentation for ACME/staging/rate-limit behavior.
4. NeoForge official documentation for lifecycle/build behavior.
5. acme4j official documentation/source for library behavior.

Record the exact URL and the relevant fact in the implementation notes when an external fact materially changes the design. Do not rely on search snippets, forum posts, or generated summaries when an official source is available.

Use the web-research skill for this work.

## Testing policy

Use the test-runner skill. Fast/cheap agents may execute tests and summarize output. The primary agent must inspect failures affecting security gates, deployment/rollback, credential handling, ACME state, AutoModpack compatibility, configuration drift, or concurrency.

Never make a test pass by weakening an invariant without explicit human approval.

Before considering the milestone complete:
- run targeted tests while iterating;
- run the full unit test suite;
- run the offline smoke test where applicable;
- inspect the final diff for secret paths, accidental credentials, and security-regression changes;
- document any test that could not be run and why.

## Documentation policy

Documentation is part of the feature. Use a fast model for drafting after behavior is stable, then have the primary agent verify all security/operational claims.

Keep English and Russian operator docs semantically aligned. Clearly distinguish:
- supported use case;
- prerequisites;
- automatically discovered values;
- values the operator must configure;
- staging vs production behavior;
- limitations and unsupported topologies;
- fail-closed behavior;
- migration/reconciliation procedure.

## Commit hygiene

Keep commits scoped and reviewable. Never commit runtime credentials or generated certificate/account state. Do not modify release metadata or publish artifacts unless explicitly requested.
