# Decision Record: SubscriptionService Split — Planning

Date: 2026-05-08
Phase: Planning
Agents: ddd-tdd-architect (R1 + R2), secure-feature-planner (R1 + R2)
Status: Accepted

## Summary

Split the 846-line `SubscriptionService` into three focused Angular services
(`SubscriptionStateService`, `SubscriptionPersistence`, `SubscriptionStompClient`) plus a thin
facade that preserves the existing public API byte-for-byte. This is deferred item D.3 from the
Comprehensive Quality Review ([Quality Review Planning](2026-05-07-planning-quality-review.md)). The split
surfaces one pre-existing security finding (`hashtag.component.ts:52` reading localStorage
directly) which is fixed as part of this work.

Security verdict: CONDITIONAL PASS — no conflicts, conditions documented as AC-16..20.

---

## Key Decisions

### Bounded contexts and service responsibilities

**Decision**: Three services own three distinct concerns.

| Service | Owns | Key public API |
|---|---|---|
| `SubscriptionPersistence` | `MessageQueue` class (co-located, exported, not injectable), `hashtags` and `messageQueue` localStorage keys, validation call sites | `loadHashtags()`, `saveHashtags()`, `createMessageQueue()` |
| `SubscriptionStateService` | Single `MessageQueue` instance (via `persistence.createMessageQueue()`), three observables (`messageObservable$`, `hasMigrated$`, `settlingHashtags$`), `recentlyTerminated` guard, ingest path | `restoreFromStorageAndEmit()`, `enqueueWallMessage()`, `updateMessage()`, `dequeueById()`, `ingestCacheEntries()`, `isRecentlyTerminated()`, `seedRecentlyTerminated()`, `pruneByHashtag()`, `clearSettlingTimers()` |
| `SubscriptionStompClient` | All RxStomp subscription handles, both ack handlers, 4-step termination sequence, publish side | `attach()`, `subscribeHashtag()`, `unsubscribeHashtag()`, `terminateAll()` |
| `SubscriptionService` (facade) | Preserves full public API for all four consumers unchanged | All existing public methods + observables, delegated one-for-one |

**Rationale**: SRP and bounded-context isolation. The current monolith mixes four concerns (RxJS state,
localStorage, STOMP lifecycle, fallback ingest) through shared private fields, making it impossible to
test one concern without spinning up the others.

**Alternatives considered**: Partial extraction leaving MessageQueue in place — rejected; would leave the
persistence trust boundary split across two files.

**Source**: arch_plan §2, §3; arch_review Part 1.

---

### Dependency direction locked — no back-edges

**Decision**: `SubscriptionStompClient → SubscriptionStateService → SubscriptionPersistence`. No
back-edges. `SubscriptionPersistence` has zero project-internal imports. Enforced by ESLint rule
(see ADR below).

**Rationale**: Three-service splits often regress into a State ↔ StompClient cycle. Locking the graph
at the architectural level prevents this. `SubscriptionStateService` can be unit-tested with no
`RxStompService` mock at all.

**Alternatives considered**: Event-bus pattern (fourth service); StompClient owning state observables —
both rejected (reasons in arch_plan ADR-1).

**Source**: arch_plan ADR-1; arch_review Part 1 SR-SPLIT-02.

---

### `MessageQueue` stays a class, not a service

**Decision**: Co-locate `MessageQueue` with `SubscriptionPersistence` as `export class MessageQueue`.
Owned at runtime by `SubscriptionStateService` via a single `private readonly` field.

**Rationale**: Pure data structure; only one instance per session; no Angular dependencies. Promoting to
`@Injectable` would force injection ceremony in every test that needs a fresh queue (fuzz spec constructs
queues directly with `new MessageQueue(capacity)`).

**Source**: arch_plan ADR-2; arch_review §4.

---

### `hasMigrated$` remains `new Subject<boolean>()` — not `BehaviorSubject`

**Decision**: `_migratedSubject` is copied verbatim from `subscription.service.ts:55–56` into
`subscription-state.service.ts`, including the Javadoc explaining late-subscriber semantics.

**Rationale**: Late subscribers must not receive the cached `true` value (migration banner must not
re-trigger on route changes or re-renders). A `BehaviorSubject` seeded `false` would re-emit on
subscribe, breaking the one-shot semantic (ADR-4).

**Consequences**: AC-10 (plain Subject) and new test SR-SPLIT-08-T7 (late-subscriber assertion).

**Source**: arch_plan §3.2; arch_review Q5; security_final §5.

