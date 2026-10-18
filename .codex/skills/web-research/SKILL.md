# Official web research

Use this skill when implementation depends on current external behavior.

## Preferred model
Terra/Luna or equivalent fast model for retrieval; low reasoning. Escalate conflicting/security-critical interpretation to the primary agent.

## Source priority
1. AutoModpack official docs/source
2. Cloudflare official API docs
3. Let's Encrypt official docs
4. NeoForge official docs
5. acme4j official docs/source

## Procedure
1. State the exact question.
2. Find the current authoritative page.
3. Extract only facts needed for the question.
4. Record exact setting/endpoint names and edge-case semantics.
5. Include the URL and access date when adding durable implementation notes.
6. If docs conflict with inspected code for a pinned dependency version, report both; do not guess.
7. Never paste credentials, tokens, private keys, challenge values, or private support logs into searches.

## Output
- Conclusion
- Authoritative URL(s)
- Exact relevant fact
- Version/date caveat
- Any unresolved conflict
