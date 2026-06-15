# Decision Record: ShareViewStompRelay Migration — Phase 1 Planning

Date: 2026-05-15
Phase: Planning
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2), ux-ui-designer (Round 1)
Status: Accepted

## Summary

`ShareViewStompRelay.relayTootEvent()` calls the deprecated `ShareLinkService.listBySharer()` which silently
returns empty under the SQLite adapter (enabled when `glacier.share.db.path` is set), dropping all toot
fan-outs to viewers. This is a production blocker before SQLite can be enabled. Additionally, the viewer
wall Angular component has a STOMP placeholder — the actual WebSocket subscription is not wired.

The solution is an event-driven in-memory routing table (`ShareLinkActivityRegistry`) populated by Spring
`ApplicationEventPublisher` events from `ShareLinkServiceImpl`. The relay reads from the registry instead
of calling the deprecated DB method. The viewer wall gains a new `ReadonlyWallStompClient` per-component
service using native WebSocket (no SockJS).

## Problem Context

### Why not `listSummaryBySharer()`?

`ShareLinkSummary` intentionally omits the raw `ShareLinkId` (stores only `idHash8` = first 8 hex chars
of SHA-256(token)). The SQLite adapter cannot reconstruct raw tokens. The relay needs raw tokens to build
STOMP topic paths (`/topic/share/{rawToken}/hashtag/eventType`).

### Why not SHA-256(token) as the STOMP topic identifier?

**Pass-the-hash vulnerability**: The SHA-256 hash stored in DB would become the new STOMP credential. A DB
compromise would allow subscribing to any share feed, defeating `AC-TOKEN-AT-REST`. The raw token must
remain the STOMP topic path — knowledge of the raw token is what grants STOMP access.

## Key Decisions

### ADR-RELAY-01: Event-driven registry replaces deprecated DB read

**Decision**: Introduce `ShareLinkActivityRegistry` in `share.application` — a `ConcurrentHashMap<String,
CopyOnWriteArraySet<ShareLinkId>>` keyed by `sharerWallId`. `ShareViewStompRelay.relayTootEvent()` reads
`registry.getActiveLinks(wallId)` instead of calling `listBySharer()`.

**Rationale**: The SQLite adapter stores only SHA-256(token) and cannot reconstruct raw tokens. An in-memory
registry populated from lifecycle events preserves the raw token only in application memory (not in the DB),
maintaining the pass-the-hash protection while enabling correct relay routing.

**Alternatives considered**:
- Option A (SHA-256 as STOMP topic): rejected as pass-the-hash — DB compromise yields STOMP access.
- Direct call to `InMemoryShareLinkRepository` bypassing interface: rejected as layer violation.

**Source**: ddd-tdd-architect Round 1; pass-the-hash concern raised by user.

---

### ADR-RELAY-02: Package-private event constructors; only ShareLinkServiceImpl emits events

**Decision**: `ShareLinkActivatedEvent` and `ShareLinkRevokedEvent` are package-private constructor records
carrying only `(sharerWallId: String, shareLinkId: ShareLinkId)`. Only `ShareLinkServiceImpl` may construct
them (enforced by ArchUnit ARCH-RELAY-03).

**Rationale**: Minimal blast radius on event payload; constructors enforced at compile time so no rogue
emitter can forge lifecycle events.

**Source**: secure-feature-planner Round 1 (SR-RELAY-04, SR-RELAY-11).

---

### ADR-RELAY-03: Per-linkId synchronization closes the TOCTOU window

**Decision**: `ShareLinkActivityRegistry` holds `ConcurrentHashMap<ShareLinkId, Object> linkLocks` as
per-linkId lock sentinels. `register()` acquires the per-linkId lock, re-resolves the link under the lock,
and returns `false` if the link is no longer active (concurrent revocation won the race). `onRevoke` in
`ShareViewStompRelay` acquires the same per-linkId lock before `unregister()` + `pushRevocation()`.

**Rationale**: Closes the window between handshake's `resolve()` and subsequent `register()`. Both
interleaving orders (revoke-first and register-first) are safe; neither leaves a stale active entry.

**Source**: secure-feature-planner Round 1 (CONFLICT 2); ddd-tdd-architect Round 2 (ACCEPTED).

---

### ADR-RELAY-04: Native WebSocket only for viewer STOMP (no SockJS)

**Decision**: `ReadonlyWallStompClient` uses `new WebSocket(...)` directly — no SockJS wrapper.

**Rationale**: SockJS iframe transports violate `CSP: frame-ancestors 'none'` set on the share-view page.
Using native WebSocket is required by CSP compliance.

**Source**: secure-feature-planner Round 1 (SR-RELAY-18); ddd-tdd-architect Round 2 (ACCEPTED).

---

### CONFLICT 5 resolution: ShareLinkServiceImpl must not call pushRevocation() directly

