# Subagent policy

This file expands the repository-wide rules in `AGENTS.md`.

## Goal

Reduce token/cost usage without reducing correctness or security. Use cheap/fast subagents for bounded evidence gathering and mechanical work; reserve the primary capable agent for decisions where a subtle mistake can rotate credentials, weaken trust, leak secrets, or break rollback.

## Preferred routing

Model names depend on the Codex runtime. When available:

| Task | Preferred lane | Reasoning |
| --- | --- | --- |
| Locate files/symbols/call sites | Terra or fastest equivalent | low |
| Read one official documentation page and extract facts | Terra/Luna | low |
| Compare 2-3 official docs/config versions | Luna | low/medium |
| Run deterministic tests and summarize failures | Terra/Luna | low |
| Draft EN/RU docs from approved behavior | Luna | low/medium |
| Mechanical unit-test cases for an already-defined invariant | Luna | medium |
| Security/ACME/deployment architecture | primary capable model | medium/high |
| Debug ambiguous concurrency/rollback failures | primary capable model | high |
| Final security review | primary capable model | high |

If Terra/Luna are unavailable, use the cheapest available model with equivalent capability. If the runtime allows reasoning selection, prefer the lowest level sufficient for the task.

## Escalation triggers

A cheap subagent must stop and return evidence rather than decide when it encounters:
- conflicting official documentation;
- a proposed change to hostname/SAN trust semantics;
- secret handling or filesystem permission changes;
- staging/production gate changes;
- certificate/private-key validation changes;
- rollback/crash recovery changes;
- AutoModpack reflection/API uncertainty;
- concurrency, locking, server-thread, retry, or cooldown changes;
- a failing test that protects one of the above.

The primary agent resolves these.

## Output discipline

Subagent reports should normally contain:
1. conclusion;
2. exact evidence (file + symbol or official URL);
3. uncertainty/open question;
4. no more than ~20 lines unless specifically requested.

Do not return copied full web pages, giant build logs, or entire source files. For test failures, include the first actionable stack trace/cause and affected tests.

## Parallelism

Use at most 2-3 read-only subagents in parallel. Do not run concurrent agents that edit the same files. Do not recursively delegate.

Good parallel bundle:
- codebase scout;
- official AutoModpack/Cloudflare researcher;
- test inventory scout.

Bad parallel bundle:
- three agents independently redesigning the ACME state machine.

## Validation

The primary agent must verify any external fact that directly controls security behavior against an authoritative source before implementation.
