# Decision Record: TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: secure-tdd-implementer (commits 1–3), tdd-ddd-implementer (commits 4–5), cross-review by both
Status: Accepted

## Summary

Five commits implement the three deferred follow-up items. All security requirements (SR-TD5-FU-01/02/03/04, SR-F6INFO2-R1-01) are covered. `./mvnw verify -DskipIntegrationTests` = BUILD SUCCESS on the committed state; Jacoco thresholds met. No frontend or infrastructure changes.

## Commits

| # | Hash | Message | Lane | Key SR |
|---|------|---------|------|--------|
| 1 | `65a6b72` | `test(security): TD-5-FU-1 add T7c/T7d canaries for unknown StreamEvent/event default branches (RED)` | secure-tdd | SR-TD5-FU-01/02 |
| 2 | `2f11053` | `fix(security): TD-5-FU-1 replace getClass() with getSimpleName() at lines 218 and 223 of StompCallback` | secure-tdd | SR-TD5-FU-01/02 |
| 3 | `f42f4fd` | `test(security): TD-5-FU-1 T7-gate-FU structural gate banning bare .getClass()) on logEvent lines (SR-TD5-FU-03)` | secure-tdd | SR-TD5-FU-03 |
| 4 | `d0ebe26` | `refactor(security): F-6-INFO-2 R-1 remove unused destination from StompCallback private methods` | tdd-ddd | SR-F6INFO2-R1-01 |
| 5 | `b268b37` | `docs(test): TD-5-FU-2 fix stale overflow-value comment and description in LogScrubberTest (2_147_483_648L → 2_148_532_224L)` | tdd-ddd | SR-TD5-FU-04 |

## What Was Implemented

### Item 1 — TD-5-FU-1 (commits 1–3)

**RED canaries** (`RawWallIdLogHygieneTest.java`):
- **T7c**: drives `ParsedStreamEvent.UnknownType` (a real Bigbone type) through `onEvent` to the inner `switch` default at line 218. Asserts `getFormattedMessage()` does NOT match `".*class .*\\..*"` and `getArgumentArray()` carries no `Class.toString()`-shaped value. Caught the real violation: `"class social.bigbone.api.entity.streaming.ParsedStreamEvent$UnknownType"`.
- **T7d**: drives a `mock(MastodonApiEvent.class)` through the outer `switch` default at line 223. Same assertion shape. Caught: `"class social.bigbone.api.entity.streaming.MastodonApiEvent$MockitoMock$qkdJQo9B"`.
- `TestLogAppender` promoted to `@BeforeEach`/`@AfterEach`, scoped to `StompCallback.class` logger (not AUDIT).

**GREEN fix** (`StompCallback.java`):
- Line 218: `streamEvent.getEvent().getClass()` → `streamEvent.getEvent().getClass().getSimpleName()`
- Line 223: `event.getClass()` → `event.getClass().getSimpleName()`

**Structural gate** (`StompCallbackTest.java`, T7-gate-FU):
- Scans every `LOGGER.`- or `logEvent(`-bearing line in `StompCallback.java` via ProtectionDomain path resolution (consistent with T7-gate / T6b-gate precedent in this codebase).
- Triggers on `.getClass())` present AND `.getSimpleName()` absent — allowlists the safe pattern at line 207.
- Failure message cites SR-TD5-FU-03 / CWE-117 / D-13 / SR-8.
- Destructively verified: temporarily reverting line 218 caused the gate to fire citing line 221.

### Item 2 — TD-5-FU-2 (commit 5)

`LogScrubberTest.java`:
- Comment at line ~655: `// 2_147_483_648L` → `// 2_148_532_224L`
- Assertion description at line ~667: `"xfo-totallen=2147483648"` → `"xfo-totallen=2148532224"`
- Runtime assertion logic unchanged (already computed from variable).

### Item 3 — F-6-INFO-2 R-1 (commit 4)

**Production changes** (`StompCallback.java`):

| Method | Before | After |
|--------|--------|-------|
| `processStatusCreatedEvent` | `(final Status status, final String destination)` | `(final Status status)` |
| `processStatusEditedEvent` | `(final Status status, final String destination)` | `(final Status status)` |
| `procesStatusDeletedEvent` | `(final String statusId, final String destination)` | `(final String statusId)` |
| `processGenericEvent` | `(GenericMessage genericMessage, String destination)` | `(GenericMessage genericMessage)` |

- 5 call sites updated in `onEvent` and `processGenericEvent`.
- `String baseDestination = "/topic/hashtags/" + principal + "/" + hashtag;` removed from `onEvent` (confirmed dead after signature cleanup).
- Javadoc swept on all four methods: `@param destination` dropped; no `{@code destination}` / `{@see}` / inline snippet references remain.

**Structural gates** (`StompCallbackTest.java`):
- `R-1-gate`: `@ParameterizedTest` over 4 method names (including typo `procesStatusDeletedEvent`). Asserts no signature line contains `destination`. Cites SR-F6INFO2-R1-01 + CWE-532 + SI-11 + AU-9.
- `baseDestination_isRemovedFromOnEvent`: asserts `String baseDestination` is absent from `onEvent` body (skipping comment/Javadoc lines). Same citation.

## Test Results

| Suite | Count | Delta | Result |
|-------|-------|-------|--------|
| `RawWallIdLogHygieneTest` | 15 | +2 (T7c, T7d) | PASS |
| `StompCallbackTest` | 150 | +6 (T7-gate-FU, R-1-gate ×4, baseDestination test) | PASS |
| `LogScrubberTest` | 63 | 0 | PASS |
| Backend Surefire suite | ~928 | — | PASS |
| Jacoco instruction | ≥ 45% | — | PASS |
| Jacoco branch | ≥ 35% | — | PASS |

## Deviations from Phase 1 Plan

- **T7-gate-FU path resolution**: Phase 1 plan specified `System.getProperty("user.dir")`; implementer used ProtectionDomain path resolution. Both agents confirmed ProtectionDomain is the more established codebase pattern (T7-gate, T6b-gate, SR-TD3-10-gate all use it). No functional difference; accepted in cross-review.

## Resolved Conflicts

### Build failure from pre-existing WIP
**secure-tdd-implementer** cross-review flagged BUILD FAILURE (24 test failures) from uncommitted pre-existing WIP (OWASP matrix completion feature, opt-in gate, security test suite). The 5 pipeline commits themselves were clean.
**Resolution (2026-04-30)**: Stashed WIP, ran `./mvnw verify -DskipIntegrationTests` on committed state → BUILD SUCCESS. Restored stash. Pipeline commits confirmed independently correct.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

None — both cross-reviews passed on the pipeline commits. Pre-existing WIP (OWASP matrix completion) is a separate in-progress feature; its null-stream NPE finding is tracked in that feature's test suite (`StompCallbackHostileResponseTest.java`).

## References

- [TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle — Planning](2026-04-30-planning-cleanup-bundle.md)
- [TD-5 CWE-117 Cleanup — Acceptance](2026-04-30-acceptance-td5-cwe117-cleanup.md)
- [F-6-INFO-2 Event-Type Constants — Acceptance](2026-04-30-acceptance-f6info2-event-type-constants.md)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
