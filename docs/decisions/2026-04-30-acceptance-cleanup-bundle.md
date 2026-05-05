# Decision Record: TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

The TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle closes three deferred follow-up items from the 2026-04-30 acceptance round. All five security requirements are satisfied by tests. No Critical, High, Medium, or Low findings were raised during acceptance. No fix cycles. The implementation is approved.

## Acceptance Disposition: PASSED

## Findings

### All five SR requirements satisfied

| SR | Status | Key test(s) |
|----|--------|-------------|
| SR-TD5-FU-01 | PASS | T7c canary at `RawWallIdLogHygieneTest.java:803` — asserts `getFormattedMessage()` and `getArgumentArray()` contain no `"class fully.qualified.Name"` shape; `StompCallback.java:220` confirmed `getSimpleName()` |
| SR-TD5-FU-02 | PASS | T7d canary at `RawWallIdLogHygieneTest.java:880` — same dual assertion for outer-switch default at `StompCallback.java:228` |
| SR-TD5-FU-03 | PASS | T7-gate-FU at `StompCallbackTest.java:3741` — non-vacuous (filter widened to `LOGGER.` OR `logEvent(`); `ProtectionDomain` path; allowlists `.getSimpleName()`; cites SR-TD5-FU-03/CWE-117/D-13/SR-8 |
| SR-TD5-FU-04 | PASS (risk accepted) | `LogScrubberTest.java:655,667` — comment `// 2_148_532_224L` and description `xfo-totallen=2148532224` corrected; runtime assertion unchanged |
| SR-F6INFO2-R1-01 | PASS | `StompCallbackTest.java:3907` `R1_gate_privateEventMethod_mustNotDeclareStringDestinationParameter` (×4 method names, typo preserved); `StompCallbackTest.java:3942` `baseDestination_isRemovedFromOnEvent`; cites SR-F6INFO2-R1-01/CWE-532/SI-11/AU-9 |

### CWE-117 / D-13 / SR-8 boundary verification

Peer-controlled bytes blocked at three independent layers:

1. **Behavioural canaries** (T7c/T7d): confirm `getFormattedMessage()` and `getArgumentArray()` carry no `Class.toString()` shape for both default-branch call sites (inner StreamEvent switch and outer MastodonApiEvent switch).
2. **Structural gate** (T7-gate-FU): source-level ban on bare `.getClass())` without `.getSimpleName()` on any LOGGER/logEvent line; catches regressions before runtime.
3. **Complete log-call scan** (security-auditor Round 1): all 30+ LOGGER/logEvent/AUDIT calls in `StompCallback.java` verified clean — every argument is either a `LogScrubber` helper output, a JVM-bounded `getSimpleName()`, a numeric primitive, or a constant string.

### SR-F6INFO2-R1-01 — `destination` / `baseDestination` elimination

The `/{wallId}/{hashtag}` composite path bytes (D-13-sensitive) no longer propagate past `onEvent` into any private event-processing method. Removal confirmed:
- `processStatusCreatedEvent(final Status status)` — no `destination`
- `processStatusEditedEvent(final Status status)` — no `destination`
- `procesStatusDeletedEvent(final String statusId)` — no `destination` (typo in name preserved)
- `processGenericEvent(GenericMessage genericMessage)` — no `destination`
- `String baseDestination = ...` — removed from `onEvent`

### T7c RED-state verification

T7c caught the real `ParsedStreamEvent$UnknownType` Bigbone type producing `"class social.bigbone.api.entity.streaming.ParsedStreamEvent$UnknownType"` — confirming the default branch at line 220 is reachable in production (Bigbone emits this type for unrecognised streaming events).

## Informational / Non-blocking findings

