# Decision Record: ShareViewStompRelay Migration — Acceptance

Date: 2026-06-15
Phase: Acceptance
Agents: security-auditor (Round 1 + fix re-verification), acceptance-test-auditor (Round 1 + fix re-verification)
Status: Accepted — **PASSED**

## Summary

Phase 3 acceptance of the ShareViewStompRelay migration (event-driven `ShareLinkActivityRegistry`
replacing the deprecated `ShareLinkService.listBySharer()`, resolve-before-increment handshake with
per-linkId TOCTOU locking, and the `ReadonlyWallStompClient` viewer STOMP wiring). Two independent
audits initially returned **PASSED WITH CONDITIONS** on two findings — both of which had been flagged
BLOCKING in an earlier (2026-05-20) review and left unfixed. The user directed both be fixed. After a
single fix cycle, both auditors independently re-verified the working tree and signed off **PASSED**
with no remaining blockers.

## Audit Disposition

| Phase 3 stage | Security | Acceptance |
|---|---|---|
| Round 1 (independent) | PASS WITH CONDITIONS | PASSED WITH CONDITIONS |
| After fix cycle (re-verify) | **PASS** | **PASSED** |

Round 2 cross-review was folded into Round 1: the acceptance-test-auditor consumed the security audit
verbatim, peer-reviewed it, and confirmed no inter-auditor conflict — both converged on the same two
findings, so a separate security-auditor Round 2 pass was redundant.

## Findings and Dispositions

### SEC-ACC-01 / ACC-01 — Viewer-cap counter leak on `register()` throw (HIGH) — RESOLVED

**Finding**: `ShareViewPrincipalHandler.determineUser()` incremented the viewer counter and then called
`ShareLinkActivityRegistry.register()`, which calls `shareLinkService.resolve()` inside the per-linkId
synchronized block. Under the SQLite adapter (`glacier.share.db.path` set — the exact condition this
migration targets) `resolve()` can throw `DataAccessException`. With no `try/finally`, the increment was
never refunded, leaking a slot permanently → cap exhaustion → self-inflicted DoS of that link. The
exception path had no test.

**Fix** (secure-tdd-implementer, `ShareViewPrincipalHandler.java` ~L193-243): the two independent refund
branches were replaced by a single `boolean committed` try/catch/finally. `committed` is set true only on
the success return; `catch (RuntimeException e)` (covering `DataAccessException`) returns null fail-closed
without re-throwing; `finally` decrements the counter iff `!committed`. The new AUDIT line logs
`reason=registry_error shareId-hash={}` (hash8 only) and the full exception goes to the operational ERROR
log — no raw wallId/token/cookie reaches the AUDIT encoder (D-13 / SR-8).

**Verification**: new test `ShareViewPrincipalHandlerOrderingTest#determineUser_registryRegisterThrows_counterRefunded`
uses a real `ShareLinkViewerCounter`, stubs `registry.register()` to throw `TransientDataAccessResourceException`,
and asserts null return + counter == 0. RED before fix (exception propagated, count stayed 1), GREEN after.
SR-RELAY-13 upgraded PARTIAL → PASS (SR-RELAY matrix now 23/23 PASS).

**Disposition**: Fixed.

### SEC-ACC-02 / ACC-02 — Fixed reconnect delay instead of exponential backoff (MEDIUM) — RESOLVED

**Finding**: `readonly-wall-stomp-client.service.ts` used `reconnectDelay: 1000` (fixed 1 Hz), violating the
Phase 1 UX requirement (exponential 1s/2s/4s/8s capped at 8s) and not recorded as an accepted risk. Revoked
or expired viewers reconnect once per second, amplifying server-side AUDIT volume and producing a
thundering-herd reconnect storm against the resolve path (a DB hit under SQLite).

**Fix** (frontend-designer, `readonly-wall-stomp-client.service.ts`): added
`INITIAL_RECONNECT_DELAY_MS = 1000` / `MAX_RECONNECT_DELAY_MS = 8000` and a `currentReconnectDelay` field;
`onStompDisconnected()` advances `Math.min(current * 2, MAX)` and writes it into `stompClient.reconnectDelay`;
`connect()` and `onStompConnected()` reset to the initial value. Sequence 1000→2000→4000→8000→8000(cap),
reset to 1000 on connect. Native WebSocket / no SockJS preserved (SR-RELAY-18); the LIVE/PROBING/FALLBACK/
EXPIRED state machine and 5 s PROBING grace window unregressed; the EXPIRED short-circuit runs before the
backoff advance so a control-frame expiry never perturbs the counter.

**Verification**: two new fakeAsync specs read the live `stompClient.reconnectDelay` after each simulated
disconnect and assert the exact progression + reset, plus independent re-progression after reconnect. RED
before fix (fixed 1000), GREEN after.

**Disposition**: Fixed.

### SEC-ACC-03 — Unbound-sentinel mints a viewer id + AUDIT line per request (LOW) — DEFERRED

No counter or DB is touched and the topic interceptor blocks the sentinel from subscribing; pairs with
handshake-level per-IP rate limiting. Recorded as a Phase 5 follow-up: add an AUDIT rate-limit per
(linkId, IP) on `viewer.handshake_rejected`.

### SEC-ACC-04 — ARCH-RELAY-02 scope-broadening vs SR-RELAY-03 wording (INFO) — DOCUMENT