---

### Ingest path in `SubscriptionStateService`

**Decision**: `ingestCacheEntries(hashtag, entries)` lives in `SubscriptionStateService` (it owns the
queue). `FallbackService` calls `subscriptionService.ingestCacheEntries(...)` (facade) → delegates to
`state.ingestCacheEntries(...)`. Critically: STOMP and HTTP fallback write to the **same** `MessageQueue`
instance.

**Rationale**: Fallback-mode discipline (`glacier-fallback-mode-discipline`) requires live STOMP entries
and fallback HTTP entries to be indistinguishable on the wall. A second queue instance would be a silent
regression. Trust boundary note: cache-entry payload validation is deferred (pre-existing, owned by
`FallbackService`). JSDoc on `ingestCacheEntries` documents the deferred risk.

**Source**: arch_plan ADR-4; security_plan SR-SPLIT-08; security_final §5.

---

### `WallAnnouncerService` injected into `SubscriptionStompClient`

**Decision**: Step 4 of the 4-step termination ack sequence (`wallAnnouncer.announce({type:'prune', result})`)
is called inside `SubscriptionStompClient.handleTerminationAck`. State returns a `PruneResult`; the
caller decides what to do with it.

**Rationale**: `WallAnnouncerService` is a UI/a11y concern. Pushing it into State would couple State to
a11y delivery. Every State mutation method would potentially need an announce call.

**Source**: arch_plan ADR-3.

---

### Facade constructor order: `stomp.attach()` before `persistence.loadHashtags()`

**Decision**: Facade constructor calls `this.stomp.attach()` first (registers user-topic ack listeners),
then iterates `this.persistence.loadHashtags().forEach(tag => this.stomp.subscribeHashtag(tag))`.

**Rationale**: Ack listeners must be registered before any `/glacier/subscription` publish, otherwise the
resulting ack lands on no listener. This preserves the current constructor order at `subscription.service.ts:101–124`.

**Source**: arch_plan ADR-5.

---

### `validateHashtagsList` is the sole gate between localStorage and STOMP publish (SR-SPLIT-01)

**Decision** (split into 01a and 01b after ground-truth grep):

- **SR-SPLIT-01a**: `SubscriptionPersistence.loadHashtags()` is the only path in the codebase that reads the `hashtags` localStorage key. Validator runs before return; `JSON.parse` is wrapped in try/catch returning `[]` (also emits `console.warn` — not logging the malformed value; prevents DoS-on-boot).
- **SR-SPLIT-01b (new finding)**: `hashtag.component.ts:52` currently reads `localStorage.getItem('hashtags')` directly, bypassing `validateHashtagsList`. This is a pre-existing A03/CWE-20 gap. It is migrated to use `SubscriptionPersistence.loadHashtags()` in commit 2 as part of this work.

**Rationale**: `localStorage` is attacker-controlled under XSS or a compromised browser extension
(FIND-P3-SEC-4, OWASP A03). SR-SPLIT-01b converts the refactor from "no regression" to "active
improvement" of the persistence trust boundary.

**Source**: security_plan SR-SPLIT-01; arch_review SR-SPLIT-01 adaptation; security_final §1.

---

### ESLint rules enforce structural invariants (lint-time, not Karma-time)

**Decision**: SR-SPLIT-08-T1 and T2 are implemented as ESLint rules in `frontend/eslint.config.js`,
not as Karma `fs.readFileSync` structural tests. `npm run lint` must pass in **both**
`.github/workflows/verify.yml` and `.github/workflows/pull-request.yml` (R8).

**Rationale**: Karma runs in a browser bundle with no Node `fs` API. ESLint AST-level rules are more
precise (no false negatives from multiline calls or template strings), fire earlier (IDE + save), and
match the backend structural-test pattern (`OwaspMatrixTest`, `EndpointInventoryTest`) — different
language, same shape.

- **T1 rule**: `no-restricted-syntax` on `localStorage.getItem/setItem/removeItem` calls with argument
  `'hashtags'` or `'messageQueue'`, exempting `subscription-persistence.service.ts`.
- **T2 rule**: `no-restricted-imports` scoped to `subscription-persistence.service.ts`, forbidding
  imports from `./subscription-state.service`, `./subscription-stomp-client.service`, `./subscription.service`.

**Source**: arch_review Q1; security_final §2.

---

### `state.seedRecentlyTerminated()` is the test seam — no bracket-access, no spies on predicate

