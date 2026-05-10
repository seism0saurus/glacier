# Decision Record: SubscriptionService Split — Implementation

Date: 2026-05-10
Phase: Implementation
Agents: tdd-ddd-implementer (Lanes A, C; Round 2), secure-tdd-implementer (Lane B; Round 2)
Status: Accepted

## Summary

The 846-line `SubscriptionService` monolith was split into three focused Angular services
(`SubscriptionPersistence`, `SubscriptionStateService`, `SubscriptionStompClient`) plus a 99-line
facade that preserves the original public API byte-for-byte. All 6 implementation commits (Lanes A,
B, C) are committed to `main`. Tests: 587/587 pass. ESLint: 0 errors. The ESLint T2 dependency-direction
lock was extended to cover all four DAG nodes (Condition A1 from Round 2 cross-review).

---

## Key Decisions

### Strangler-Fig sequence (6 commits, leaves-first)

**Decision**: Extract Persistence first (commit 2), then State (commit 3), then StompClient (commit 4),
then slim the facade (commit 5), then clean up specs (commit 6). Each commit keeps `ng test` green.

**Rationale**: Leaves-first extraction means each extracted service is self-contained before any
consumer migrates to it. The facade remains the single point of injection for all consumers at every
intermediate state — no consumer needs to be updated before the split is complete.

**Source**: Phase 1 plan (6-commit TDD sequence, ADR-2).

---

### ESLint T2 extended to all DAG layers (Condition A1)

**Decision**: `eslint.config.js` was extended with three `no-restricted-imports` blocks (T2a, T2b, T2c),
one per lower-layer service:
- T2a (`subscription-persistence.service.ts`): forbids import of State/StompClient/Facade
- T2b (`subscription-state.service.ts`): forbids import of StompClient/Facade
- T2c (`subscription-stomp-client.service.ts`): forbids import of Facade

**Rationale**: The Phase 2 Round 2 cross-review (`impl_review`) raised that the original T2 block
covered only the bottom edge of the dependency DAG. `secure_final` accepted this as a maintenance
risk (not an active security risk, since Angular DI cycle provides a secondary safety net), but both
agents recommended fixing it. User approved as Condition A1. Commit `4e20e0a`.

**Alternatives considered**: Accept as deferred (A2) — rejected by user in favour of A1.

**Source**: impl_review CONFLICT; secure_final disposition; user approval 2026-05-10.

---

### Delegation tests for subscribeToUpdated/subscribeToDeleted deferred (Condition B2)

**Decision**: `subscribeToUpdated` and `subscribeToDeleted` have no dedicated `it()` blocks
directly asserting `state.updateMessage` / `state.dequeueById` calls. These are exercised
indirectly through full ack-flow tests. Deferral accepted.

**Rationale**: `secure_final` assessed this as a quality gap, not a security gap. Neither path
participates in principal-binding (SR-TEST-23), guard-gate (SR-PRUNE-04/06), or localStorage
access (SR-SPLIT-01). Downstream `MessageQueue.update`/`dequeue` are unit-tested at Persistence
level. User selected B2 (deferred).

**Source**: impl_review QN-1; secure_final QN-1 assessment; user approval 2026-05-10.

---

## Implemented Files

| File | Lines | Change |
|---|---|---|
| `subscription-persistence.service.ts` | 388 (new) | `MessageQueue` class + `loadHashtags`/`saveHashtags`/`createMessageQueue` |
| `subscription-state.service.ts` | 376 (new) | Single queue, 3 observables, guard map, settling timers |
| `subscription-stomp-client.service.ts` | 243 (new) | STOMP lifecycle, 4-step sync ack, SR-TEST-23 principal binding |
| `subscription.service.ts` | 99 (was ~846) | Thin facade, delegates one-for-one |
| `subscription-persistence.service.spec.ts` | 26 it() (new) | MessageQueue + loadHashtags coverage |
| `subscription-state.service.spec.ts` | 21 it() (new) | Queue-identity T3, banner-Subject T7, guard TTL |
| `subscription-stomp-client.service.spec.ts` | 23 it() (new) | T5, T6, SR-TEST-23, termination ack |
| `subscription.service.spec.ts` | 42 it() (updated) | T4 ordering, retargeted via stompClient |
| `subscription.service.prune.spec.ts` | DELETED | Tests migrated to new spec files |
| `hashtag.component.ts` | updated | SR-SPLIT-01b: uses `SubscriptionPersistence.loadHashtags()` |
| `eslint.config.js` | updated | T1 (localStorage), T2a/T2b/T2c (DAG back-edge locks) |
| `.github/workflows/verify.yml` | updated | `npm run lint` wired into CI (AC-17) |
| `.github/workflows/pull-request.yml` | updated | `npm run lint` wired into PR checks (AC-17) |

**Spec count**: 115 it() blocks across all 4 spec files + fuzz spec (target ≥ 89 — PASS).

---

## Acceptance Criteria Status (AC-1..20)

