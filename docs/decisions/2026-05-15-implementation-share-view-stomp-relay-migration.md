# Decision Record: ShareViewStompRelay Migration — Phase 2 Implementation

Date: 2026-05-15
Phase: Implementation
Agents: tdd-ddd-implementer (Lane A + Round 2), secure-tdd-implementer (Lane B + @Bean fix + Round 2), frontend-designer (Lane C + EXPIRED fix)
Status: Accepted

## Summary

Phase 2 implements the event-driven `ShareLinkActivityRegistry` replacing the deprecated
`ShareLinkService.listBySharer()` call in `ShareViewStompRelay`, rewrites the
`ShareViewPrincipalHandler` handshake ordering to enforce resolve-before-increment (SR-RELAY-05),
and adds the `ReadonlyWallStompClient` Angular service for the viewer wall's real-time
STOMP connection. Two Round 2 conflicts were found and fixed before approval.

## Lane Partition

| Lane | Agent | Scope |
|---|---|---|
| A | tdd-ddd-implementer | Registry + events + relay migration (backend domain/application) |
| B | secure-tdd-implementer | Handshake reorder, WebSocket config, ArchUnit rules (backend security) |
| C | frontend-designer | ReadonlyWallStompClient + viewer UX (Angular frontend) |

---

## Key Decisions

### ADR-RELAY-01: Registry Replaces listBySharer() in Relay

**Decision**: `ShareViewStompRelay.relayTootEvent()` reads `ShareLinkActivityRegistry.getActiveLinks(wallId)` instead of calling the deprecated `ShareLinkService.listBySharer()`.

**Rationale**: `listBySharer()` silently returns empty under the SQLite adapter (which stores SHA-256(token) — cannot reconstruct raw tokens). The in-memory registry is populated by Spring events emitted on create/revoke, so no DB read is needed at relay time.

**Files**: `ShareViewStompRelay.java`, `ShareLinkActivityRegistry.java`

### ADR-RELAY-02: Event-Driven Registry Population

**Decision**: `ShareLinkServiceImpl` publishes `ShareLinkActivatedEvent` (after `repository.save()`) and `ShareLinkRevokedEvent` (after `repository.markRevoked()`). `ShareViewStompRelay` consumes them via `@EventListener`.

**Rationale**: Synchronous Spring events (no `@Async`) ensure the registry is updated in the same thread and transaction boundary as the persistence operation, preventing race windows.

**Source**: Lane A — `ShareLinkServiceImpl.java`, `ShareLinkActivatedEvent.java`, `ShareLinkRevokedEvent.java`

### ADR-RELAY-03: Resolve-Before-Increment Handshake Ordering (SR-RELAY-05)

**Decision**: `ShareViewPrincipalHandler.determineUser()` ordering:
1. Extract `shareLinkId` from URI
2. Unbound-sentinel short-circuit (no DB hit)
3. `shareLinkService.resolve()` — reject if empty (HTTP 403)
4. Extract `sharerWallId`
5. Mint/read viewer cookie
6. `viewerCounter.increment()` — AFTER resolve
7. `registry.register()` under per-linkId lock (TOCTOU mitigation; reject + decrement if false)
8. Return `ShareViewerPrincipal`

**Rationale**: Counters incremented before resolve allow DoS via revoked-but-known links (OWASP API4). The registry re-resolves under lock to close the TOCTOU window between the handshake's initial resolve and registration.

**Source**: Lane B — `ShareViewPrincipalHandler.java`

### ADR-RELAY-04: ThreadPoolTaskScheduler as Spring-Managed @Bean

**Decision**: The `ThreadPoolTaskScheduler` for STOMP heartbeats is declared as a `@Bean` method in `WebSocketConfiguration`; `configureMessageBroker()` calls it directly (CGLIB proxy returns the singleton in `@Configuration` classes).

**Rationale**: An inline `new ThreadPoolTaskScheduler()` is not Spring-managed: `destroy()` is never called, leaving a thread pool leak in integration test runs with repeated context loads. Promoting it to a bean ensures Spring calls `afterPropertiesSet()` (initialize) and `destroy()` (shutdown) correctly.

**Source**: Round 2 fix — `WebSocketConfiguration.java`

### ADR-RELAY-05: Component-Owned EXPIRED Navigation (WCAG 2.2.1 / 3.2.5)

