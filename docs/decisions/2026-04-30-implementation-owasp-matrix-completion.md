# Decision Record: OWASP Coverage Matrix Completion — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (L1), secure-tdd-implementer (L2), devops-infra-engineer (L3)
Status: Accepted

## Summary

All nine OWASP coverage gaps identified in the Phase 1 planning document are closed.
Seventeen new test classes / specs were created across three implementation lanes, two new
production interceptors were added, `WebSocketConfiguration` was hardened with explicit
transport limits, and the CI `security.yml` pipeline gained a Trivy filesystem scan stage.
`./mvnw verify` passes 1130 tests (946 Surefire + 184 Failsafe) with zero failures.

## Key Decisions

### isOptedIn() helper unified across all StompCallback event paths

**Decision**: Extracted `isOptedIn(Status payload, String shortHandle)` as a private static
helper in `StompCallback`. Called in `sendMessage` (replacing the previous inline check),
`processStatusCreatedEvent`, and `processStatusEditedEvent`. The typed `ParsedStreamEvent`
path previously had no bot-mention guard.

**Rationale**: Defence-in-depth requires the guard to be at the Glacier layer in all event
paths, not delegated to Bigbone streaming filters. A future Bigbone API change or
subscription misconfiguration could bypass a filter-only guard.

**Alternatives considered**: Document the typed-path divergence as intentional (ADR-PT-A04-01
original). Rejected — fragile delegation is not sound defence-in-depth.

**Source**: L1 tdd-ddd-implementer; aligned with ADR-PT-A04-01 (revised) from Phase 1.

---

### HandshakeRateLimitInterceptor — per-IP WS CONNECT rate-limit

**Decision**: New `HandshakeRateLimitInterceptor` (`webservice.security` package) implements
`HandshakeInterceptor`. Registered on `/websocket` and `/share-view-ws` endpoints in
`WebSocketConfiguration`. Uses `ConcurrentHashMap<String, TokenBucket>` (Glacier's own
fixed-window `TokenBucket` pattern — Bucket4j is NOT on the classpath). Returns `false` +
HTTP 429 on breach. Fails-open on `Throwable` (logs WARN, allows connection — consistent
with `FallbackRateLimiter`). AUDIT log uses `LogScrubber.maskIp(ip)`.

**Config** (`glacier.security.ws.handshake.*`): `max-per-minute` default 10,
`bucket-capacity` default 10; all env-overridable via `${GLACIER_WS_HANDSHAKE_*}`.

**Rationale**: `FallbackRateLimiter` only covers HTTP; the WS handshake endpoint was
unguarded against cookie-rotation DoS. Each fresh wallId bypasses per-wallId limits, so a
per-source-IP guard at the transport level is necessary.

**Source**: L2 secure-tdd-implementer; per ADR-PT-G5-01 from Phase 1.

---

### SubscribeRateLimitInterceptor — silent-drop SUBSCRIBE rate-limit

**Decision**: New `SubscribeRateLimitInterceptor` implements `ChannelInterceptor`, registered
as first interceptor on `clientInboundChannel`. Key = `sourceIp + ":" + principalName` to
prevent wallId-rotation bypass. Returns `null` (silent drop) on exhaustion — preserves
indistinguishability (the attacker cannot distinguish rate-limiting from a non-existent
wallId). AUDIT event uses `LogScrubber.maskIp(ip)` + `LogScrubber.hash8(principalName)`.

**Config** (`glacier.security.ws.subscribe.*`): `max-per-minute` default 30,
`bucket-capacity` default 30; all env-overridable.

**Rationale**: Indistinguishability test alone is insufficient when wallId is semi-public
via share links. The rate-limit makes share-link-based enumeration impractical. Silent drop
(vs. ERROR frame) preserves indistinguishability.

**Source**: L2 secure-tdd-implementer; per ADR-PT-G7-01 from Phase 1.

---

### WebSocketConfiguration transport limits (four-axis API4 closure)

**Decision**: Added `configureWebSocketTransport()` override in `WebSocketConfiguration`
with explicit `setMessageSizeLimit`, `setSendBufferSizeLimit`, `setSendTimeLimit` sourced
from `@Value`-injected properties. Default values: 65536 bytes / 524288 bytes / 20000 ms.