| AC | Description | Status |
|---|---|---|
| AC-1 | Persistence sole reader/writer of `hashtags`/`messageQueue` localStorage keys | PASS |
| AC-2 | ESLint T2 — Persistence no back-edges | PASS (T2a) |
| AC-3 | Single `MessageQueue` instance; STOMP + ingest write same queue (T3) | PASS |
| AC-4 | No `spyOn(state, 'isRecentlyTerminated')` in stomp-client spec (T5) | PASS |
| AC-5 | U-SEC-13 passes; `callOrder.length === 6` synchronously (T6) | PASS |
| AC-6 | FIND-P3-SEC-5/6 ordering: clear-before-terminateAll (T4) | PASS |
| AC-7 | SR-TEST-23 tests (positive + negative + negative-ack) | PASS |
| AC-8 | Fuzz spec passes with updated import path | PASS |
| AC-9 | `MessageQueue` exported from `subscription-persistence.service.ts` | PASS |
| AC-10 | `hasMigrated$` is plain `Subject<boolean>`, not `BehaviorSubject` (T7) | PASS |
| AC-11 | Facade < 150 lines, no business logic | PASS (99 lines) |
| AC-12 | `ng test --watch=false` AND `npm run lint` green at every commit | PASS |
| AC-13 | `./mvnw verify` green | Deferred to Phase 3 verification |
| AC-14 | No production diff in `wall.component.ts`, `fallback.service.ts` etc. | PASS |
| AC-15 | JSDoc on `ingestCacheEntries` documents deferred risk | PASS |
| AC-16 | `git grep` for `hashtags` localStorage returns exactly one hit in Persistence | PASS |
| AC-17 | `npm run lint` in both `verify.yml` and `pull-request.yml` | PASS |
| AC-18 | No bracket-access `state['recentlyTerminated']` in any spec | PASS |
| AC-19 | `console.warn` in catch; never logs malformed value (CWE-117) | PASS |
| AC-20 | `messageQueue` localStorage access in exactly one file (Persistence) | PASS |
| **A1** | ESLint T2 extended to all 4 DAG layers (T2a/T2b/T2c) | PASS (commit 4e20e0a) |

---

## Security Findings from Cross-Review

All security invariants from Phase 1 (`secure_final` verdict: **PASS-WITH-CONDITIONS**):

| ID | Finding | Status |
|---|---|---|
| SR-SPLIT-01a | `loadHashtags()` sole reader; try/catch + `console.warn` (no malformed value logged) | PASS |
| SR-SPLIT-01b | `hashtag.component.ts` migrated to `SubscriptionPersistence.loadHashtags()` | PASS |
| SR-SPLIT-02 | Dependency direction enforced in all layers by ESLint T2a/T2b/T2c | PASS |
| SR-SPLIT-03 | Single queue instance; `providedIn: 'root'` guarantees singleton | PASS |
| SR-SPLIT-04 | Real `SubscriptionStateService` in guard-gate tests; T5 spy discipline | PASS |
| SR-SPLIT-05 | 4-step termination ack fully synchronous; T6 verified | PASS |
| SR-SPLIT-06 | `clearSettlingTimers()` before `terminateAll()` in facade; T4 verified | PASS |
| SR-SPLIT-07 | `hasMigrated$` is plain `Subject<boolean>`; T7 late-subscriber assertion | PASS |
| SR-SPLIT-08 | `ingestCacheEntries` trust boundary documented in JSDoc; WallMessage invariants enforced | PASS |
| SR-TEST-23 | `destination()` uses `data.principal` exclusively; 3-case spec coverage | PASS |
| CHK-CV-01..09 | All call-site checklist items satisfied | PASS |

OWASP A03:2021 (Injection — localStorage trust boundary): SATISFIED
OWASP A04:2021 (Insecure Design — single queue, dependency direction): SATISFIED
OWASP A07:2021 (Auth Failures — principal binding): SATISFIED

---

## Known Gaps (accepted, do not block Phase 3)

| Gap | Disposition |
|---|---|
| Direct delegation tests for `subscribeToUpdated`/`subscribeToDeleted` (B2) | Deferred by user |
| Phase 3 auditors may note QN-2 (idempotency AND vs OR in `attach()`) | Cosmetic, runtime risk is negligible |

---

## Commits in scope

```
ae0f5c0  refactor(frontend): P3-D3 commit 1 — RED: CI lint + recon + spec scaffolds
f25b87d  refactor(frontend): P3-D3 commit 2 — GREEN: extract SubscriptionPersistence + fix SR-SPLIT-01b
c7239ed  refactor(frontend): P3-D3 commit 3 — GREEN: extract SubscriptionStateService + T3 + T7
6a5b957  refactor(frontend): P3-D3 commit 4 — GREEN: extract SubscriptionStompClient + T5 + T6 + SR-TEST-23
fd25305  refactor(frontend): P3-D3 commit 5 — REFACTOR: slim SubscriptionService facade + T4
3e4a562  refactor(frontend): P3-D3 commit 6 — CLEANUP: migrate prune spec
4e20e0a  refactor(frontend): P3-D3 A1 — extend ESLint T2 to all DAG layers (SR-SPLIT-02, ADR-1)
```

---

## User Approval

Date: 2026-05-10
Approval message (verbatim): "A1, B2, go ahead"

---

## References

- Planning: `docs/decisions/2026-05-08-planning-subscription-service-split.md`
- Quality Review acceptance: `docs/decisions/2026-05-08-acceptance-quality-review.md`
- OWASP A03:2021 — Injection (localStorage trust boundary)
- OWASP A04:2021 — Insecure Design (single queue, dependency DAG)
- OWASP A07:2021 — Identification and Authentication Failures (principal binding)
- CWE-20 — Improper Input Validation
- CWE-117 — Improper Output Neutralisation for Logs
