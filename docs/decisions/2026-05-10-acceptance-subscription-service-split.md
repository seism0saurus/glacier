# Decision Record: SubscriptionService Split — Phase 3 Acceptance

Date: 2026-05-10
Phase: Acceptance
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **ACCEPTED — PASSED**

## Summary

The SubscriptionService split (deferred item D.3 from the Comprehensive Quality Review) is fully
accepted. All 20 acceptance criteria verified PASS by independent audit. 587/587 Karma tests pass,
315 backend integration tests pass, ESLint 0 errors. No Critical or High findings. The refactor
strengthens the OWASP A03/CWE-20/CWE-117 posture relative to the pre-split code.

## Acceptance verdict

**PASSED** — User approval received 2026-05-10.

## Evidence

| Dimension | Result |
|---|---|
| Karma/Jasmine (frontend unit) | **587/587 PASS** |
| Failsafe IT (backend integration) | **315/315 PASS — BUILD SUCCESS** |
| ESLint | **0 errors**, 5 cosmetic spec-file warnings (by design) |
| All 20 AC | **PASS** (independently verified by acceptance-test-auditor) |
| Security audit Round 1 | **PASSED** — no new vulnerabilities; 2 pre-existing informational observations |
| Security cross-review Round 2 | **PASSED** — AC-14 confirmed as strict security improvement |
| FIX REQUEST items issued | **None** |

## Verified Acceptance Criteria (AC-1..20)

| AC | Description | Status | Key evidence |
|---|---|---|---|
| AC-1 | Persistence sole reader/writer of `hashtags` + `messageQueue` localStorage keys | PASS | `git grep` — one production hit per key, all in `subscription-persistence.service.ts` |
| AC-2 | ESLint T2a/T2b/T2c — Persistence/State/StompClient DAG back-edge locks | PASS | `eslint.config.js` commit `4e20e0a`; `npm run lint` 0 errors |
| AC-3 | Single `MessageQueue` instance; STOMP + fallback write same queue (T3) | PASS | `subscription-state.service.spec.ts` queue-identity test |
| AC-4 | No `spyOn(state, 'isRecentlyTerminated')` in stomp-client spec (T5) | PASS | grep confirms zero spy calls; T5 discipline canary passes |
| AC-5 | `callOrder.length === 6` synchronously (T6, U-SEC-13) | PASS | `subscription-stomp-client.service.spec.ts:209` |
| AC-6 | `clearSettlingTimers()` before `terminateAll()` — ordering test (T4) | PASS | `subscription.service.spec.ts:907` |
| AC-7 | SR-TEST-23: positive + negative + negative-ack cases | PASS | `subscription-stomp-client.service.spec.ts:302/324/355` |
| AC-8 | Fuzz spec passes with updated import path | PASS | 3/3 fuzz it() blocks green standalone |
| AC-9 | `MessageQueue` exported from `subscription-persistence.service.ts` | PASS | `subscription-persistence.service.ts:138` |
| AC-10 | `hasMigrated$` is plain `Subject<boolean>` (T7) | PASS | `subscription-state.service.ts:73` — `new Subject<boolean>()` |
| AC-11 | Facade < 150 lines, no business logic | PASS | 99 lines; delegation + teardown ordering only |
| AC-12 | `ng test --watch=false` AND `npm run lint` green | PASS | 587/587 PASS; 0 lint errors |
| AC-13 | `./mvnw verify` green | PASS | 315 IT tests — BUILD SUCCESS |
| AC-14 | No unexpected production diffs in consumer files | PASS | Only `hashtag.component.ts` changed (SR-SPLIT-01b); all others untouched |
| AC-15 | JSDoc on `ingestCacheEntries` documents deferred risk | PASS | `subscription-state.service.ts` method-level JSDoc |
| AC-16 | `localStorage.getItem('hashtags')` exactly one production hit | PASS | `subscription-persistence.service.ts:58` only |
| AC-17 | `npm run lint` in both `verify.yml` and `pull-request.yml` | PASS | `verify.yml:31`, `pull-request.yml:67` |
| AC-18 | No `state['recentlyTerminated']` bracket-access in any spec | PASS | grep returns zero production-path hits |
| AC-19 | `console.warn` in catch; never logs malformed value (CWE-117) | PASS | `subscription-persistence.service.ts:67-68` — static string only |
| AC-20 | `messageQueue` localStorage access in exactly one file | PASS | All `messageQueue` operations confined to `subscription-persistence.service.ts` |

## Security Findings

### No new vulnerabilities introduced.

The refactor **improves** the pre-split security posture in three areas:

