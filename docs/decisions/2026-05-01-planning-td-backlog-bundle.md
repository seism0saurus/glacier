# Decision Record: TD Backlog Bundle (F-5, F-6, F-7, F-9, OBS-1) — Planning

Date: 2026-05-01
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Five technical-debt items deferred from the 2026-04-30 OWASP Coverage Matrix Completion acceptance cycle. All are test/doc/CI fixes — no production code changes. One CONFLICT was raised and resolved during cross-review (OBS-1 share-link creation path).

## Items in Scope

| ID | What | Severity |
|----|------|----------|
| F-5 | Replace `Thread.sleep(200)` in `WebSocketFrameSizeLimitIT:300` with Awaitility stabilization poll | Low |
| F-6 | Add matrix lockstep assertion to `EndpointInventoryTest` (bijection with `OWASP_COVERAGE_MATRIX.md` EP-NN table) | Low |
| F-7 | Fix event-name drift: docs say `WS_HANDSHAKE_RATE_LIMITED` / `WS_SUBSCRIBE_RATE_LIMITED`; code emits `ws.handshake.rate_limited` / `ws.subscribe.rate_limited` | Informational |
| F-9 | Add sentinel suppression entry to `.trivyignore-fs` to prove `trivyignores` CI parameter is active | Informational |
| OBS-1 | Add IT covering `/share-view-ws` REMOTE_ADDR production path | Low |

## Key Decisions

### F-5 — Awaitility scalar-size stabilization (ADR-1)

**Decision**: Replace `Thread.sleep(200)` in `WebSocketFrameSizeLimitIT:300` with `awaitLogStability(appender, maxWait, quietWindow)` that snapshots `int size` (not the live list reference) between polls. The helper uses `AtomicInteger(-1)` as a first-poll sentinel so an empty appender at t=0 cannot accidentally satisfy the predicate.

**Rationale**: A negative log assertion ("nothing was logged") has no positive completion signal. Two consecutive identical size reads separated by `pollInterval == quietWindow` provide the closest deterministic proxy for "logging has quiesced." Comparing the live `ListAppender.list` reference is vacuous (always the same object); only scalar `int` snapshots are meaningful.

**Test case**: New `LogStabilityTest` covering: empty appender, settled burst, continuous-growth timeout, first-poll sentinel.

**Alternatives considered**: Plain `Awaitility.await().pollDelay(200ms)` — equivalent to `Thread.sleep`; rejected. `MemoryAppender` flush hook — over-engineered; rejected.

**Source**: ddd-tdd-architect Round 1 (ADR-1), secure-feature-planner SR-F5-01/SR-F5-02, architect Round 2 acceptance.

---

### F-6 — Bounded-section matrix lockstep, bijection both directions (ADR-2)

**Decision**: Add `httpRouteInventory_matrixContainsEveryAllowlistedEndpoint()` to `EndpointInventoryTest` using a parser scoped to the `### HTTP Endpoints` section of `OWASP_COVERAGE_MATRIX.md` (lines between `### HTTP Endpoints` and the next `###`/`##` header). Assert bijection in both directions using `SoftAssertions`.

**Rationale**: Free-text substring search over the whole matrix file is structurally insufficient — endpoint paths appear in 100+ OWASP cross-reference cells (lines 58–92), so a deletion from the actual EP-NN inventory table would be masked. The bounded-section parser with regex `^\|\s*EP-\d+\s*\|\s*\w+\s*\|\s*\`([^\`]+)\`\s*\|` extracts only the authoritative inventory rows. Bijection in both directions catches both "undocumented endpoint" and "phantom matrix row for a removed endpoint."

**Matrix structure** (verified against current file):
- `## Endpoint Inventory` at line 19
- `### HTTP Endpoints (discovered by EndpointInventoryTest — UT-sec-06)` at line 21
- Rows EP-01..EP-09 at lines 25–33
- `### STOMP/WebSocket Endpoints` at line 38 (out of scope)
- `## OWASP Web Application Top 10 (2021)` at line 52 (section terminator)

**Test cases**: `httpRouteInventory_matrixContainsEveryAllowlistedEndpoint()` + `matrixLockstepFailsWhenEndpointMissing()` negative canary.

**Source**: architect Round 1 (ADR-2), planner SR-F6-01/SR-F6-02, architect Round 2.

---

### F-7 — Update docs, not code; close both drifts; strengthen unit test assertions (ADR-4)

