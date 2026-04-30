# Decision Record: F-6-INFO-2 Event-Type Constants — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1)
Status: Accepted

## Summary

`StompCallback.sendMessage` at line 320 extracts the STOMP event-type suffix from the destination path via `destination.substring(destination.lastIndexOf('/') + 1)`. This is redundant (the type is already known from `statusMessageClass`, as line 315 proves), fragile (a hashtag containing `/` would return the wrong segment), and leaves six bare string literals (`"creation"`, `"modification"`, `"deletion"`) across the file without constants. This change introduces a `StompEventType` enum, replaces the `lastIndexOf` extraction, removes all bare literals, and removes the now-dead `destination` parameter from `sendMessage`.

## Key Decisions

### ADR-F6-INFO-2-A: Use a package-private `StompEventType` enum, not three `String` constants
**Decision**: Introduce `StompEventType { CREATION("creation"), MODIFICATION("modification"), DELETION("deletion") }` with a `.suffix()` accessor returning the wire-format string.
**Rationale**: The three values form a closed, exhaustive set. An enum documents this, enforces exhaustiveness in the mapper, and prevents typos at call sites. Three `private static final String` constants would meet the literal request but leave the `Class<?> → String` ternary open-coded.
**Alternatives considered**: Three string constants (rejected — weaker type safety); reuse `webservice.cache.EventType` (rejected — different bounded contexts, incidental shape similarity).
**Source**: ddd-tdd-architect Round 1; accepted by secure-feature-planner Round 1.

