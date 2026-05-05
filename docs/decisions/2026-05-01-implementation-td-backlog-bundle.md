# Decision Record: TD Backlog Bundle (F-5, F-6, F-7, F-9, OBS-1) — Implementation

Date: 2026-05-01
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A), secure-tdd-implementer (Lane B), devops-infra-engineer (Lane C)
Status: Accepted

## Summary

All five TD backlog items implemented across three sequential lanes. 954 unit tests and 187 integration tests pass; BUILD SUCCESS. One deviation from the Phase 1 plan discovered and resolved during RED phase (missing `ws.subscribe.rate_limited` in matrix WS-03 A09 cell).

## Lane Partition

| Lane | Agent | Items |
|------|-------|-------|
| A | `tdd-ddd-implementer` | F-5 (Awaitility), F-6 (matrix lockstep), F-7 (docs + lockstep test) |
| B | `secure-tdd-implementer` | F-7 (literal token assertions), OBS-1 (ShareView IT) |
| C | `devops-infra-engineer` | F-9 (Trivy sentinel) |

## Key Decisions

### F-5 — Awaitility scalar-size stabilization

**Decision**: Replaced `Thread.sleep(200)` in `WebSocketFrameSizeLimitIT:300` (and the same pattern in `StompEnumerationIndistinguishabilityIT:177`) with `awaitLogStability(appender, Duration.ofSeconds(5), Duration.ofMillis(150))`. The helper uses `AtomicInteger(-1)` as a first-poll sentinel so an empty appender at t=0 cannot accidentally satisfy the stability predicate. Scalar `int` snapshots are compared — never the live list reference, which would be vacuously equal on every poll.

**Rationale**: Negative log assertions have no positive completion signal; two consecutive identical size reads separated by `pollInterval == quietWindow` is the closest deterministic proxy for "logging has quiesced."

**Source**: tdd-ddd-implementer Lane A, SR-F5-01/SR-F5-02.

---

### F-6 — Bounded-section matrix lockstep, bijection both directions

**Decision**: Added `matrixHttpInventoryMatchesAllowlist()` and `matrixLockstepDetectsMissingEntry()` to `EndpointInventoryTest`. The `readMatrixHttpInventoryPaths()` helper scans only the `### HTTP Endpoints` section of `OWASP_COVERAGE_MATRIX.md`, bounded by the next `###`/`##` anchor. Regex `^\|\s*EP-\d+\s*\|\s*\w+\s*\|\s*\`([^\`]+)\`\s*\|.*` extracts EP-NN rows only. Bijection is asserted in both directions via `SoftAssertions`.

**Rationale**: A free-text scan over the full matrix file would false-positive: endpoint paths appear in 100+ OWASP cross-reference cells. Bounded-section parsing anchors to the authoritative EP-NN inventory table only.

**Source**: tdd-ddd-implementer Lane A, SR-F6-01/SR-F6-02.

---

### F-7 — Docs corrected; lockstep test added; literal token assertions added

**Decision**:
- `OWASP_COVERAGE_MATRIX.md:70`: `WS_HANDSHAKE_RATE_LIMITED` → `ws.handshake.rate_limited`
- `OWASP_COVERAGE_MATRIX.md` WS-03 A09 cell: added `ws.subscribe.rate_limited` (was absent — found during RED phase)
- `SECURITY_TESTS.md:239`: both tokens → dot-form
- `docs/decisions/2026-04-30-planning-owasp-matrix-completion.md:96`: both tokens → dot-form
- `docs/decisions/2026-04-30-acceptance-owasp-matrix-completion.md:125`: **left unchanged** (historical drift narrative)
- `HandshakeRateLimitInterceptorTest.java` UT-WS-RL-04: added `.contains("ws.handshake.rate_limited")`
- `SubscribeRateLimitInterceptorTest.java` UT-SUBRL-05: added `.contains("ws.subscribe.rate_limited")`
- `AuditEventNameLockstepTest.java` (NEW): scans matrix + SECURITY_TESTS.md + planning ADR; asserts dot-form present, SCREAMING_SNAKE absent; does NOT scan `2026-04-30-acceptance-owasp-matrix-completion.md`

**Rationale**: AUDIT event names are an external contract (SOC dashboards bind to literal string). Renaming code-emitted tokens would break running deployments silently. Docs are internal; they were updated. The lockstep test prevents re-drift.

**Source**: tdd-ddd-implementer Lane A (docs + lockstep), secure-tdd-implementer Lane B (literal assertions), SR-F7-01/SR-F7-02.

---

### F-9 — Comment-form Trivy sentinel

**Decision**: Appended `# SENTINEL: trivyignores parameter active # expires: 2027-05-01` to `infrastructure/security/.trivyignore-fs`. Added `trivyignoreFs_containsActiveSentinel()` to `TrivyignoreExpiryTest` (3 tests total). The test asserts the sentinel line is present and its expiry date is in the future.

**Rationale**: Comment-form sentinel is dependency-agnostic and survives version bumps. A real-CVE suppression would introduce unjustified security-policy data (A06 anti-pattern). Expiry date forces yearly review via existing `TrivyignoreExpiryTest` discipline.

**Source**: devops-infra-engineer Lane C, SR-F9-01.

---

### OBS-1 — ShareView REMOTE_ADDR production-path IT

**Decision**: `ShareViewRemoteAddrProductionPathIT` seeds a share link via `@Autowired InMemoryShareLinkRepository.save()`, connects to `/share-view-ws?shareLinkId=...` via real `WebSocketStompClient`, sends 5 SUBSCRIBE frames (rate-limit threshold=3), and asserts AUDIT log contains `ws.subscribe.rate_limited` with `ip-hash=` non-null.

**Rationale**: Routing test setup through `POST /rest/share-links` would couple the IT to `ShareRateLimiter` (5/min/wallId + 20/min/IP), risking cross-contamination with `ShareLinkRateLimitIT` siblings sharing the same JVM fork. Repository injection mirrors `SubscribeRateLimitProductionPathIT`.

**IT was green on first run**: AUDIT log showed `ws.subscribe.rate_limited ip-hash=127.0.0.xxx wallid-hash=2d328430`.

**Source**: secure-tdd-implementer Lane B, SR-OBS1-01/SR-OBS1-02/SR-OBS1-03.

---

## Deviation from Phase 1 Plan

### `ws.subscribe.rate_limited` absent from OWASP_COVERAGE_MATRIX.md WS-03 A09 cell

The Phase 1 planning doc listed only the `ws.handshake.rate_limited` line-70 fix. When `AuditEventNameLockstepTest` was written in RED state, it failed because `ws.subscribe.rate_limited` was also absent from the matrix. The WS-03 A09 cell was patched in the same commit, satisfying SR-F7-01 ("fix both drifts in same commit").

## Test Results

| Suite | Tests | Failures | Errors | Skipped |
|-------|-------|----------|--------|---------|
| Surefire (unit) | 954 | 0 | 0 | 0 |
| Failsafe (IT) | 187 | 0 | 0 | 0 |
| Jacoco | — | — | threshold pass | — |

## User Approval

Date: 2026-05-01
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-F9-01 | Sentinel comment proves `.trivyignore-fs` is read by CI but not that individual suppression lines are parsed by `aquasecurity/trivy-action` | First real CVE suppression provides line-level proof |

## References

- [Planning doc](2026-05-01-planning-td-backlog-bundle.md)
- [OWASP Coverage Matrix](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
- [.trivyignore-fs](../../infrastructure/security/.trivyignore-fs)