**Rationale**: Spring defaults were implicit; explicit values from properties make the limits
operator-tunable and auditable. Completes the four-axis API4:2023 closure per ADR-PT-G5-01.

**Source**: L2 secure-tdd-implementer.

---

### IT suite rate-limit isolation

**Decision**: `src/test/resources/application.properties` overrides all four WS rate-limit
properties to 10,000/minute for the test context.

**Rationale**: All integration tests share `127.0.0.1` as source IP. The default 10/minute
handshake limit was exhausted mid-suite, causing `SubscriptionControllerHashtagValidationIT`
to receive HTTP 429 instead of 200. Raising limits to 10,000/min eliminates contamination
while preserving the rate-limit code paths under their dedicated unit tests.

**Source**: Identified during L2 cross-review; fix applied to `src/test/resources/application.properties`.

---

### Trivy filesystem scan in CI

**Decision**: `security.yml` now invokes Trivy twice: (1) image mode (existing — catches
base-OS CVEs), (2) filesystem mode scanning `infrastructure/glacier/` — catches Java
dependency CVEs inside the fat jar. Both modes have precondition steps (`docker manifest
inspect` and `ls glacier.jar`). Both report SARIF and upload to code-scanning. Separate
ignore files: `.trivyignore` (image) and `.trivyignore-fs` (fs). `TrivyignoreExpiryTest`
validates `# expires: YYYY-MM-DD` on every suppression in both files.

**Source**: L3 devops-infra-engineer; per ADR-PT-A06-01 from Phase 1.

---

### OWASP Coverage Matrix completed

**Decision**: `infrastructure/security/OWASP_COVERAGE_MATRIX.md` has zero blank cells. All
12 HTTP endpoints (EP-01..EP-12) and 7 STOMP destinations (WS-01..WS-07) are covered across
OWASP Web Top 10 (2021) and OWASP API Security Top 10 (2023). N/A cells carry structural
justifications (no likelihood-based N/A). `infrastructure/security/SECURITY_TESTS.md`
documents how to run all tests locally and how to add new endpoints or suppressions.

**Source**: L3 devops-infra-engineer; per SR-MTX-01/SR-MTX-02 from Phase 1.

## New Files

| File | Lane | Purpose |
|------|------|---------|
| `src/main/java/.../security/HandshakeRateLimitInterceptor.java` | L2 | WS CONNECT rate-limit per source IP |
| `src/main/java/.../security/SubscribeRateLimitInterceptor.java` | L2 | STOMP SUBSCRIBE rate-limit; silent drop |
| `src/test/java/.../security/StompCallbackOptInEnforcementTest.java` | L1 | UT-sec-01..04c, A04:2021 |
| `src/test/java/.../security/StompCallbackHostileResponseTest.java` | L1 | UT-sec-07..11, API10:2023 |
| `src/test/java/.../security/HandshakeRateLimitInterceptorTest.java` | L2 | Handshake rate-limit unit tests |
| `src/test/java/.../security/SubscribeRateLimitInterceptorTest.java` | L2 | Subscribe rate-limit unit tests |
| `src/test/java/.../security/EndpointInventoryTest.java` | L2 | UT-sec-06, API5+API9:2023 |
| `src/test/java/.../security/TrivyignoreExpiryTest.java` | L2/L3 | Validates # expires: on suppressions |
| `src/test/java/.../security/StompPayloadDeserializationIT.java` | L1 | IT-sec-01..03, A08:2021 |
| `src/test/java/.../security/StompMassAssignmentIT.java` | L1 | IT-sec-04..06, API3:2023 |
| `src/test/java/.../security/WebSocketFrameSizeLimitIT.java` | L2 | IT-sec-07a/b/c, API4:2023 |
| `src/test/java/.../security/StompEnumerationIndistinguishabilityIT.java` | L2 | IT-sec-08..09, API6:2023 |
| `frontend/e2e/workflows/security-opt-in.spec.ts` | L1 | Playwright e2e, A04:2021 opt-in |
| `infrastructure/security/.trivyignore-fs` | L3 | Jar-dependency CVE suppressions |
| `infrastructure/security/OWASP_COVERAGE_MATRIX.md` | L3 | Authoritative OWASP coverage evidence |
| `infrastructure/security/SECURITY_TESTS.md` | L3 | How to run and extend security tests |