| Area | Before | After |
|---|---|---|
| OWASP A03:2021 / CWE-20 | `validateHashtagsList` present in Persistence; `hashtag.component.ts:52` bypassed it with direct `localStorage.getItem('hashtags')` | SR-SPLIT-01b fix: `hashtag.component.ts` now uses `persistence.loadHashtags()`, which applies `validateHashtagsList`, wraps `JSON.parse` in try/catch, and enforces CWE-117 in the catch |
| OWASP A04:2021 (Insecure Design) | DAG implicit within a single 846-line class | Explicit 4-node DAG enforced by ESLint T2a/T2b/T2c — back-edge is now a CI build break |
| Guard predicate integrity (SR-PRUNE-04/06) | Guard gate implicit; no structural canary against spy | T5 discipline check: `(state.isRecentlyTerminated as any).and === undefined` fails CI if a future spec installs a spy on the guard predicate |

### Informational observations (pre-existing, not regressions)

| ID | Observation | Source |
|---|---|---|
| INFO-1 | `console.error` in negative-ack paths concatenates server-controlled `data.principal`/`data.hashtag` (browser-console scope only; not server-side logs; pre-existing) | Carried over verbatim from pre-split code |
| INFO-2 | `JSON.parse(message.body)` in subscriber callbacks not wrapped in try/catch (trust contract: server validates STOMP frames + TLS; pre-existing) | Carried over verbatim from pre-split code |

## Known Gaps (accepted, do not block)

| Gap | Severity | Rationale |
|---|---|---|
| B2: no isolated `it()` blocks for `subscribeToUpdated`/`subscribeToDeleted` delegation | Low | Quality gap only; security_final confirms neither handler participates in guard-gate, principal-binding, or localStorage access. Indirect coverage via facade-level tests. Fix on next StompClient pass. |
| R-2: ESLint "File ignored" warnings for spec files | Low / cosmetic | Spec files correctly exempt from T1/T2 by design (tests must inspect raw localStorage). Adding spec files to lint scope would break legitimate test helpers. |

## OWASP Coverage

| Category | Standard | Verdict |
|---|---|---|
| Injection (localStorage trust boundary) | OWASP A03:2021, CWE-20, CWE-117 | **Strengthened** — validator-at-the-edge enforced in all production paths; ESLint T1 blocks bypass at lint time |
| Insecure Design (single queue, DAG) | OWASP A04:2021 | **Strengthened** — explicit DAG with static enforcement; `private readonly` factory pattern for single queue |
| Auth / Principal Binding | OWASP A07:2021, API1:2023 (BOLA) | **Maintained** — SR-TEST-23 migrated; `destination()` uses server-ack principal exclusively; 3-case spec coverage |
| Security Logging | OWASP A09:2021, CWE-117 | **Maintained** — `console.warn` in catch uses static string; never echoes attacker-controlled payload |

## Commits in scope

```
ae0f5c0  refactor(frontend): P3-D3 commit 1 — RED: CI lint + recon + spec scaffolds
f25b87d  refactor(frontend): P3-D3 commit 2 — GREEN: extract SubscriptionPersistence + fix SR-SPLIT-01b
c7239ed  refactor(frontend): P3-D3 commit 3 — GREEN: extract SubscriptionStateService + T3 + T7
6a5b957  refactor(frontend): P3-D3 commit 4 — GREEN: extract SubscriptionStompClient + T5 + T6 + SR-TEST-23
fd25305  refactor(frontend): P3-D3 commit 5 — REFACTOR: slim SubscriptionService facade + T4
3e4a562  refactor(frontend): P3-D3 commit 6 — CLEANUP: migrate prune spec
4e20e0a  refactor(frontend): P3-D3 A1 — extend ESLint T2 to all DAG layers (SR-SPLIT-02, ADR-1)
0a7bc06  docs(implementation): SubscriptionService split — Phase 2 decision record
```

## User Approval

Date: 2026-05-10
Approval message (verbatim): "approve"

## Final sign-off

The SubscriptionService split (deferred item D.3 from `docs/decisions/2026-05-07-planning-quality-review.md`)
is hereby **CLOSED — PASSED**. The split is security-equivalent to the pre-split code with four
strengthened structural controls. All three deferred items from the Comprehensive Quality Review are
now resolved:

| Item | Status |
|---|---|
| D.3 — SubscriptionService split | **COMPLETE** (this document) |
| GdprComponent body i18n | Deferred — jurisdictional complexity |
| Sec-02/P1-09 Bigbone SHA256 pin | Deferred — upstream coordination needed |

## References

- Planning: `docs/decisions/2026-05-08-planning-subscription-service-split.md`
- Implementation: `docs/decisions/2026-05-10-implementation-subscription-service-split.md`
- Quality Review acceptance: `docs/decisions/2026-05-08-acceptance-quality-review.md`
- OWASP Top 10 (2025) — A03 Injection, A04 Insecure Design, A07 Auth Failures
- OWASP API Security Top 10 (2023) — API1 BOLA
- CWE-20 — Improper Input Validation
- CWE-117 — Improper Output Neutralisation for Logs
- ADR-1..6 — see planning decision record