### ADR-F6-INFO-2-B: Remove `destination` parameter from `sendMessage`
**Decision**: Drop the `destination` parameter from `sendMessage`'s signature and update the four call sites in `processGenericEvent`. This replaces the original Round 1 position (keep with TODO).
**Rationale**: After the `lastIndexOf` is replaced, `destination` has no remaining consumer inside `sendMessage`. The parameter carries `principal + "/" + hashtag` — D-13-sensitive data. Keeping a dead parameter that holds raw-wallId data creates a regression vector (future debug log accidentally prints raw principal).
**Alternatives considered**: Keep with TODO comment (architect Round 1 — rejected in Round 2 after conceding to security planner's structural risk argument).
**Source**: secure-feature-planner Round 1 dispute; accepted by ddd-tdd-architect Round 2.

### ADR-F6-INFO-2-C: `StompEventType` is package-private
**Decision**: Visibility `package-private` (no `public` modifier). The `.suffix()` string is the cross-context contract, not the enum type itself.
**Rationale**: Exporting `StompEventType` would invite cross-context coupling (`ShareViewStompRelay`, `MessageCacheImpl`) for incidental shape similarity. The relay already accepts `String eventType`; no API break is needed.
**Source**: ddd-tdd-architect Round 1; accepted by secure-feature-planner.

### ADR-F6-INFO-2-D: `eventTypeFor` returns `Optional<StompEventType>`, not throws
**Decision**: `private static Optional<StompEventType> eventTypeFor(Class<? extends StatusMessage> clazz)` returns `Optional.empty()` for unknown classes; callers log ERROR and return early.
**Rationale**: `sendMessage` is called from `processGenericEvent` → `onEvent` on the Bigbone virtual thread. The only catch in the chain is `catch (JsonProcessingException)`. An unchecked `IllegalArgumentException` would escape and risk killing the streaming socket, pushing the wall into fallback mode without a clean trigger.
**Alternatives considered**: Throw `IllegalArgumentException` (architect Round 1 — rejected after verifying the virtual-thread call chain).
**Source**: secure-feature-planner Round 1 (SR-F6INFO2-04); accepted by ddd-tdd-architect Round 2.

## Security Requirements

| SR | Requirement |
|----|-------------|
| SR-F6INFO2-01 | `eventTypeFor(StatusCreatedMessage.class)` returns `Optional.of(CREATION)` |
| SR-F6INFO2-02 | `eventTypeFor(StatusUpdatedMessage.class)` returns `Optional.of(MODIFICATION)` |
| SR-F6INFO2-03 | No attacker-controlled bytes reach `stomp.message.published` log line via eventType field |
| SR-F6INFO2-04 | Unknown `statusMessageClass` → `Optional.empty()` + LOGGER.error + return (no throw, no virtual-thread kill) |
| SR-F6INFO2-05 | `event-type=` field name preserved (existing SIEM/log queries unchanged) |
| SR-F6INFO2-06 | Hashtag `"a/b"` cross-module invariant: logs `event-type=creation`, not `event-type=b` |
| SR-F6INFO2-07 | `StompEventType.suffix()` returns exactly `"creation"` / `"modification"` / `"deletion"` |
| SR-F6INFO2-08 | No new MDC fields; no raw values in the affected log line (D-13/SR-8) |
| SR-F6INFO2-09 | `sendMessage` signature no longer has a `destination` parameter (structural test) |

## Test Plan

### Lane A — `tdd-ddd-implementer`

**New file**: `src/main/java/de/seism0saurus/glacier/mastodon/StompEventType.java`
- Package-private enum with three values and `.suffix()` accessor
- Javadoc explains separation from `cache.EventType`, cites F-6-INFO-2

**New file**: `src/test/java/de/seism0saurus/glacier/mastodon/StompEventTypeTest.java`
- 3 assertions pinning `.suffix()` wire-format contract (SR-F6INFO2-07)

**`StompCallback.java`** edits:
- Add `private static Optional<StompEventType> eventTypeFor(Class<? extends StatusMessage>)`
- Remove `destination` parameter from `sendMessage`; update call sites at lines 229, 231
- Replace `lastIndexOf` at line 320 with `StompEventType type = eventTypeFor(...).orElse(...)` pattern (Optional.empty → log ERROR + return)
- Replace literals `"creation"` / `"modification"` / `"deletion"` at lines 315, 451, 493, 511 with `.suffix()` calls
- Remove destination-suffix concatenations at lines 229, 231 (`destination + "/creation"` etc.)

**`StompCallbackTest.java`** additions:
- `stompMessagePublished_logsEventTypeFromEnum_evenIfHashtagContainsSlash` (SR-F6INFO2-06, cross-module invariant)
- Existing `sendMessage_logsStructuredPublishedEvent_notRawDestination` (line ~2234) must continue to pass unchanged

### Lane B — `secure-tdd-implementer`

- Verify `eventTypeFor` returns `Optional.empty()` for unknown class (SR-F6INFO2-04)
- Add `sendMessage_unknownStatusMessageClass_doesNotThrow_andLogsError` test
- Add `sendMessage_signature_hasNoDestinationParameter` structural test (SR-F6INFO2-09)
- Red-anchor: confirm that reverting `eventTypeFor` to the old `lastIndexOf` pattern causes the invariant test (SR-F6INFO2-06) to fail
- Run `./mvnw verify`

### Lane order
Lane A first (production + primary tests), Lane B (boundary + structural tests), then joint Round 2.

## Phase 2 Lane Partition

| Lane | Owner | Files |
|------|-------|-------|
| Lane A | `tdd-ddd-implementer` | `StompEventType.java` (new), `StompCallback.java` (refactor), `StompEventTypeTest.java` (new), `StompCallbackTest.java` (invariant test) |
| Lane B | `secure-tdd-implementer` | `StompCallbackTest.java` (unknown-class + structural tests only, no production code) |

## Resolved Conflicts

### CONFLICT: ADR-B — keep vs. remove `destination` parameter
**ddd-tdd-architect Round 1**: Keep `destination` in `sendMessage` with TODO comment for follow-up PR (scope discipline).
**secure-feature-planner Round 1**: Remove `destination` — dead parameter carries D-13-sensitive data; maintenance hazard.
**Resolution (2026-04-30)**: Both agents agree: remove in this PR. Architect conceded in Round 2 after verifying structural risk.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- Three parallel event-type vocabularies (`cache.EventType`, `mastodon.StompEventType`, `Status*Message.class`) remain separate — explicitly accepted (ADR-F6-INFO-2-A). Unification deferred.
- `HashtagFormat.PATTERN = ^[\p{L}\p{N}_]{1,50}$` currently forbids `/` in hashtags, so the cross-module invariant test (SR-F6INFO2-06) is defence-in-depth. If the pattern is ever loosened, the test will surface the risk.

## References

- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (INFO-2 origin, finding F-2b deferred)
- glacier-fallback-mode-discipline (virtual-thread kill risk, ADR-D rationale)
- glacier-structured-logging-logback (D-13/SR-8, log-field stability)
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
