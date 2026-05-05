# Decision Record: TD Backlog Bundle (F-5, F-6, F-7, F-9, OBS-1) — Acceptance

Date: 2026-05-01
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1 + fix re-verification)
Status: Accepted — PASSED

## Summary

All five TD backlog items accepted with no outstanding findings. One fix cycle completed in-pipeline (SR-F5-02 — `LogStabilityTest` missing from Lane A delivery). Zero critical/high/medium security findings. 958 unit tests, 187 ITs, BUILD SUCCESS.

## Acceptance Result

**Disposition: PASSED**
**Criteria checked**: 10
**Criteria passed**: 10
**Criteria failed**: 0

## Security Audit Findings (security-auditor)

| Severity | Count | Disposition |
|----------|-------|-------------|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 4 | All verified/accepted (test-quality observations only) |
| Informational | 4 | No action required |

Production attack surface: unchanged — zero `src/main/**` modifications across all five items.

**Round 2 update**: Security finding F-2 (awaitLogStability race-condition reasoning) upgraded from ACCEPTED RISK → VERIFIED. `LogStabilityTest` scenario (d) is now an executable mutation gate that enforces the scalar-size-compare invariant.

## SR Coverage (acceptance-test-auditor)

| SR | Requirement | Status | Key Evidence |
|----|-------------|--------|--------------|
| SR-F5-01 | Scalar-size stabilization, pollDelay ≥ 100ms, timeout ≤ 5s | SATISFIED | `LogStabilityHelper.java:54–68` — `int current = appender.list.size()`; pollInterval=150ms; maxWait=5s; `previous >= 0` guard |
| SR-F5-02 | LogStabilityTest guards helper against reference-equality regression | SATISFIED (after fix) | `LogStabilityTest.java:224–243` scenario (d): `elapsed >= quietWindow` is the active mutation gate |
| SR-F6-01 | Matrix lockstep parses `### HTTP Endpoints` EP-NN table; loud AssertionError on missing anchor | SATISFIED | `EndpointInventoryTest.readMatrixHttpInventoryPaths` throws explicit `AssertionError` if section anchor absent |
| SR-F6-02 | Bijection both directions via SoftAssertions | SATISFIED | `EndpointInventoryTest.matrixHttpInventoryMatchesAllowlist` — `containsAll` both directions; negative canary present |
| SR-F7-01 | Both drifts fixed; AuditEventNameLockstepTest scoped to matrix + SECURITY_TESTS + planning ADR | SATISFIED | `AuditEventNameLockstepTest.SCANNED_FILES` lists 3 files; acceptance doc intentionally excluded |
| SR-F7-02 | UT-WS-RL-04 asserts `ws.handshake.rate_limited`; UT-SUBRL-05 asserts `ws.subscribe.rate_limited` | SATISFIED | `HandshakeRateLimitInterceptorTest:170–174`; `SubscribeRateLimitInterceptorTest:279–283` |
| SR-F9-01 | Sentinel in `.trivyignore-fs` with future expiry; TrivyignoreExpiryTest asserts it | SATISFIED | `.trivyignore-fs:7`; `TrivyignoreExpiryTest.trivyignoreFs_containsActiveSentinel` parses expiry and asserts `isAfter(LocalDate.now())` |
| SR-OBS1-01 | IT seeds via repository injection; asserts `ip-hash=` non-null | SATISFIED | `ShareViewRemoteAddrProductionPathIT.setUp:119–139` calls `shareLinkRepository.save(link)`; asserts `doesNotContain("ip-hash=null")` |
| SR-OBS1-02 | IT asserts literal `ws.subscribe.rate_limited` token | SATISFIED | Filter at line 237–239 + assertion at line 254–256 |
| SR-OBS1-03 | Production wiring; no manual session-attribute injection | SATISFIED | No `@MockitoBean` on rate-limit interceptors or `ShareViewPrincipalHandler`; REMOTE_ADDR populated by `ShareViewPrincipalHandler.java:117–124` |

## Fix Cycles

### SR-F5-02 — LogStabilityTest

**Raised by**: acceptance-test-auditor Round 1
**Finding**: `LogStabilityTest.java` listed in Phase 1 Lane A contract but never created; `awaitLogStability` duplicated across two ITs with no dedicated regression guard.
**Agent**: tdd-ddd-implementer
**Fix**:
1. `src/test/java/de/seism0saurus/glacier/security/LogStabilityHelper.java` — package-private utility; single canonical `awaitLogStability` implementation.
2. `src/test/java/de/seism0saurus/glacier/security/LogStabilityTest.java` — 4 tests: empty appender, settled burst, continuous growth (timeout), first-poll sentinel (mutation gate).
3. `WebSocketFrameSizeLimitIT.java` and `StompEnumerationIndistinguishabilityIT.java` updated to delegate to `LogStabilityHelper`.
**Re-verification**: SATISFIED — LogStabilityTest = 4/4 pass; dependent ITs = 5/5 pass; BUILD SUCCESS.

## Test Results

| Suite | Tests | Failures | Errors | Skipped |
|-------|-------|----------|--------|---------|
| Surefire (unit) | 958 | 0 | 0 | 0 |
| Failsafe (IT) | 187 | 0 | 0 | 0 |
| Jacoco | — | — | threshold pass | — |

## Resolved Conflicts

None — security-auditor and acceptance-test-auditor fully agreed across both rounds. No `## ⚡ CONFLICT:` markers raised.

## User Approval

Date: 2026-05-01
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-F9-01 | Trivy sentinel proves `.trivyignore-fs` is read by CI but not that individual suppression lines are parsed at line level by `aquasecurity/trivy-action` | First real CVE suppression will provide line-level proof; comment-form avoids injecting unjustified security-policy data |

## References

- [Planning doc](2026-05-01-planning-td-backlog-bundle.md)
- [Implementation doc](2026-05-01-implementation-td-backlog-bundle.md)
- [OWASP Coverage Matrix Completion — Acceptance (source backlog)](2026-04-30-acceptance-owasp-matrix-completion.md)
- [OWASP_COVERAGE_MATRIX.md](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