| ID | Finding | Severity | Disposition |
|----|---------|----------|-------------|
| SA-1 | T7-gate-FU regex `\.getClass\(\)\)` misses `.getClass().toString()` and `.getClass(),` arg-position patterns. No current bypass in source; T7c/T7d behavioural canaries provide defense-in-depth backstop. | Informational | Accepted — consider widening to `\.getClass\(\)\s*[^.]` in a future hardening pass |
| SA-2 | R-1-gate uses `System.getProperty("user.dir")`; T7-gate-FU uses `ProtectionDomain.getCodeSource()` — minor inconsistency. Both function correctly under standard Surefire execution. | Informational | Accepted — optional unification to `ProtectionDomain` for portability |
| SA-3 | `ImageProxyUrlBuilderVerifyFuzzTest.anyMutationOfValidTokenReturnsEmpty` intermittent flake (jqwik mutation index ~167). Pre-existing, unrelated to this bundle. | Pre-existing / out of scope | Deferred — tracked separately; fix in share-link / image-proxy domain |

## Final Test Results

| Suite | Count | Result |
|-------|-------|--------|
| `RawWallIdLogHygieneTest` | 15 (+2 T7c, T7d) | PASS |
| `StompCallbackTest` | 150 (+6: T7-gate-FU, R-1-gate ×4, baseDestination test) | PASS |
| `LogScrubberTest` | 63 (unchanged) | PASS |
| Backend Surefire total | 947 | PASS |
| Jacoco instruction ≥ 45% | — | PASS |
| Jacoco branch ≥ 35% | — | PASS |

## Commits

| Hash | Message | Shape |
|------|---------|-------|
| `65a6b72` | `test(security): TD-5-FU-1 add T7c/T7d canaries for unknown StreamEvent/event default branches (RED)` | RED |
| `2f11053` | `fix(security): TD-5-FU-1 replace getClass() with getSimpleName() at lines 218 and 223 of StompCallback` | GREEN |
| `f42f4fd` | `test(security): TD-5-FU-1 T7-gate-FU structural gate banning bare .getClass()) on logEvent lines (SR-TD5-FU-03)` | Additive gate |
| `d0ebe26` | `refactor(security): F-6-INFO-2 R-1 remove unused destination from StompCallback private methods` | Refactor + gate |
| `b268b37` | `docs(test): TD-5-FU-2 fix stale overflow-value comment and description in LogScrubberTest (2_147_483_648L → 2_148_532_224L)` | Doc fix |

## Resolved Conflicts

None — no `## ⚡ CONFLICT:` markers raised in any Phase 3 round.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Closed Follow-Up Items

| Item | Source | Status |
|------|--------|--------|
| TD-5-FU-1 | `docs/decisions/2026-04-30-acceptance-td5-cwe117-cleanup.md` | **Closed** — fixed at commits `65a6b72`, `2f11053`, `f42f4fd` |
| TD-5-FU-2 | same doc | **Closed** — fixed at commit `b268b37` |
| F-6-INFO-2 R-1 | `docs/decisions/2026-04-30-acceptance-f6info2-event-type-constants.md` | **Closed** — fixed at commit `d0ebe26` |

## Open Risks

- **SA-1 (T7-gate-FU regex narrowness)**: Behavioural canaries T7c/T7d provide defense-in-depth; no current bypass in source. Future hardening suggested but not required.
- **SA-2 (path-resolution inconsistency)**: Cosmetic; no security impact.
- **SA-3 (jqwik flake)**: Pre-existing; out of this bundle's scope.

## References

- [TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle — Planning](2026-04-30-planning-cleanup-bundle.md)
- [TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle — Implementation](2026-04-30-implementation-cleanup-bundle.md)
- [TD-5 CWE-117 Cleanup — Acceptance](2026-04-30-acceptance-td5-cwe117-cleanup.md) (TD-5-FU-1, TD-5-FU-2 deferral source)
- [F-6-INFO-2 Event-Type Constants — Acceptance](2026-04-30-acceptance-f6info2-event-type-constants.md) (R-1 deferral source)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [OWASP A09:2021 — Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- glacier-structured-logging-logback (D-13/SR-8)