ARCH-RELAY-02 deliberately allows `ShareViewStompRelay.onActivate` as a second `register()` caller (benign —
the link was just persisted in the same synchronous call chain). The rule and its tests are correct;
SR-RELAY-03's prose ("register restricted to ShareViewPrincipalHandler") should be updated to match the
implemented-and-tested two-caller rule. Doc-only; no code change.

### ACC-03 — `no_viewers` debounce test is non-behavioral (LOW) — ACCEPTED FOLLOW-UP

`ShareViewStompRelayRelayTest.relayTootEvent_emptyRegistry_auditWarnWithDebounce_noException` asserts only
"no exception / no STOMP" and would still pass if the debounce were removed. The debounce implementation is
unchanged and the behavior is low-severity observability. Follow-up: strengthen the test to prove the 30 s
suppression (inject a clock or capture the AUDIT logger). Owner: tdd-ddd-implementer. Non-blocking.

## Test Results (re-run during the fix-cycle re-verification, 2026-06-15)

| Suite | Count | Delta vs Phase 2 | Failures |
|---|---|---|---|
| Backend unit (Surefire) | **1743** | +1 (ACC-01 test) | 0 |
| Backend integration (Failsafe) | **383** | unchanged | 0 |
| Angular Karma | **618** (1 skipped) | +2 (backoff specs) | 0 |
| Jacoco coverage check | met (instruction ≥45% / branch ≥35%) | — | — |
| **BUILD** | **SUCCESS** | | |

The 1 Karma skip is the unchanged, documented `wss://` test (ChromeHeadless cannot redefine
`window.location.protocol`).

## Live-Relay E2E — Attempted 2026-06-15

The mandatory live-relay Playwright e2e (`frontend/e2e/workflows/share-link.spec.ts`) covers the full live
STOMP fan-out + revoke→/expired round trip against the dockerized Mastodon stack. It was **attempted** in
this acceptance against the real `docker-compose.yaml` stack (chromium project). The attempt surfaced three
**pre-existing** problems unrelated to the relay migration, two of which were fixed here so the gate can run:

1. **Boot crash (FIXED).** `docker-compose.yaml` set `DEVMODE=true` with `SPRING_PROFILES_ACTIVE=default`;
   the `StartupSanityChecker` (Sec-01, committed 2026-05-11 — *after* the compose file's last change on
   2026-05-05) fails fast on that combination, so Glacier could not boot at all in the e2e stack. Fixed by
   setting `SPRING_PROFILES_ACTIVE: "dev"` (the checker's documented remediation; `dev` not `test`, so
   `@Profile("test")` `PassthroughFallbackAuthGuard` stays inactive and fallback auth remains
   production-like). Glacier now boots healthy and streams toots.
2. **Broken gate spec (FIXED).** `share-link.spec.ts`, `share-link-fallback.spec.ts`, and
   `share-link-killswitch.spec.ts` import `{ MastodonClient }` and call `new MastodonClient(...).postToot()`,
   but `e2e/helper/mastodon-client.ts` only ever exported standalone functions — never that class. The specs
   threw `TypeError: MastodonClient is not a constructor` and had been dead on `main` since 2026-04-24
   (commit b205b97). Fixed by adding the `MastodonClient` class (constructor `(apiUrl, token)` + `postToot`)
   to the helper; the three specs now type-check and collect.
3. **Environmental toot delivery (NOT fixed — machine-specific).** Core toot-posting specs
   (`toots.spec.ts`, `subscriptions.spec.ts`, `security-opt-in.spec.ts`) failed because posted toots did not
   surface on the wall within timeout on the developer machine (heavy container contention). This is not a
   code defect and not introduced by this work; it is expected to pass in clean CI with dedicated resources.

Neither relay fix altered the external contract (`/share-view-ws?shareLinkId=`, `/topic/share/{id}/...`,
control frame) the spec exercises. Per CLAUDE.md testing policy this suite must run green in CI via
`-P RunE2ETest` against the real Mastodon stack **before merge** — now unblocked by fixes (1) and (2).

## Open Risks (carried from planning, re-affirmed)

| # | Risk | Mitigation |
|---|------|------------|
| R2 | `linkLocks` map grows monotonically with distinct `ShareLinkId` values | Negligible at Glacier scale (~1000 active links); documented in registry Javadoc |
| R3 | `noViewersLastWarnAt` debounce map grows with distinct `sharerWallId` | Same scale argument; cleared on `@PreDestroy` |
| FU | SEC-ACC-03 unbound-sentinel AUDIT amplification | Phase 5 follow-up: per-(linkId, IP) AUDIT rate-limit |

## User Approval

Date: 2026-06-15
Approval message (verbatim): "approved"

Context: the user pre-authorized the PASSED disposition conditional on both auditors confirming the two fixes
pass re-verification. Both the security-auditor and the acceptance-test-auditor independently re-ran the
working tree and reported **PASS / PASSED** with no remaining blockers, satisfying that condition.

## References

- Planning: `docs/decisions/2026-05-15-planning-share-view-stomp-relay-migration.md`
- Implementation: `docs/decisions/2026-05-15-implementation-share-view-stomp-relay-migration.md`
- Prior acceptance that filed the blocker: `docs/decisions/2026-05-14-acceptance-share-link-sqlite-persistence.md`
- [OWASP API4:2023 — Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)
- [OWASP API1:2023 — BOLA](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)
- [CWE-772: Missing Release of Resource](https://cwe.mitre.org/data/definitions/772.html)
- [CWE-367: TOCTOU Race Condition](https://cwe.mitre.org/data/definitions/367.html)
- [WCAG 2.2 SC 2.2.1 + 3.2.5](https://www.w3.org/TR/WCAG22/)
