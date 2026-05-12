# Decision Record: TD-5 CWE-117 Cleanup — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + re-verify + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

TD-5 closes three remaining CWE-117 / D-13 / SR-8 violations from the TD-4 informational findings: `event.toString()` and `open.toString()` in `StompCallback.processTechnicalEvent`, and `int totalLen` overflow risk in `LogScrubber.xfoSummary`. All 6 security requirements are covered by tests. One Medium finding (M-1: T7-gate vacuity) was caught in Phase 3 and fixed before sign-off. No Critical or High findings. The implementation is approved for merge.

## Acceptance Disposition: PASSED

## Findings

### All 6 SR-TD5 requirements satisfied

| SR | Status | Key test(s) |
|----|--------|-------------|
| SR-TD5-01 | PASS | T7a (canary), T7a-struct (8-row injection fuzz) — both `getFormattedMessage()` and `getArgumentArray()` checked at `StompCallbackTest.java:3258-3302` and `:3468-3524` |
| SR-TD5-02 | PASS | T7b (canary), T7b-struct (8-row injection fuzz) at `:3335-3379` and `:3559-3616` |
| SR-TD5-03 | PASS | LST-T5-1 (overflow canary: 2049 × 1 MiB flyweight, expected 2_148_532_224L) + LST-T5-2 (jqwik 1000 tries) at `LogScrubberTest.java:649-677` and `:699-725` |
| SR-TD5-04 | PASS (after M-1 fix) | T7-gate at `StompCallbackTest.java:3642-3681`; filter widened to `LOGGER. || logEvent(` at commit `5e90286`; verified by destructive test |
| SR-TD5-05 | PASS | T7a positive-shape `:3297-3301`, T7a-struct `:3518-3523` — `"class="` present in default-branch message |
| SR-TD5-06 | PASS (stronger than planned) | T7b-struct `:3610-3615` asserts full prefix `"got an Open event (class="` rather than just `"class="` |

### CWE-117 boundary verification

Peer-controlled bytes reaching the log encoder are blocked at three independent layers:

1. **Behavioural canaries** (T7a/T7b): single canonical canary string confirms no raw `toString()` bytes reach `getFormattedMessage()` or `getArgumentArray()`.
2. **Injection fuzz** (T7a-struct/T7b-struct, 8 adversarial rows each): ASCII canary, CRLF, ANSI ESC, NUL, U+2028, U+202E, U+FEFF, U+2029 — all confirm neither channel carries adversarial content.
3. **Structural gate** (T7-gate, post-M-1): scans every physical line containing `LOGGER.` or `logEvent(` in `StompCallback.java`; asserts no `.formatted(event)` or `.formatted(open)` substring present.

### Finding M-1 — T7-gate vacuity (Medium, Fixed)

**Severity**: Medium
**Finding**: The original T7-gate at commit `58771e6` filtered only `LOGGER.`-bearing lines. The actual production fix sites at `StompCallback.java:545,555` use the private `logEvent(...)` wrapper — they contain no `LOGGER.` token. The gate was vacuously passing and would not have caught a regression at the fix sites.
**Fix**: Commit `5e90286` widened the filter to `normalised.contains("LOGGER.") || normalised.contains("logEvent(")`. Regression-test verified: temporarily reverting `:555` to `.formatted(event)` caused the gate to fail with the correct message citing line 555; reverting back caused it to pass.
**Disposition**: Fixed and closed.

### Deferred findings (non-blocking)

| ID | Finding | Severity | Disposition |
|----|---------|----------|-------------|
| TD-5-FU-1 | `StompCallback.java:218,223` use `.getClass()` (Class.toString) rather than `.getSimpleName()` — pattern inconsistency; no CWE-117 surface | Low | Deferred — JVM-controlled string, not peer-influenced; cosmetic; fix on next touch of those lines |
| TD-5-FU-2 | `LogScrubberTest.java` comment cites overflow value `2_147_483_648` (correct value is `2_148_532_224` = 2049 × 1_048_576) — runtime assertion uses computed value and is correct | Informational | Deferred — documentation only; no behavioural impact |

## Commits

| Hash | Message | Shape |
|------|---------|-------|
| `e84b2e0` | `test(security): TD-5 add T7a/T7b/LST-T5-1/LST-T5-2 canary tests (RED)` | RED |
| `f33f2bf` | `fix(security): TD-5 replace event.toString()/open.toString() with getSimpleName() and widen xfoSummary totalLen to long (CWE-117, D-13/SR-8)` | GREEN |
| `58771e6` | `test(security): TD-5 T7a-struct/T7b-struct injection fuzz and T7-gate structural regression gate (SR-TD5-01/02/04)` | Lane B additive |
| `5e90286` | `fix(test): TD-5 T7-gate: widen filter to cover logEvent() wrapper lines (SR-TD5-04 vacuity fix)` | Phase 3 fix |

## Final Test Results

| Suite | Count | Result |
|-------|-------|--------|
| `StompCallbackTest` | 144 (was 125 pre-TD5, +19) | PASS |
| `LogScrubberTest` | 63 (was 61 pre-TD5, +2) | PASS |
| Backend unit suite (Surefire) | ~916 | PASS |
| Jacoco instruction | ≥ 45% threshold | PASS |
| Jacoco branch | ≥ 35% threshold | PASS |

## Resolved Conflicts

None — no `## ⚡ CONFLICT:` markers raised in either round of Phase 3.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Follow-Up Items

| Item | Type | Description |
|------|------|-------------|
| TD-5-FU-1 | Cleanup | Replace `.getClass()` with `.getClass().getSimpleName()` at `StompCallback.java:218,223` on next touch |
| TD-5-FU-2 | Documentation | Fix comment in `LogScrubberTest.java:655` from `2_147_483_648L` to `2_148_532_224L` |

## Open Risks

- **Bigbone snapshot dependency**: `TechnicalEvent.Open` is a `data object` in `2.0.0-SNAPSHOT`. T7-gate now enforces the fix pattern structurally; a future Bigbone snapshot adding fields to `Open` would not regress the fix (T7b-struct would catch a reversion). Pinning to a stable Bigbone release remains a separate follow-up.

## References

- [Planning doc](2026-04-30-planning-td5-cwe117-cleanup.md)
- [Implementation doc](2026-04-30-implementation-td5-cwe117-cleanup.md)
- [TD-4 source (informational findings SA-TD4-I1/I3/I4)](2026-04-30-acceptance-td4-xframeoptions.md)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP A09:2021 — Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- Glacier D-13 / SR-8 structured-logging discipline ([`glacier-structured-logging-logback`](../../.claude/skills/glacier-structured-logging-logback.md) skill)