**Decision**: StompClient specs use a **real** `SubscriptionStateService` instance (feasible: Persistence is
pure, ~2 ms/spec). Guard-window state is seeded via `state.seedRecentlyTerminated(hashtag)` (public API).
No `spyOn(state, 'isRecentlyTerminated')` anywhere in the spec corpus.

**Rationale**: A spy returning the wrong value for `isRecentlyTerminated` is the exact regression mode for
SR-PRUNE-04/06 (pruned toots reappearing). Using the real State instance and the public seeder:
(1) survives internal refactors of the map shape, (2) cannot be confused with production paths,
(3) is the only mutation path tests use.

**Source**: security_plan SR-SPLIT-04; arch_review SR-SPLIT-04 adaptation; security_final §3.

---

## TDD Sequence — 6 Commits (Strangler-Fig, leaves-first)

| Commit | Color | Scope | Lane |
|---|---|---|---|
| 1 | RED | Empty spec scaffolds; verify ESLint runs in CI (R8); `git grep` recon for `messageQueue` violations (AC-20) | A |
| 2 | GREEN | Extract `SubscriptionPersistence` + `MessageQueue` move; SR-SPLIT-01b (`hashtag.component.ts`); ESLint T1/T2; T0 (try/catch + warn) | A |
| 3 | GREEN | Extract `SubscriptionStateService`; T3 (queue-identity); T7 (banner Subject) | A |
| 4 | GREEN | Extract `SubscriptionStompClient`; T5 (no-spy discipline); T6 (U-SEC-13 synchrony) | B |
| 5 | REFACTOR | Slim facade < 150 lines; T4 (ordering spy: clear before terminate) | A |
| 6 | CLEANUP | Delete `subscription.service.prune.spec.ts`; update fuzz-spec import; verify count ≥ 89 | C |

Pre-refactor Karma it() count: **83**. Post-refactor: **≥ 89** (+ 2 ESLint rules replacing T1/T2).

---

## Phase 2 Lane Partition

| Lane | Agent | Commits | Scope |
|---|---|---|---|
| **A** | `tdd-ddd-implementer` | 1, 2, 3, 5 | Persistence, State, facade. ESLint rules. SR-SPLIT-01b. try/catch. |
| **B** | `secure-tdd-implementer` | 4 | StompClient. 4-step ack synchrony. SR-TEST-23 principal binding. Guard-gate integration. |
| **C** | `tdd-ddd-implementer` | 6 | Spec cleanup. Import-path update. Count verification. |

Sequential: A → B → C. Round-2 cross-review after each lane.

---

## Resolved Conflicts

None. No `## ⚡ CONFLICT:` markers were raised in any round.

---

## User Approval

Date: 2026-05-08
Approval message (verbatim): "go ahead"

---

## Open Risks

| ID | Risk | Mitigation | Status |
|---|---|---|---|
| R1 | Persistence not sole localStorage reader | SR-SPLIT-01a + ESLint T1 + AC-16 git-grep AC | Resolved |
| R2 | Back-edge in import graph | ESLint T2 + AC-2/AC-17 | Resolved |
| R3 | Guard-gate spy regression | SR-SPLIT-04: real State, public seeder, AC-18 | Resolved |
| R4 | Settling-timer ordering on teardown | T4 (ordering spy), AC-6 | Resolved |
| R5 | Two-`MessageQueue` instances by accident | T3 (queue-identity), AC-3 | Resolved |
| R6 | Spec count drift | Pre=83, post≥89 invariant checked in commit 6 | Resolved |
| **R7** | `hashtag.component.ts:52` reads localStorage directly (pre-existing) | SR-SPLIT-01b — fixed in commit 2 | New, in-scope |
| **R8** | ESLint not yet verified in CI | Lane A verifies in commit 1; prep PR if absent | New, in-scope |
| SR-SPLIT-08 | `ingestCacheEntries` cache-entry payload unvalidated | JSDoc deferred-risk note; accepted (FallbackService trust boundary) | Accepted deferred |
| SR-SPLIT-07 | Migration-banner replay via attacker-controlled localStorage | Pre-existing; plain Subject limits damage to UX nuisance | Accepted |

---

## Acceptance Criteria (Complete List)

### From security_plan (AC-1..15)