**Decision**: The direct `shareViewStompRelay.pushRevocation(id)` call at `ShareLinkServiceImpl.java:198-200`
is removed in the same commit that introduces `@EventListener onRevoke`. The `ShareViewStompRelay` field and
constructor parameter are removed from `ShareLinkServiceImpl`. Responsibility for `pushRevocation()` moves
entirely to the event listener path (SR-RELAY-23, ARCH-RELAY-06).

**Rationale**: Retaining the direct call alongside the new listener causes double STOMP control frame
delivery to viewers and duplicate AUDIT log lines per revoke — making a single revoke indistinguishable
from a double-revoke incident in log analysis.

**Source**: secure-feature-planner Round 2 (CONFLICT 5); user approved Option A 2026-05-15.

## Security Requirements

| SR | Severity | Description |
|----|----------|-------------|
| SR-RELAY-01 | CRITICAL | Event delivery synchronous (`@EventListener`, NOT `@Async`). Enforced by ARCH-RELAY-01 + ARCH-RELAY-05. |
| SR-RELAY-02 | CRITICAL | `onRevoke` listener must call `unregister()` + `pushRevocation()` atomically under the per-linkId lock. |
| SR-RELAY-03 | CRITICAL | `register()` restricted to `ShareViewPrincipalHandler` (ARCH-RELAY-02); `unregister()` restricted to `ShareViewStompRelay#onRevoke` (ARCH-RELAY-02b). |
| SR-RELAY-04 | HIGH | Event constructors package-private; only `ShareLinkServiceImpl` emits (ARCH-RELAY-03). |
| SR-RELAY-05 | HIGH | `determineUser()` ordering: `extractShareLinkId → resolve → mint-cookie → increment → cap-check → lock+register`. |
| SR-RELAY-06 | HIGH | `register()` re-resolves under per-linkId lock; returns `false` on revoked link. |
| SR-RELAY-07 | HIGH | Per-linkId lock covers handshake `register()` AND `onRevoke` `unregister()`. |
| SR-RELAY-08 | MEDIUM | `getActiveLinks()` returns unmodifiable set; no bulk-dump API exposed. |
| SR-RELAY-09 | MEDIUM | `share.relay.no_viewers` AUDIT.warn emitted with 30-second debounce when registry has no active entries for a sharerWallId (coupled with SR-RELAY-20). |
| SR-RELAY-10 | MEDIUM | Relay uses registry only for routing; `@Lazy ShareLinkService` retained only for `getRecentMessages()`. |
| SR-RELAY-11 | MEDIUM | Event records carry only `(sharerWallId, shareLinkId)` — no full aggregate. |
| SR-RELAY-12 | MEDIUM | Registry state volatile by design; viewer re-authenticates via STOMP handshake (re-hydrates registry) on JVM restart. |
| SR-RELAY-13 | HIGH | If `register()` returns `false`, handshake rejects HTTP 403 and decrements counter (fail-closed). |
| SR-RELAY-14 | LOW | AUDIT.info on `onActivate`: `share.link.activated shareId-hash8={} wallId-hash8={}`. |
| SR-RELAY-15 | LOW | AUDIT.info on `onRevoke`: `share.link.relay_revoked shareId-hash8={} wallId-hash8={}`. Exactly once per revoke (not duplicated from direct call — see SR-RELAY-23). |
| SR-RELAY-16 | LOW | `ReadonlyWallStompClient` uses `wss://` in production (`location.protocol === 'https:'` + Angular environment flag). |
| SR-RELAY-17 | MEDIUM | `ReadonlyWallStompClient` declared in `providers: [ReadonlyWallStompClient]` of `ReadonlyWallComponent` — not `providedIn: 'root'`. |
| SR-RELAY-18 | HIGH | `ReadonlyWallStompClient` uses `new WebSocket(...)` directly — no SockJS import (ARCH-RELAY-04). |
| SR-RELAY-19 | LOW | STOMP heartbeat `10s incoming/outgoing` matched server (`ShareViewWebSocketConfig`) and client. |
| SR-RELAY-20 | MEDIUM | 30-second debounce via `ConcurrentHashMap<String, Instant>` for `share.relay.no_viewers` per sharerWallId. |
| SR-RELAY-21 | MEDIUM | `ShareLinkActivityRegistry` exposes `@PreDestroy` that clears the registry map for test isolation. |
| SR-RELAY-22 | MEDIUM | AUDIT.info emissions in `onRevoke` logged from inside the per-linkId synchronized block, after `unregister()` returns and before `pushRevocation()` is invoked (audit ordering correctness). |
| SR-RELAY-23 | HIGH | `ShareLinkServiceImpl.revoke()` must NOT call `pushRevocation()` directly. ARCH-RELAY-06: `ShareLinkServiceImpl` must not depend on `ShareViewStompRelay`. |