## Modified Files

| File | Lane | Change |
|------|------|--------|
| `src/main/java/.../mastodon/StompCallback.java` | L1 | `isOptedIn()` helper; called on all 3 paths |
| `src/main/java/.../messaging/WebSocketConfiguration.java` | L2 | Transport limits; both interceptors registered |
| `src/main/resources/application.properties` | L2 | 7 new `glacier.security.ws.*` properties |
| `src/test/resources/application.properties` | L2 | WS rate limits raised to 10,000/min for IT suite |
| `.github/workflows/security.yml` | L3 | Trivy fs scan + preconditions + SARIF upload |

## Test Results

```
./mvnw verify (JAVA_HOME=/path/to/jdk23)

Surefire (unit):     946 tests — 0 failures, 0 errors
Failsafe (integration): 184 tests — 0 failures, 0 errors
Total: 1130 tests, 0 failures
```

## Resolved Conflicts

### Javadoc: EndpointInventoryTest webEnvironment=NONE vs MOCK
**L2 agent**: Javadoc said `NONE` but annotation is `MOCK`.
**Orchestrator**: Fixed directly — corrected Javadoc to `MOCK`.
**Resolution (2026-04-30)**: Javadoc aligned with actual annotation.

### WebSocketFrameSizeLimitIT mode declaration
**Cross-review**: Test claimed fallback mode applicability but STOMP broker is inactive in fallback mode.
**Orchestrator**: Fixed directly — mode declaration corrected to "live mode only; Fallback N/A; Killswitch N/A; Insecure same."
**Resolution (2026-04-30)**: Mode declaration corrected.

### security.yml: github.sha vs github.ref_name tag divergence
**L3 agent**: Precondition used `${{ github.sha }}` but Trivy image scan used `${{ github.ref_name }}`.
**Orchestrator**: Fixed directly — aligned both to `ref_name` (consistent with existing Trivy image scan step).
**Resolution (2026-04-30)**: Both steps use `github.ref_name`.

### security.yml: Trivy fs scan target path
**Cross-review**: L3 agent used Maven `target/glacier-*.jar`; but `security.yml` downloads artifact to `infrastructure/glacier/glacier.jar`.
**Orchestrator**: Fixed directly — corrected both precondition and `scan-ref` to `infrastructure/glacier/`.
**Resolution (2026-04-30)**: Paths aligned with artifact download step.

### LogScrubber.urlHostHash() wrong for bare IPs
**Cross-review**: `urlHostHash(ip)` parses URIs; a bare IP returns `hash8("unparseable")` — useless for incident correlation.
**Orchestrator**: Fixed directly — both interceptors changed to `LogScrubber.maskIp(ip)`.
**Resolution (2026-04-30)**: Correct scrubber method used throughout.

### IT suite rate-limit starvation
**L2 agent test run**: All ITs share `127.0.0.1`; default 10/min handshake limit exhausted mid-suite.
**Resolution (2026-04-30)**: `src/test/resources/application.properties` raises WS limits to 10,000/min for test context.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-WS-01 | Rate-limit buckets in-memory (single-instance only) | Glacier currently runs as a single instance; must revisit before HA deployment |
| AR-WS-02 | Source IP trusted from X-Forwarded-For via single Traefik proxy | Internal cluster bypass is out of threat model scope |
| AR-WS-03 | Known wallId allows subscription-topic probing at rate-limited rate | 122-bit UUID + 50-hashtag cap limits information gain; no sensitive data exposed |

## References

- [Phase 1 Planning Document](2026-04-30-planning-owasp-matrix-completion.md)
- [glacier-pentest-automator agent](./.claude/agents/glacier-pentest-automator.md)
- [OWASP_COVERAGE_MATRIX.md](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