- AC-1: Persistence is sole reader/writer of `hashtags` and `messageQueue` localStorage keys
- AC-2: Persistence module has no intra-project imports to state/stomp/facade (ESLint T2)
- AC-3: One `MessageQueue` instance; STOMP + ingest write to same queue (T3)
- AC-4: No `spyOn(state, 'isRecentlyTerminated')` in stomp-client spec (T5)
- AC-5: Migrated U-SEC-13 passes; `callOrder.length === 6` synchronously (T6)
- AC-6: FIND-P3-SEC-5/6 tests pass; new ordering test confirms clear-before-terminateAll (T4)
- AC-7: Migrated SR-TEST-23 tests (positive + negative + negative-ack) pass
- AC-8: Fuzz spec passes with updated import path
- AC-9: `MessageQueue` exported from `subscription-persistence.service.ts`
- AC-10: `hasMigrated$` is plain `Subject<boolean>`, not `BehaviorSubject` (T7)
- AC-11: Facade < 150 lines, no business logic
- AC-12: `ng test --watch=false` AND `npm run lint` green at every commit
- AC-13: `./mvnw verify` green
- AC-14: No production diff in `wall.component.ts`, `hashtag.component.ts`, `migration-banner.component.ts`, `fallback.service.ts`
- AC-15: JSDoc on `SubscriptionStateService.ingestCacheEntries()` documents deferred risk

### New / tightened (AC-16..20)

- **AC-16 (tightened)**: `git grep -n "localStorage.getItem('hashtags')"` returns exactly one hit in `subscription-persistence.service.ts`; same for `setItem` and `removeItem` variants
- **AC-17 (tightened)**: `npm run lint` wired into **both** `verify.yml` and `pull-request.yml`
- **AC-18 (new)**: No bracket-access `state['recentlyTerminated']` in any `subscription-*.spec.ts`; mutations only via `state.seedRecentlyTerminated()`
- **AC-19 (new)**: `loadHashtags()` calls `console.warn('hashtags localStorage malformed, resetting')` in catch; does NOT log the malformed value (CWE-117)
- **AC-20 (new, conditional)**: `git grep` for `messageQueue` localStorage access returns exactly one file (`subscription-persistence.service.ts`); any sibling violation handled like R7

---

## Input Validation Call-Site Checklist (CHK-CV-01..09)

For implementers and acceptance auditor:

- CHK-CV-01: `localStorage.getItem('hashtags')` only in `subscription-persistence.service.ts`
- CHK-CV-02: `safeSetItem('hashtags', ...)` only in `subscription-persistence.service.ts`
- CHK-CV-03: `localStorage.getItem('messageQueue')` only in `subscription-persistence.service.ts`
- CHK-CV-04: `validateHashtagsList(` has exactly one call site; in `subscription-persistence.service.ts`
- CHK-CV-05: `validateMessageQueue(` same rule
- CHK-CV-06: `data.hashtag` from ack reaches STOMP publish without going through localStorage
- CHK-CV-07: `destination()` builder uses `data.principal` from ack exclusively (SR-TEST-23)
- CHK-CV-08: `ingestCacheEntries` first line is `normalizeHashtag(hashtag)`; CREATED branch has `if (!normalised) break`
- CHK-CV-09: `MessageQueue.restore()` sequence: JSON.parse in try/catch → validateMessageQueue → discard-on-null with console.warn → assign storage

---

## References

- [Prior decision](2026-05-07-planning-quality-review.md) — D.3 deferred item
- [Acceptance](2026-05-08-acceptance-quality-review.md) (SR-PRUNE-01..13 lock-in)
- [OWASP A03:2021 — Injection](https://owasp.org/Top10/A03_2021-Injection/) — localStorage trust boundary, FIND-P3-SEC-4
- [OWASP A04:2021 — Insecure Design](https://owasp.org/Top10/A04_2021-Insecure_Design/) — dependency direction, single queue instance
- [OWASP A08:2021 — Software and Data Integrity Failures](https://owasp.org/Top10/A08_2021-Software_and_Data_Integrity_Failures/) — schema migration banner one-shot
- [CWE-20: Improper Input Validation](https://cwe.mitre.org/data/definitions/20.html) — `validateHashtagsList` call-site discipline
- [CWE-117: Improper Output Neutralisation for Logs](https://cwe.mitre.org/data/definitions/117.html) — do not log malformed localStorage value
- Glacier skill: [`glacier-fallback-mode-discipline`](../../.claude/skills/glacier-fallback-mode-discipline.md) (ingest convergence, [ADR-6](2026-04-24-planning-hashtag-prune.md#adr-6---normalizehashtagss-canonical-helper))
- Glacier skill: [`angular-karma-jasmine-testing`](../../.claude/skills/angular-karma-jasmine-testing.md) (Karma constraints; TestBed provider chain)
