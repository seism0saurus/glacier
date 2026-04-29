# Decision Record: TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing — Acceptance

Date: 2026-04-29
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **PASSED**

## Summary

Phase 3 acceptance audit validated all 15 security requirements for TD-2. Three production lines in `StompCallback.processTechnicalEvent` no longer log peer-controlled bytes: `failure.getError().getMessage()` replaced with `getSimpleName()`, and `closing.toString()`/`closed.toString()` (which embed RFC 6455 §5.5.1 `reason` fields) replaced with numeric `getCode()`. TDD-verified by 8 shape/restart tests and a 7-variant injection canary. No fix cycles. 843 unit + 173 integration = 1016 total tests, 0 failures.

## Acceptance Disposition: PASSED

All requirements from Phase 1 planning (`docs/decisions/2026-04-29-planning-td2-technical-failure.md`) are met. No Critical or High findings remain open.

## Security Requirements — Final Verification

| SR | Requirement | Status |
|----|-------------|--------|
| SR-TD2-01 | No peer-controlled byte in any log line from Failure/Closing/Closed branches | VERIFIED — T-B1 all 7 variants |
| SR-TD2-02 | Failure log at INFO via `logEvent`; structured `exception=<SimpleName>` | VERIFIED — T-A1, T-A3 |
| SR-TD2-03 | Legacy `"The error is: %s"` format fully removed | VERIFIED — T-A3, grep gate 0 hits |
| SR-TD2-04 | Assertions check `getArgumentArray()` AND `getThrowableProxy()` | VERIFIED — T-A4, T-B1 |
| SR-TD2-05 | TD1-INV-1 confirmed (not extended) for TD-2 scope | VERIFIED — planning doc, ADR-TD2-02 |
| SR-TD2-06 | Mode-discipline neutrality — live/fallback/killswitch/insecure no control-flow change | VERIFIED — inspection, no mode branch added |
| SR-TD2-07 | Existing F-6 + TD-1 canaries not regressed | VERIFIED — full 1016-test suite |
| SR-TD2-08 | `getSimpleName()` drawn from bounded `EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS` test parameter | VERIFIED — T-A2, T-B1 |
| SR-TD2-09 | `getThrowableProxy() == null` strict — no Throwable SLF4J arg | VERIFIED — T-A4, T-B1 |
| SR-TD2-10 | CRLF injection contained | VERIFIED — T-B1[CRLF] |
| SR-TD2-11 | ANSI escape injection contained | VERIFIED — T-B1[ANSI] |
| SR-TD2-12 | MDC clean + AUDIT zero events after Failure/Closing/Closed | VERIFIED — T-A5, T-A5b |
| SR-TD2-13 | Restart calls still fire after fix | VERIFIED — T-A6 |
| SR-TD2-14 | Closing/Closed log `code=<int>` only; `reason` fully absent | VERIFIED — T-A4b, T-A5b, T-B1 |
| SR-TD2-15 | Sibling TD-1 + F-6 canaries not regressed | VERIFIED — full suite 1016/0 |

## Grep Gates

```
git grep "failure.getError().getMessage()" src/main/  →  0 hits
git grep "The error is:" src/main/                    →  0 hits
git grep "\.reason" src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java  →  0 hits
```

## Final Test Counts

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 843 | 0 |
| Java integration (Failsafe) | 173 | 0 |
| **Total** | **1016** | **0** |

Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Finding Dispositions

| ID | Description | Severity | Disposition |
|----|-------------|----------|-------------|
| TD-2 | `processTechnicalEvent` logged peer-controlled exception message and close-reason strings | High (CWE-117) | FIXED |
| F-1 | `TechnicalEvent.Open` (line 525) and `default` (line 536) branches reviewed; low exposure | Informational | Deferred to TD-4 |
| F-2 | `normaliseEditedAt` passes raw `raw` value into `LOGGER.warn` (line 581) — pre-existing | Low | Deferred as TD-3 |
| F-3 | xFrameOptions literal string comparison — pre-existing, unrelated | Informational | Accepted |

## Observations Accepted

**O-1** (documentation precision): T-A4 is labeled `[red→green anchor]` in the implementation doc, but `getThrowableProxy() == null` was already true before the fix (old code used `String.formatted()`, not an SLF4J Throwable arg). The genuine red→green anchors are T-A3, T-A4b, T-A5b, T-B1. This is a labeling note only — the assertion has independent defensive value as a regression guard against a future refactor that passes `failure.getError()` as an SLF4J Throwable argument. No fix required.

## User Approval

Date: 2026-04-29
Approval message (verbatim): "approve"

## References

- Phase 1 planning: `docs/decisions/2026-04-29-planning-td2-technical-failure.md`
- Phase 2 implementation: `docs/decisions/2026-04-29-implementation-td2-technical-failure.md`
- TD-1 acceptance: `docs/decisions/2026-04-28-acceptance-td1-json-parse-exception.md` (sibling)
- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (F-6-FU-2 origin)
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
- RFC 6455 §5.5.1 (WebSocket Close frame reason field)
- NIST SP 800-53 SI-11: Error Handling