**Decision**: Update docs to use canonical dot-form names (`ws.handshake.rate_limited`, `ws.subscribe.rate_limited`) matching the code-emitted tokens. Do NOT change production code. New `AuditEventNameLockstepTest` prevents re-drift. Strengthen UT-WS-RL-04 and UT-SUBRL-05 to assert the literal token.

**Rationale**: AUDIT event names are an external contract — SOC dashboards and alerting bind to the literal string. Renaming the code-emitted token breaks running deployments without warning. Docs are internal; updating them is cost-free. The drift existed for BOTH events (`ws.handshake.rate_limited` and `ws.subscribe.rate_limited`); both must be fixed in the same commit.

**Files to update**:
- `infrastructure/security/OWASP_COVERAGE_MATRIX.md:70` — `WS_HANDSHAKE_RATE_LIMITED` → `ws.handshake.rate_limited`
- `infrastructure/security/SECURITY_TESTS.md:239` — both tokens → dot-form
- `docs/decisions/2026-04-30-planning-owasp-matrix-completion.md:96` — both tokens → dot-form
- `docs/decisions/2026-04-30-acceptance-owasp-matrix-completion.md:125` — **leave as-is** (describes the historical drift; changing it erases the historical record)
- `src/test/java/.../HandshakeRateLimitInterceptorTest.java` UT-WS-RL-04 — add `.contains("ws.handshake.rate_limited")`
- `src/test/java/.../SubscribeRateLimitInterceptorTest.java` UT-SUBRL-05 — add `.contains("ws.subscribe.rate_limited")`

**`AuditEventNameLockstepTest` scope**: scan ONLY `OWASP_COVERAGE_MATRIX.md` + `SECURITY_TESTS.md` + planning ADR. Do NOT scan `docs/decisions/2026-04-30-acceptance-owasp-matrix-completion.md` (historical drift narrative would false-positive).

**Source**: architect Round 1 (ADR-4), planner SR-F7-01/SR-F7-02, architect Round 2.

---

### F-9 — Comment-form Trivy sentinel (ADR-3)

**Decision**: Add `# SENTINEL: trivyignores parameter active # expires: 2027-05-01` to `infrastructure/security/.trivyignore-fs`. Extend `TrivyignoreExpiryTest` to assert the sentinel line is present with a future expiry date.

**Rationale**: A real-CVE suppression would inject load-bearing security-policy data with no actual triage justification (an A06 anti-pattern). A comment-form sentinel is dependency-agnostic and survives version bumps. The expiry forces a yearly review via the existing `TrivyignoreExpiryTest` expiry discipline.

**Accepted risk (AR-F9-01)**: Comment sentinel proves the file is read by CI but does not prove individual suppression lines are parsed by `aquasecurity/trivy-action`. First real CVE suppression will provide line-level proof.

**Source**: architect Round 1 (ADR-3), planner SR-F9-01 acceptance.

---

### OBS-1 — Repository injection for share-link setup (ADR-5)

**Decision**: New `ShareViewRemoteAddrProductionPathIT` seeds a share link via `@Autowired InMemoryShareLinkRepository` direct injection (not `POST /rest/share-links`), connects to `/share-view-ws?shareLinkId=...` via real `WebSocketStompClient`, sends >3 SUBSCRIBE frames, and asserts AUDIT log contains `ip-hash=` with a non-null value.

**Rationale**: `POST /rest/share-links` is rate-limited by `ShareRateLimiter` (5/min/wallId + 20/min/IP). Routing test setup through a rate-limited controller couples the IT to a sibling concern for zero observational benefit, risking cross-contamination when run alongside `ShareLinkRateLimitIT` siblings. Direct repository injection mirrors the isolation pattern of the sibling `SubscribeRateLimitProductionPathIT`.

**CONFLICT resolved**: Architect originally claimed `/rest/share-links` was not rate-limited. Secure-feature-planner identified `ShareLinkController.java:150` where `shareRateLimiter.checkShareCreate(...)` is called. Architect accepted the correction and chose option (b) — repository injection.

**Placement**: `src/test/java/de/seism0saurus/glacier/security/ShareViewRemoteAddrProductionPathIT.java` (alongside `SubscribeRateLimitProductionPathIT`).

**Phase 2 precondition**: verify `InMemoryShareLinkRepository.save(...)` is accessible from the test package.

**Source**: architect Round 1 (ADR-5), planner SR-OBS1-01/02/03, architect Round 2 CONFLICT resolution.

## Security Requirements

