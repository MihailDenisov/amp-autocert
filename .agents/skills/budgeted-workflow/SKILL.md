---
name: budgeted-workflow
description: Use for multi-file Codex tasks requiring low token use through limited cheap/fast subagents, targeted reading, and concise test reporting.
---
# Token-efficient workflow
1. Identify the next milestone and inspect only its files. Start from `CODEX_TASK.md` acceptance criteria, not the entire repository.
2. If bounded read-only exploration can happen independently, hand exactly one small task to `fast_scanner`. If a deterministic test log is noisy, give its tail plus relevant source file to `test_triage`. Both are configured for fast models; max 2 simultaneous agents.
3. Main agent implements and decides anything security-sensitive. Never delegate unrestricted edits of credentials or trust code to a low-cost agent.
4. Do not spawn agents for tasks shorter than the handoff. Never ask two agents the same question. No nested fan-out unless the user explicitly asks.
5. One security review after a meaningful completed security change; do not rerun it without a substantial diff. Short result format: changed files, pass/fail tests, next blocker.
6. Record milestone outcomes in `docs/IMPLEMENTATION_LOG.md` as a compact checklist for future sessions; this prevents re-reading long conversation history.
