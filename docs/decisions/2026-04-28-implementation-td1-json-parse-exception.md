# Decision Record: TD-1 JsonProcessingException Logging Fix — Implementation

Date: 2026-04-28
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2), secure-tdd-implementer (Lane B + Round 2)
Status: Accepted

## Summary

One-line production fix in `StompCallback.processGenericEvent`: removed `Throwable` argument from `LOGGER.error` call, replacing it with `e.getClass().getSimpleName()`. Prevents Jackson's `JsonProcessingException.getMessage()` — which embeds a verbatim wire-input fragment — from reaching JSON logs. TDD-verified: T-A4 was RED before the fix and GREEN after. 107 unit + 1 IT, 0 failures.

## Production Code Changes

| File | Change |
|------|--------|
| `mastodon/StompCallback.java:163` | `LOGGER.error("Could not parse GenericMessage", e)` → `LOGGER.error("Could not parse GenericMessage — exception={}", e.getClass().getSimpleName())` |

## Test Changes

| File | Tests added | Type |
|------|-------------|------|
| `mastodon/StompCallbackTest.java` | T-A1: `parseError_logsErrorLevel` | Unit |
| | T-A2: `parseError_includesAllowlistedExceptionSimpleName` — `ACCEPTABLE_PARSE_EXCEPTIONS` allowlist | Unit |
| | T-A3: `parseError_messagePrefixMatches` — prefix format confirmed | Unit |
| | T-A4: `parseError_attachesNoThrowable` — `getThrowableProxy() == null` strict **[red→green anchor]** | Unit |
| | T-A5: `parseError_doesNotPolluteMdcOrAudit` — MDC clean + AUDIT zero events | Unit |
| | T-B1: `parseError_doesNotLeakAttackerControlledFragment` `@ParameterizedTest` — 4 variants (ASCII/CRLF/ANSI/null-byte) | Unit |

**Final counts**: 107 unit + 1 IT = 108 total, 0 failures. Jacoco thresholds met.

## Security Requirements Verified

| SR | Status |
|----|--------|
| SR-TD1-01: No wire-payload byte in any log line | VERIFIED — T-B1 all 4 variants |
| SR-TD1-02: Structured `exception=<SimpleName>` at ERROR | VERIFIED — T-A1, T-A3 |
| SR-TD1-03: Legacy format with Throwable render gone | VERIFIED — T-A4 was RED before fix |
| SR-TD1-04: Assertions check `getArgumentArray()` + `getThrowableProxy()` | VERIFIED — T-A4, T-B1 |
| SR-TD1-05: TD1-INV-1 documented | VERIFIED — planning doc |
| SR-TD1-06: Mode-neutrality (no control-flow change) | VERIFIED — inspection |
| SR-TD1-07: Existing F-6 canaries not regressed | VERIFIED — full suite |
| SR-TD1-08: Bounded allowlist `ACCEPTABLE_PARSE_EXCEPTIONS` | VERIFIED — T-A2 |
| SR-TD1-09: `getThrowableProxy() == null` strict | VERIFIED — T-A4 + T-B1 |
| SR-TD1-10: CRLF injection contained | VERIFIED — T-B1[CRLF] |
| SR-TD1-11: ANSI escape injection contained | VERIFIED — T-B1[ANSI] |
| SR-TD1-12: MDC clean + AUDIT zero events | VERIFIED — T-A5 |

## Deviation from Phase 1 Plan

`ACCEPTABLE_PARSE_EXCEPTIONS` extended from 3 to 5 entries: `JsonEOFException` and `JsonMappingException` added during Round 2. T-B1 uses truncated JSON (missing closing brace) which causes Jackson to throw `JsonEOFException` — not in the original 3-entry set. The extension is documentation of what the production log can emit under the full range of malformed inputs; it is non-controversial and makes the allowlist truthful.

## Worktree

Final implementation state: `worktree-agent-a04216e2a304b97ec`

## User Approval

Date: 2026-04-28
Approval message (verbatim): "approve"

## References

- Phase 1 planning: `docs/decisions/2026-04-28-planning-td1-json-parse-exception.md`
- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (TD-1 origin)
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