## ArchUnit Rules

| Rule | Description |
|------|-------------|
| ARCH-RELAY-01 | No `@Async` annotation on any method in `share.application.*` `@EventListener` methods. |
| ARCH-RELAY-02 | `ShareLinkActivityRegistry.register()` callers restricted to `webservice.messaging.ShareViewPrincipalHandler`. |
| ARCH-RELAY-02b | `ShareLinkActivityRegistry.unregister()` callers restricted to `share.application.ShareViewStompRelay`. |
| ARCH-RELAY-03 | `ShareLinkActivatedEvent` and `ShareLinkRevokedEvent` constructors only called from `share.application.ShareLinkServiceImpl`. |
| ARCH-RELAY-04 | No class outside `share.application` calls `ShareLinkRepository.findAllBySharer` or `ShareLinkService.listBySharer`; no `@SuppressWarnings("deprecation")` referencing these methods in `src/main/java`. |
| ARCH-RELAY-05 | No `@Async` annotation on any class or method in `share.application` package. |
| ARCH-RELAY-06 | `ShareLinkServiceImpl` must NOT depend on `ShareViewStompRelay` (no field, constructor param, or import). |

## UX Requirements

- **Transport state machine** (`ReadonlyWallStompClient`): `LIVE | PROBING | FALLBACK | EXPIRED`. Exposed as `transportMode$: Observable<TransportMode>`.
- **PROBING grace window**: 5 seconds after WebSocket disconnect before transitioning to `FALLBACK`.
- **Reconnect backoff**: exponential 1s/2s/4s/8s (capped at 8s).
- **`TransportStatusSource` interface**: allows `ConnectionStatusComponent` reuse without `ReadonlyWallStompClient` injected into sharer wall path.
- **WCAG announce-then-navigate**: `LiveAnnouncer.announce(msg, 'assertive')` + ~1000ms delay before `router.navigate` on revocation/expiry (WCAG 2.2.1 / 3.2.5).
- **Killswitch parity**: HTTP 404 from `/rest/share/{id}/catalog` renders same DOM as genuine link expiry (anti-enumeration).
- **Live-region dedupe**: scoped to `ReadonlyWallComponent` only in this pipeline; `WallComponent` dedupe filed as separate follow-up.

## Phase 2 Lane Partition

### Lane A — tdd-ddd-implementer (domain + application layer)

New files:
- `src/main/java/de/seism0saurus/glacier/share/application/ShareLinkActivityRegistry.java`
- `src/main/java/de/seism0saurus/glacier/share/application/ShareLinkActivatedEvent.java`
- `src/main/java/de/seism0saurus/glacier/share/application/ShareLinkRevokedEvent.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareRelayArchitectureTest.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareLinkActivityRegistryTest.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareLinkActivityRegistryIT.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareViewStompRelayRelayTest.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareViewStompRelayEventTest.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareLinkServiceImplEventEmissionTest.java`
- `src/test/java/de/seism0saurus/glacier/share/application/ShareLinkEventPublishingIT.java`

Modified files:
- `src/main/java/de/seism0saurus/glacier/share/application/ShareViewStompRelay.java` — remove `listBySharer()` call; add `ShareLinkActivityRegistry` field; add `@EventListener onActivate()` and `@EventListener onRevoke()`; add 30s debounce for `no_viewers`
- `src/main/java/de/seism0saurus/glacier/share/application/ShareLinkServiceImpl.java` — add `ApplicationEventPublisher` injection; remove `ShareViewStompRelay` field entirely; emit `ShareLinkActivatedEvent` after `repository.save()`; emit `ShareLinkRevokedEvent` after `repository.markRevoked()`; remove `pushRevocation()` direct call (SR-RELAY-23)

### Lane B — secure-tdd-implementer (handshake reorder + TOCTOU fix + WebSocket endpoint)

Peer lanes: Lane A (registry + events), Lane C (Angular frontend — do not touch Angular files)

New/modified files:
- `src/main/java/de/seism0saurus/glacier/webservice/messaging/ShareViewPrincipalHandler.java` — reorder `determineUser()`: `extractShareLinkId → resolve → mint-cookie → increment → cap-check → synchronized(linkLock) { register() or reject 403 }`
- `src/main/java/de/seism0saurus/glacier/share/web/ShareViewWebSocketConfig.java` (new or update) — STOMP endpoint `/share-view-ws`, heartbeat `10s incoming/outgoing`

New test files:
- `src/test/java/de/seism0saurus/glacier/webservice/messaging/ShareViewPrincipalHandlerOrderingTest.java`
- `src/test/java/de/seism0saurus/glacier/webservice/messaging/ShareViewPrincipalHandlerToctouIT.java`
- `src/test/java/de/seism0saurus/glacier/webservice/messaging/ShareViewPrincipalHandlerCapExhaustionTest.java`

