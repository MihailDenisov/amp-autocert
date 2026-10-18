# Test runner and failure triage

Use this skill for deterministic test execution and compact triage.

## Preferred model
Terra/Luna or equivalent fast model, low reasoning for execution/summarization. Escalate security, concurrency, deployment, rollback, or ACME failures to the primary agent.

## Procedure
1. Run the narrowest relevant test set first.
2. Capture exit code and the first actionable failure.
3. Distinguish environment/download failures from code failures.
4. Do not "fix" by deleting/weakening a security test.
5. After targeted tests pass, run the full suite.
6. Run `sh scripts/offline-smoke-test.sh` when applicable.
7. Never use real production credentials in tests.

## Report
- Command
- Pass/fail count
- First root cause
- Files/tests implicated
- Whether primary-agent escalation is required