| SR | Requirement | Standard | Status |
|----|-------------|----------|--------|
| SR-F5-01 | Negative log assertions use scalar-size stabilization, pollDelay ≥ 100ms, timeout ≤ 5s | [OWASP A09:2021](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/) | ACCEPTED |
| SR-F5-02 | `LogStabilityTest` guards the helper against reference-equality regression | SAFECode test isolation | ACCEPTED |
| SR-F6-01 | Matrix lockstep parses `### HTTP Endpoints` EP-NN table; loud AssertionError on missing anchor | [OWASP API9:2023](https://owasp.org/API-Security/editions/2023/en/0xa9-improper-inventory-management/) | ACCEPTED |
| SR-F6-02 | Bijection both directions: `matrix ⊇ allowlist` AND `allowlist ⊇ matrix` via SoftAssertions | [OWASP API9:2023](https://owasp.org/API-Security/editions/2023/en/0xa9-improper-inventory-management/) | ACCEPTED |
| SR-F7-01 | Fix both drifts in same commit; `AuditEventNameLockstepTest` scoped to matrix + SECURITY_TESTS + planning ADR | [OWASP A09:2021](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/) | ACCEPTED |
| SR-F7-02 | UT-WS-RL-04 asserts `ws.handshake.rate_limited`; UT-SUBRL-05 asserts `ws.subscribe.rate_limited` | [OWASP A09:2021](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/) | ACCEPTED |
| SR-F9-01 | Sentinel present in `.trivyignore-fs` with future expiry; `TrivyignoreExpiryTest` asserts it | [OWASP A06:2021](https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/) | ACCEPTED |
| SR-OBS1-01 | IT seeds share link via repository injection; asserts ip-hash= non-null in AUDIT log | [OWASP API4:2023](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/), [API6:2023](https://owasp.org/API-Security/editions/2023/en/0xa6-unrestricted-access-to-sensitive-business-flows/) | ACCEPTED |
| SR-OBS1-02 | IT asserts AUDIT event literal token (not just `ip-hash=`) | [OWASP A09:2021](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/) | ACCEPTED |
| SR-OBS1-03 | IT runs with production wiring; no manual session-attribute injection | [OWASP API6:2023](https://owasp.org/API-Security/editions/2023/en/0xa6-unrestricted-access-to-sensitive-business-flows/) | ACCEPTED |

## Phase 2 Lane Partition

| Lane | Agent | Files / Concerns |
|------|-------|-----------------|
| A | `tdd-ddd-implementer` | New: `LogStabilityTest`, `AuditEventNameLockstepTest`. Edit: `WebSocketFrameSizeLimitIT:300` (F-5), `EndpointInventoryTest` (F-6 lockstep), `OWASP_COVERAGE_MATRIX.md:70`, `SECURITY_TESTS.md:239`, `docs/decisions/2026-04-30-planning-owasp-matrix-completion.md:96` (F-7 docs) |
| B | `secure-tdd-implementer` | New: `ShareViewRemoteAddrProductionPathIT` (OBS-1). Edit: `HandshakeRateLimitInterceptorTest` UT-WS-RL-04, `SubscribeRateLimitInterceptorTest` UT-SUBRL-05 (F-7 literal tokens) |
| C | `devops-infra-engineer` | Edit: `.trivyignore-fs` (sentinel), `TrivyignoreExpiryTest` (F-9) |

Lane sequence: A → B → C (sequential, per project preference).

## Resolved Conflicts

### OBS-1 share-link creation path
**ddd-tdd-architect**: claimed `POST /rest/share-links` is not rate-limited (WS-only rate limiters); recommended creating share link via controller.
**secure-feature-planner**: identified `ShareLinkController.java:150` — `shareRateLimiter.checkShareCreate(...)` — proving the endpoint IS rate-limited (5/min/wallId + 20/min/IP).
**Resolution (2026-05-01)**: Architect accepted the correction; chose repository injection (option b). OBS-1 IT seeds the share link directly via `InMemoryShareLinkRepository.save()`.

## User Approval

Date: 2026-05-01
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-F9-01 | Comment sentinel proves ignore file is read but not that individual suppression lines are parsed | First real CVE suppression provides line-level proof; comment-form avoids injecting unjustified security-policy data |

## References

- [OWASP Coverage Matrix Completion — Acceptance](2026-04-30-acceptance-owasp-matrix-completion.md) (TD backlog source)
- [OWASP_COVERAGE_MATRIX.md](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
- [SubscribeRateLimitProductionPathIT.java](../../src/test/java/de/seism0saurus/glacier/security/SubscribeRateLimitProductionPathIT.java) (OBS-1 pattern)