**Decision**: `ReadonlyWallStompClient.handleControlFrame()` emits `ViewerTransportMode.EXPIRED` on `transportMode$` only — no announce, no navigate. `ReadonlyWallComponent`'s `transportMode$` subscription is the sole owner: `liveAnnouncer.announce('assertive')` + `setTimeout(1000)` + `router.navigate`.

**Rationale**: The original implementation had both the service and the component announce + navigate on EXPIRED, causing two `assertive` announcements and two navigate calls from a single control frame (WCAG violation, possible duplicate route load). Services emit state; components react to state.

**Source**: Round 2 fix — `readonly-wall-stomp-client.service.ts`, `readonly-wall.component.ts`

### ADR-RELAY-06: No SockJS on Share-View Endpoint (SR-RELAY-18)

**Decision**: `ReadonlyWallStompClient` uses `@stomp/stompjs Client` directly with `webSocketFactory: () => new WebSocket(wsUrl)`.

**Rationale**: SockJS iframe transports would violate `CSP: frame-ancestors 'none'` on the share-view page. Native `WebSocket` has no such dependency.

**Source**: Lane C — `readonly-wall-stomp-client.service.ts`

---

## Files Created

| File | Description |
|---|---|
| `src/main/java/.../share/application/ShareLinkActivatedEvent.java` | Package-private record; compact constructor validates non-null |
| `src/main/java/.../share/application/ShareLinkRevokedEvent.java` | Package-private record; same pattern |
| `src/main/java/.../share/application/ShareLinkActivityRegistry.java` | @Component routing table; ConcurrentHashMap + per-linkId sentinel locks |
| `src/test/java/.../share/application/ShareLinkActivityRegistryTest.java` | Unit tests for registry operations |
| `src/test/java/.../share/application/ShareLinkActivityRegistryIT.java` | Concurrency test with CountDownLatch |
| `src/test/java/.../share/application/ShareLinkServiceImplEventEmissionTest.java` | Verifies events emitted after persistence |
| `src/test/java/.../share/application/ShareViewStompRelayEventTest.java` | onActivate / onRevoke listener tests |
| `src/test/java/.../share/application/ShareViewStompRelayRelayTest.java` | relayTootEvent tests using registry |
| `src/test/java/.../share/application/ShareRelayArchitectureTest.java` | 7 ArchUnit rules (ARCH-RELAY-01 to -06 + -02b) |
| `src/test/java/.../webservice/messaging/ShareViewPrincipalHandlerCapExhaustionTest.java` | Cap exceeded + refund path |
| `src/test/java/.../webservice/messaging/ShareViewPrincipalHandlerOrderingTest.java` | Resolve-before-increment ordering |
| `src/test/java/.../webservice/messaging/ShareViewPrincipalHandlerToctouIT.java` | Concurrent register/unregister locking |
| `frontend/src/app/share/services/viewer-transport-mode.ts` | ViewerTransportMode enum |
| `frontend/src/app/share/services/transport-status-source.ts` | TransportStatusSource interface |
| `frontend/src/app/share/services/readonly-wall-stomp-client.service.ts` | Per-component STOMP client |
| `frontend/src/app/share/services/readonly-wall-stomp-client.service.spec.ts` | 17 specs (1 skipped wss://) |

## Files Modified

| File | Change |
|---|---|
| `ShareViewStompRelay.java` | Removed listBySharer(); uses registry; added EventListener methods; debounced AUDIT.warn |
| `ShareLinkServiceImpl.java` | Removed ShareViewStompRelay dependency; publishes events via ApplicationEventPublisher |
| `ShareViewPrincipalHandler.java` | 5-arg constructor; new determineUser() ordering; TOCTOU guard |
| `WebSocketConfiguration.java` | Wires registry into handler; @Bean ThreadPoolTaskScheduler |
| `WebSocketConfigurationTest.java` | Stubs for new deps; removed stale scheduler field injection; +1 @Bean test |
| `readonly-wall.component.ts` | providers, connect/disconnect, transportMode$ reactions |
| `readonly-wall.component.spec.ts` | +9 tests including "announces exactly once" guard |
| `messages.de.json` + `messages.en.json` | 6 new `share.connection.*` i18n keys |
| `docs/decisions/README.md` | Index row for this document |

---

## Resolved Conflicts

### CONFLICT 5 (Phase 1): ShareViewStompRelay dependency in ShareLinkServiceImpl

**tdd-ddd-implementer**: remove the field, use ApplicationEventPublisher only (no circular dep)
**User decision (2026-05-15)**: Option A — field removed; ARCH-RELAY-06 added as 7th ArchUnit rule

### CONFLICT 1 (Round 2): Double announce+navigate on EXPIRED

**tdd-ddd-implementer**: service and component both own EXPIRED side-effects — must consolidate
**Resolution**: Component is sole owner; service emits state only (ADR-RELAY-05)

### CONFLICT 2 (Round 2): ThreadPoolTaskScheduler not a @Bean

**tdd-ddd-implementer + secure-tdd-implementer**: inline `new` instance is not Spring-managed
**Resolution**: Extracted to `@Bean` method; direct call in `configureMessageBroker()` via CGLIB (ADR-RELAY-04)

---

## Test Results

| Suite | Count | Failures |
|---|---|---|
| Backend unit (Surefire) | **1742** | 0 |
| Backend integration (Failsafe) | **383** | 0 |
| Angular Karma | **616** (1 skipped) | 0 |
| **BUILD** | | **SUCCESS** |

Baseline before this pipeline: 1708 unit + 379 IT + 591 Karma.
Net additions: +34 unit / +4 IT / +25 Karma.

---

## Open Risks

| Risk | Severity | Rationale |
|---|---|---|
| `noViewersLastWarnAt` map grows monotonically | Low (Open Risk R3) | Bounded by ~1000 real wallIds at Glacier scale; cleared on `@PreDestroy`; requires attacker to rotate wallId cookies at scale |
| `wss://` Karma test skipped | Informational | ChromeHeadless cannot redefine `window.location.protocol`; behavior covered by code review + ws:// path test |
| ToctouIT Scenario 2 naming misleading | Informational | Test runs sequentially despite "Race" in name; actual concurrency tested in Scenario 3; rename deferred |

---

## User Approval

Date: 2026-05-15
Approval message (verbatim): "approve"

---

## Security Implementation Decision: SEC-ACC-01 Fix — Counter Slot Leak on registry.register() Exception

**Requirement**: SR-RELAY-13 (exception path) — the per-link viewer counter slot incremented at handshake time must be refunded on ALL non-committed exits from the increment→cap-check→register sequence, including when `registry.register()` throws a `DataAccessException` (SQLite adapter, flapping DB). Without this, repeated handshakes against a flapping DB drive `viewerCount → maxViewers` and permanently deny all future legitimate viewers of that link (self-inflicted DoS, OWASP API4).

**Implementation**: `ShareViewPrincipalHandler.determineUser()` now wraps Steps 5-6 (increment → cap-check → register) in a `boolean committed` try/catch/finally block. The `finally` decrements the counter whenever `committed == false`. A `catch (RuntimeException e)` catches `DataAccessException` and any other runtime error from `register()`, emits an AUDIT log entry (`reason=registry_error`), and returns `null` (fail-closed HTTP 403). The existing AUDIT log lines for cap-exceeded and concurrent-revoke paths are preserved unchanged. No raw exception message or stack trace is included in the AUDIT event (glacier-structured-logging-logback: no internals in AUDIT; full exception is logged at ERROR level via the application logger for ops visibility). C1 — access control at every entry point.

**Test**: `ShareViewPrincipalHandlerOrderingTest#determineUser_registryRegisterThrows_counterRefunded` — stubs `registry.register()` to throw `TransientDataAccessResourceException("SQLite locked")`, asserts `determineUser` returns `null`, asserts `viewerCounter.get(linkId) == 0` (slot refunded). Confirmed RED before fix (exception propagated), GREEN after fix.

**Accepted risk**: None. The catch is scoped to `RuntimeException` (not `Throwable`), so `Error` subtypes (e.g. `OutOfMemoryError`) still propagate. `DataAccessException` extends `RuntimeException` so it is covered. The operational log (`log.error(...)`) includes the full exception for diagnostics without leaking it to the AUDIT trail.

---

## References

- Planning: `docs/decisions/2026-05-15-planning-share-view-stomp-relay-migration.md`
- Acceptance (P3-05): `docs/decisions/2026-05-14-acceptance-share-link-sqlite-persistence.md`
- [OWASP API4 — Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)
- [OWASP API1 — BOLA](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)
- [CWE-367: TOCTOU Race Condition](https://cwe.mitre.org/data/definitions/367.html)
- [WCAG 2.2.1 Timing Adjustable](https://www.w3.org/WAI/WCAG22/Understanding/timing-adjustable.html)
- [WCAG 3.2.5 Change on Request](https://www.w3.org/WAI/WCAG22/Understanding/change-on-request.html)