### Lane C — frontend-designer (Angular viewer STOMP + UX)

Peer lanes: Lane A (backend events/registry), Lane B (backend handshake/endpoint — do not touch backend files)

New files:
- `frontend/src/app/share/services/readonly-wall-stomp-client.service.ts`
- `frontend/src/app/share/services/transport-status-source.ts` (interface)
- `frontend/src/app/share/services/readonly-wall-stomp-client.service.spec.ts`

Modified files:
- `frontend/src/app/share/components/readonly-wall/readonly-wall.component.ts` — wire `ReadonlyWallStompClient`; remove placeholder comment; expose `transportMode$`
- `frontend/src/app/share/components/readonly-wall/readonly-wall.component.spec.ts` — add STOMP wiring specs
- `frontend/src/app/wall/components/connection-status/connection-status.component.ts` — consume `TransportStatusSource` interface instead of concrete type
- i18n: `src/main/resources/static/i18n/messages.de.json` + `messages.en.json` — add viewer-specific transport-status strings

## Resolved Conflicts

### CONFLICT 1 — Counter before resolve
**ddd-tdd-architect**: proposed `extractShareLinkId → increment → resolve → ...` ordering.
**secure-feature-planner**: `increment` before `resolve` allows attacker with a revoked link to exhaust counter slots.
**Resolution (2026-05-15)**: Safe ordering — `extractShareLinkId → resolve → increment → register`.

### CONFLICT 2 — TOCTOU between handshake register and concurrent revoke
**ddd-tdd-architect**: original plan had no synchronization between register and revoke paths.
**secure-feature-planner**: window between handshake's `resolve()` and `register()` allows revoke event to be missed.
**Resolution (2026-05-15)**: Per-linkId synchronized block; `register()` re-resolves under lock; returns `false` if concurrent revoke won.

### CONFLICT 3 — Killswitch returns HTTP 404 not 503
**ddd-tdd-architect**: original F18 stated "503 from polling endpoint (killswitch mode)".
**secure-feature-planner**: `ShareViewController` actually returns HTTP 404 in killswitch mode.
**Resolution (2026-05-15)**: Frontend treats 404 as `state=expired` (anti-enumeration parity per SR-SHARE-01).

### CONFLICT 4 — Live-region dedupe scope
**ux-ui-designer**: proposed live-region dedupe for both `ReadonlyWallComponent` and `WallComponent`.
**ddd-tdd-architect**: extending to `WallComponent` risks regressions in existing sharer wall; out of Pipeline B scope.
**Resolution (2026-05-15)**: Dedupe scoped to `ReadonlyWallComponent` only; `WallComponent` follow-up filed separately.

### CONFLICT 5 — ShareLinkServiceImpl retains direct pushRevocation() call
**secure-feature-planner**: retaining direct call alongside new listener causes double control frame and duplicate AUDIT.
**ddd-tdd-architect**: plan specified adding event emission but did not explicitly say to remove the direct call.
**Resolution (2026-05-15)**: User approved Option A — remove `ShareViewStompRelay` field from `ShareLinkServiceImpl`; ARCH-RELAY-06 added.

## User Approval

Date: 2026-05-15
Approval message (verbatim): "approve"

## Open Risks

| # | Risk | Mitigation |
|---|------|------------|
| R1 | SR-RELAY-FU-01: `unboundShareLinkId()` sentinel causes one wasted `resolve()` DB hit per malformed handshake request (low DoS amplification) | Lane B may add early-exit short-circuit; non-blocking |
| R2 | `linkLocks` map grows monotonically with distinct `ShareLinkId` values seen; entries survive revocation | Negligible at Glacier scale (~1000 active links); documented in registry Javadoc |
| R3 | `no_viewers` debounce map grows with distinct `sharerWallId` values | Same scale argument; same documentation approach |

## References

- P3-05 acceptance: `docs/decisions/2026-05-14-acceptance-share-link-sqlite-persistence.md`
- Open follow-up: `ShareViewStompRelay` deprecated call must be fixed before SQLite enabled in production (recorded in P3-05 acceptance)
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — A02 (Cryptographic Failures), A04 (Insecure Design)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — API1 (BOLA), API4 (Unrestricted Resource Consumption)
- [CWE-667: Improper Locking](https://cwe.mitre.org/data/definitions/667.html) — TOCTOU mitigation
- [ASVS V8.3.4 (L2)](https://github.com/OWASP/ASVS/) — event ordering and non-repudiation
- [NIST SP 800-53 AU-10](https://csrc.nist.gov/publications/detail/sp/800-53/rev-5/final) — audit completeness
- [WCAG 2.2 SC 2.2.1 + 3.2.5](https://www.w3.org/TR/WCAG22/) — announce-then-navigate pattern
