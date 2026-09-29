# amp-autocert — instructions for Codex

Project: dedicated-server Minecraft 1.21.1 / NeoForge 21.1.248 mod. Build with Java 21. The published mod ID is `amp_autocert`; the distribution name is `amp-autocert`.

## Objective and truthfulness
Implement `CODEX_TASK.md` in small, tested increments. The checked-in starter is STATUS-ONLY. Never claim that it issues, installs, or renews certificates until the whole ACME pipeline and integration tests work. Default to disabled + Let's Encrypt staging during development.

## Cost-aware delegation
- Read only files required for the current change. First inspect `CODEX_TASK.md` and one relevant skill; do not dump the whole repository into context.
- For narrow investigation, docs, or test-log triage, use `fast_scanner` or `test_triage` from `.codex/agents/`; send filenames, a bounded question, and ask for <= 8 lines of findings. They use a fast, economical model.
- Do NOT spawn an agent for a trivial single-file edit. At most 2 concurrent subagents, and at most 1 per distinct question. Avoid duplicate exploratory work.
- Main agent owns integration, ACME state decisions, and security-sensitive code. Use `security_reviewer` once before merging credential, certificate, DNS, or TLS changes; it may use a stronger model, but with a bounded diff and concise report.
- If the configured model is unavailable on the user's Codex plan, use an available cheap/fast model; do not silently upgrade all workers to the expensive parent model.
- Preserve a short handoff in `docs/IMPLEMENTATION_LOG.md` after completing a milestone, not every tool call. Return concise diffs/test summaries instead of raw logs.

## Mandatory security rules
- Cloudflare API token and ACME account private key must NEVER appear in Git, `config/`, logs, AutoModpack sync, or a client JAR. Prefer `AMP_AUTOCERT_CF_API_TOKEN` or a restricted file under `automodpack/credentials/`.
- Do not disable TLS, skip CA checks, expose a dashboard, or accept unrestricted certificate/domain input.
- All network I/O belongs off the Minecraft main thread; all Minecraft commands and host management belong on the server thread.
- Never deploy Let's Encrypt staging certificates to a public server. Do not overwrite a working certificate when validation or renewal fails.
- Do not invent AutoModpack APIs. Pin and inspect the exact AutoModpack version installed by the operator before integrating `/automodpack host restart`.

## Skills (load only when applicable)
- `$neoforge-build`: Gradle, NeoForge API, VS Code and packaging.
- `$acme-cloudflare`: ACME DNS-01 issuance/renewal and DNS cleanup.
- `$tls-deployment`: safe PEM installation, validation and AutoModpack host refresh.
- `$security-check`: changes touching keys, tokens, HTTP, or certificate trust.
- `$budgeted-workflow`: delegation and token-budget checkpoints on multi-file tasks.

## Verify changed code
- Offline pure-Java smoke check: `sh scripts/offline-smoke-test.sh` (or equivalent on Windows).
- With network enabled: `./gradlew test` and `./gradlew build`; for server events, also `./gradlew runServer` and a NeoForge dedicated-server smoke test. If network is unavailable, clearly report the unrun checks.
- Don't ask for real Cloudflare credentials to test. Use fake HTTP endpoints, staging, and recorded deterministic fixtures.
