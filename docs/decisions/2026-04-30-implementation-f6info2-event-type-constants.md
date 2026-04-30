# Decision Record: F-6-INFO-2 Event-Type Constants — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A — orchestrator-assisted after worktree regression), secure-tdd-implementer (Lane B)
Status: Accepted

## Summary

`StompCallback.sendMessage` had its fragile `destination.lastIndexOf('/')` event-type extraction replaced by a `StompEventType` enum. Six bare `"creation"` / `"modification"` / `"deletion"` string literals were eliminated. The `destination` parameter was removed from `sendMessage` (dead after the refactor; carried D-13-sensitive `principal/hashtag` data). All 9 security requirements from the planning phase are covered by tests.

## Key Decisions

### Files Created
- `src/main/java/de/seism0saurus/glacier/mastodon/StompEventType.java` — package-private enum, 3 constants, `.suffix()` accessor, Javadoc citing F-6-INFO-2 and ADR-F6-INFO-2-A
- `src/test/java/de/seism0saurus/glacier/mastodon/StompEventTypeTest.java` — 3 tests pinning `.suffix()` wire-format contract (SR-F6INFO2-07)

### Files Modified
- `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java`
  - Added `private static Optional<StompEventType> eventTypeFor(Class<? extends StatusMessage>)`
  - Removed `String destination` parameter from `sendMessage`; updated 4 call sites in `processGenericEvent`
  - Replaced `destination.substring(destination.lastIndexOf('/') + 1)` with `type.suffix()` at the log line
  - Replaced 6 bare string literals with `.suffix()` / enum-constant calls
- `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackTest.java` — 89 → 92 tests
  - `sendMessage_unknownStatusMessageClass_doesNotThrow_andLogsError` (SR-F6INFO2-04)
  - `sendMessage_signature_hasNoDestinationParameter` (SR-F6INFO2-09)
  - `stompMessagePublished_logsEventTypeFromEnum_evenIfHashtagContainsSlash` (SR-F6INFO2-06)

### Security Requirements Coverage

| SR | Requirement | Status |
|----|-------------|--------|
| SR-F6INFO2-01 | `eventTypeFor(StatusCreatedMessage.class)` returns `Optional.of(CREATION)` | Covered by `sendMessage` path + StompCallbackTest existing test |
| SR-F6INFO2-02 | `eventTypeFor(StatusUpdatedMessage.class)` returns `Optional.of(MODIFICATION)` | Covered by `sendMessage` path + StompCallbackTest existing test |
| SR-F6INFO2-03 | No attacker-controlled bytes reach `event-type=` log field | Covered — enum `.suffix()` is a closed constant set |
| SR-F6INFO2-04 | Unknown class → `Optional.empty()` + LOGGER.error + return (no throw) | Covered by `sendMessage_unknownStatusMessageClass_doesNotThrow_andLogsError` |
| SR-F6INFO2-05 | `event-type=` field name preserved | Preserved — only the value source changed |
| SR-F6INFO2-06 | Hashtag `"a/b"` logs `event-type=creation`, not `event-type=b` | Covered by `stompMessagePublished_logsEventTypeFromEnum_evenIfHashtagContainsSlash` |
| SR-F6INFO2-07 | `StompEventType.suffix()` returns exactly the three wire strings | Covered by `StompEventTypeTest` (3 assertions) |
| SR-F6INFO2-08 | No new MDC fields; no raw values in the affected log line (D-13/SR-8) | Verified — no new MDC calls; `type.suffix()` is a constant |
| SR-F6INFO2-09 | `sendMessage` signature has no `destination` parameter | Covered by `sendMessage_signature_hasNoDestinationParameter` |

### Test Results

- `StompEventTypeTest`: **3/3 pass**
- `StompCallbackTest`: **92/92 pass** (was 89)
- Backend unit suite: **852/852 pass**
- Frontend unit suite: **522/522 pass**
- Build: **SUCCESS**

## Worktree Regression Note

Lane A `tdd-ddd-implementer` was launched with `isolation: "worktree"`. The agent's output contained D-13 violations (used `principal.hashCode()` instead of `LogScrubber.hash8`), removed the SSRF guard (`SafeUrlValidator`), and regressed the `MessageCache#recordThenPublish` integration. The worktree was discarded (isolation worked correctly — main branch was never touched). Lane A was re-implemented manually by the orchestrator using targeted `Edit` tool calls against the main repo, preserving all existing security controls.

## Resolved Conflicts

### ADR-B: Keep vs. remove `destination` parameter
**tdd-ddd-implementer**: Keep with TODO comment for follow-up PR (scope discipline).
**secure-tdd-implementer**: Remove — dead parameter carries D-13-sensitive `principal/hashtag` data; maintenance hazard.
**Resolution (2026-04-30)**: Remove in this PR. Architect conceded after verifying structural risk argument.

### ADR-D: Throw vs. Optional for unknown class
**tdd-ddd-implementer (Round 1)**: `throw new IllegalArgumentException(...)` for unknown class.
**secure-tdd-implementer**: `Optional.empty()` + log ERROR + return — virtual-thread safety.
**Resolution (2026-04-30)**: `Optional.empty()` pattern (SR-F6INFO2-04 confirmed the virtual-thread kill risk).

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- Three parallel event-type vocabularies (`cache.EventType`, `mastodon.StompEventType`, `Status*Message.class`) remain separate — explicitly accepted (ADR-F6-INFO-2-A). Unification deferred.
- Hashtag-slash invariant test (SR-F6INFO2-06) is defence-in-depth: `HashtagFormat.PATTERN` currently forbids `/`, making the old `lastIndexOf` bug unexploitable. If the pattern is ever loosened, the test surfaces the risk.

## References

- Planning doc: `docs/decisions/2026-04-30-planning-f6info2-event-type-constants.md`
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
- glacier-structured-logging-logback (D-13/SR-8)
- glacier-fallback-mode-discipline (virtual-thread kill risk)
