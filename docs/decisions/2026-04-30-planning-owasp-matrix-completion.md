# Decision Record: OWASP Coverage Matrix Completion — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Nine gaps in the Glacier OWASP coverage matrix are closed by this pipeline: eleven new test classes, one Playwright spec, two new production WebSocket rate-limit interceptors, one Trivy filesystem CI stage, and the `infrastructure/security/OWASP_COVERAGE_MATRIX.md` / `SECURITY_TESTS.md` documents. The matrix scope is dynamic — it equals whatever `EndpointInventoryTest` discovers at runtime, not a hardcoded cell count.

## Problem Statement

The OWASP coverage matrix required by `.claude/agents/glacier-pentest-automator.md` (Step 4b/4c) had nine cells with neither a passing test nor an explicit structurally-justified N/A:

| Gap | OWASP item | Missing evidence |
|-----|------------|-----------------|
| G1 | [A04:2021 — Insecure Design](https://owasp.org/Top10/A04_2021-Insecure_Design/) | Bot opt-in check present in GenericMessage path only; typed `ParsedStreamEvent` path uncovered |
| G2 | [A06:2021 — Vulnerable and Outdated Components](https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/) | Trivy image scan exists; no jar-dependency SCA stage |
| G3 | [A08:2021 — Software and Data Integrity Failures](https://owasp.org/Top10/A08_2021-Software_and_Data_Integrity_Failures/) | No STOMP deserialization hardening tests |
| G4 | [API3:2023 — Broken Object Property Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa3-broken-object-property-level-authorization/) | No mass-assignment test on STOMP subscription/termination DTOs |
| G5 | [API4:2023 — Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/) | WebSocket frame-size cap implicit (Spring default ~64 KB); CONNECT rate-limit absent at handshake level |
| G6 | [API5:2023 — Broken Function Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa5-broken-function-level-authorization/) | No HTTP endpoint inventory test |
| G7 | [API6:2023 — Unrestricted Access to Sensitive Business Flows](https://owasp.org/API-Security/editions/2023/en/0xa6-unrestricted-access-to-sensitive-business-flows/) | No SUBSCRIBE enumeration/rate-limit test |
| G8 | [API9:2023 — Improper Inventory Management](https://owasp.org/API-Security/editions/2023/en/0xa9-improper-inventory-management/) | No deprecated-endpoint inventory check |
| G9 | [API10:2023 — Unsafe Consumption of APIs](https://owasp.org/API-Security/editions/2023/en/0xa10-unsafe-consumption-of-apis/) | No malformed Mastodon response handling tests |

## Key Decisions

### ADR-PT-A04-01 (revised): Extract `isOptedIn()` helper and enforce uniformly across all typed paths

**Decision**: Extract `isOptedIn(Status payload, String shortHandle)` helper in `StompCallback`. Call it in `processStatusCreatedEvent`, `processStatusEditedEvent`, AND `sendMessage` (GenericMessage path). The previous typed-path delegation to the Mastodon subscription filter was fragile and undocumented.

**Rationale**: Defence-in-depth requires the guard to be at the Glacier layer, not delegated to a third-party streaming filter. A future Bigbone API change or subscription misconfiguration could bypass the Mastodon filter; `isOptedIn()` ensures the opt-in invariant is always enforced. Extracting a helper (vs. repeating the check inline) ensures future paths cannot omit it — test UT-sec-04c verifies coverage structurally.

**Alternatives considered**: Document the divergence as intentional (ADR-PT-A04-01 original). Rejected — fragile delegation to a third-party filter is not a sound defence-in-depth design.

**Source**: ddd-tdd-architect Round 1 → secure-feature-planner DISPUTE → ddd-tdd-architect Round 2 ACCEPTED.

---

### ADR-PT-G5-01: Four-axis API4 closure — frame-size cap + WS CONNECT rate-limit

**Decision**: API4 requires four explicitly configured axes in `WebSocketConfiguration`:
1. `registration.setMessageSizeLimit(glacier.security.ws-message-size-bytes)` — default 65536
2. `registration.setSendBufferSizeLimit(glacier.security.ws-send-buffer-bytes)` — default 524288
3. `registration.setSendTimeLimit(glacier.security.ws-send-time-ms)` — default 20000
4. New `HandshakeRateLimitInterceptor` registered on `/websocket` (and `/share-view-ws`) — Bucket4j per source IP, 10 connections/minute default (`glacier.security.ws-handshake.max-per-ip-per-minute`).

**Rationale**: Without a CONNECT rate-limit, a cookie-rotation DoS is possible: an attacker creates fresh wallIds (each bypasses per-wallId limits) and floods the WS upgrade endpoint. `FallbackRateLimiter` only fronts HTTP; the WS upgrade is unguarded without this interceptor.

**Alternatives considered**: Rely solely on frame-size cap. Rejected — insufficient for multi-connection DoS.

**Source**: secure-feature-planner CONFLICT 1 → ddd-tdd-architect Round 2 ACCEPT WITH ADAPT.

---

### ADR-PT-G7-01: API6 accepted residual risk — share-link wallId enumeration

**Decision**: API6 is closed at two levels:
1. `StompEnumerationIndistinguishabilityIT` — asserts identical STOMP rejection signal for foreign live wallId vs. non-existent wallId (protects against blind enumeration).
2. `SubscribeRateLimitInterceptor` (ChannelInterceptor on `clientInboundChannel`, key = sourceIp + wallId) — silently drops SUBSCRIBE frames above threshold; silent drop preserves indistinguishability; AUDIT event uses `LogScrubber.maskIp()`.

**Residual risk**: wallId values appearing in share-link URLs are semi-public. An attacker with a known wallId can still probe subscription topics, just at the rate-limited rate. Accepted because: (a) wallId is a 122-bit random UUID — even with a known wallId, the 50-hashtag-per-wallId cap means the subscription space is finite and small; (b) no sensitive data is exposed by subscription presence.

**Source**: secure-feature-planner CONFLICT 3 → ddd-tdd-architect Round 2 ACCEPT.

---

### ADR-PT-A06-01: Two Trivy modes with separate ignore files

**Decision**: `security.yml` invokes Trivy twice: (1) image mode (`ghcr.io/seism0saurus/glacier:tag`) — catches base-OS CVEs; (2) filesystem mode (`target/glacier-*.jar`) — catches Java dependency CVEs in the fat jar. Both modes are preceded by a precondition step (`docker manifest inspect` / jar existence check). Both ignore files (`.trivyignore` and `.trivyignore-fs`) are validated by `TrivyignoreExpiryTest` which asserts `# expires: YYYY-MM-DD` on every suppression.

**Source**: secure-feature-planner CONFLICT 2 → ddd-tdd-architect Round 2 ACCEPT.

---

### ADR-PT-API5-01: Endpoint inventory via `RequestMappingHandlerMapping` reflection

**Decision**: `EndpointInventoryTest` (Surefire, `@SpringBootTest(webEnvironment=NONE)`) iterates `RequestMappingHandlerMapping#getHandlerMethods()` and compares against an explicit allowlist. A new HTTP endpoint missing from the allowlist causes the test to fail, forcing the endpoint to be added to both the test and `OWASP_COVERAGE_MATRIX.md`. This doubles as the API9 (inventory management) matrix cell.

**Source**: ddd-tdd-architect Round 1.

## Security Requirements

| SR | Requirement | Standard |
|----|-------------|----------|
| SR-OI-01 | `isOptedIn(Status, String)` extracted and called in all three `StompCallback` event handlers | [A04:2021](https://owasp.org/Top10/A04_2021-Insecure_Design/) |
| SR-OI-02 | UT-sec-04c: a test iterates all `sendMessage`/`processStatus*` methods via reflection and asserts each calls `isOptedIn` | [A04:2021](https://owasp.org/Top10/A04_2021-Insecure_Design/) |
| SR-WS-01 | `HandshakeRateLimitInterceptor` registered on `/websocket` and `/share-view-ws`; rejects CONNECT above threshold with HTTP 429 | [API4:2023](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/), [CWE-400](https://cwe.mitre.org/data/definitions/400.html) |
| SR-WS-02 | `SubscribeRateLimitInterceptor` registered on `clientInboundChannel`; silently drops SUBSCRIBE frames above threshold; AUDIT event emitted | [API6:2023](https://owasp.org/API-Security/editions/2023/en/0xa6-unrestricted-access-to-sensitive-business-flows/) |
| SR-WS-03 | `FORWARD_HEADERS_STRATEGY=FRAMEWORK` must be set in the production deployment compose file (`infrastructure/docker-compose.yaml`); Spring Boot's servlet-stack `ForwardedHeaderFilter` then unwraps `X-Forwarded-For` so interceptors see the real client IP via `request.getRemoteAddr()` | [CWE-290](https://cwe.mitre.org/data/definitions/290.html) |
| SR-WS-04 | Both interceptors fail-open on `Throwable` (log WARN, allow connection) — consistent with `FallbackRateLimiter` pattern | [A04:2021](https://owasp.org/Top10/A04_2021-Insecure_Design/) secure default |
| SR-WS-05 | Explicit `setMessageSizeLimit`, `setSendBufferSizeLimit`, `setSendTimeLimit` in `configureWebSocketTransport`; values from `application.properties` | [API4:2023](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/) |
| SR-WS-06 | Rate-limit thresholds operator-tunable via `glacier.security.ws-handshake.*` / `glacier.security.ws-subscribe.*` | [API4:2023](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/) |
| SR-LOG-WS-01 | AUDIT events `ws.handshake.rate_limited` and `ws.subscribe.rate_limited` log `ip-hash={}` via `LogScrubber.maskIp(ip)`, NOT `urlHostHash()` | [GDPR Recital 30](https://www.privacy-regulation.eu/en/recital-30-GDPR.htm), [CWE-117](https://cwe.mitre.org/data/definitions/117.html) |
| SR-LOG-WS-02 | Regression test asserts masked-IP form present AND `hash8("unparseable")` sentinel absent in both AUDIT event types | [CWE-117](https://cwe.mitre.org/data/definitions/117.html) |
| SR-CI-01 | Trivy fs step targets `target/glacier-*.jar`; fails on HIGH/CRITICAL not in `.trivyignore-fs` | [A06:2021](https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/) |
| SR-CI-02 | `docker manifest inspect` precondition before Trivy image step; `ls target/glacier-*.jar` precondition before Trivy fs step | [A06:2021](https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/) |
| SR-CI-03 | `TrivyignoreExpiryTest` validates `# expires: YYYY-MM-DD` on every suppression in both ignore files; fails if any expiry is past | [A06:2021](https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/) |
| SR-MTX-01 | `OWASP_COVERAGE_MATRIX.md` has zero blank cells; each cell = test ID or `N/A — [structural justification]` | Pentest automator §4d |
| SR-MTX-02 | `EndpointInventoryTest` allowlist is the authoritative endpoint list; any new HTTP endpoint not in allowlist fails CI | [API9:2023](https://owasp.org/API-Security/editions/2023/en/0xa9-improper-inventory-management/) |
| SR-MODE-WS-01 | All new test classes declare mode applicability (live/fallback/killswitch/insecure) in class Javadoc; interceptors marked N/A for killswitch (no WS in killswitch) | `glacier-fallback-mode-discipline` |

## Accepted Residual Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-WS-01 | Rate-limit buckets in-memory (single-instance only) | Glacier currently runs as a single instance; horizontal scaling is not in scope; must be revisited before HA deployment |
| AR-WS-02 | Source IP trusted from `X-Forwarded-For` via single Traefik proxy; bypass requires internal cluster access | Internal cluster access is out of threat model scope |
| AR-WS-03 | Known wallId allows subscription-topic probing at rate-limited rate | 122-bit UUID + 50-hashtag cap limits information gain; no sensitive data exposed by subscription presence |

## Implementation Lane Partition (Phase 2)

| Lane | Agent | Scope |
|------|-------|-------|
| L1 — Domain/application tests | `tdd-ddd-implementer` | `StompCallbackOptInEnforcementTest` (including `isOptedIn()` production helper), `StompPayloadDeserializationIT`, `StompMassAssignmentIT`, `StompCallbackHostileResponseTest`, `security-opt-in.spec.ts` |
| L2 — Security-boundary tests + production interceptors | `secure-tdd-implementer` | `HandshakeRateLimitInterceptor`, `SubscribeRateLimitInterceptor`, `WebSocketConfiguration` changes, `WebSocketFrameSizeLimitIT`, `EndpointInventoryTest`, `StompEnumerationIndistinguishabilityIT` |
| L3 — CI + docs | `devops-infra-engineer` | Trivy fs step + preconditions in `security.yml`, `TrivyignoreExpiryTest`, `infrastructure/security/.trivyignore-fs`, `OWASP_COVERAGE_MATRIX.md`, `SECURITY_TESTS.md` |

**Sequencing**: L2 first (production code changes before tests that depend on them); L1 second (pure test additions); L3 third (docs reference test IDs from L1+L2).

## Resolved Conflicts

### CONFLICT 1 — API4 CONNECT rate-limit gap
**secure-feature-planner**: FallbackRateLimiter only covers HTTP; WS handshake unguarded against cookie-rotation DoS.
**ddd-tdd-architect**: accepted; `HandshakeRateLimitInterceptor` (Bucket4j per IP) added to L2.
**Resolution (2026-04-30)**: HandshakeRateLimitInterceptor on `/websocket` + `/share-view-ws` added to plan.

### CONFLICT 2 — Trivy silent-on-missing-tag
**secure-feature-planner**: Trivy image scan may succeed silently on missing tag.
**ddd-tdd-architect**: accepted; `docker manifest inspect` precondition owned by devops-infra-engineer.
**Resolution (2026-04-30)**: precondition steps added to CI lane.

### CONFLICT 3 — API6 share-link wallId enumeration
**secure-feature-planner**: indistinguishability test alone insufficient for semi-public wallIds from share links.
**ddd-tdd-architect**: accepted; `SubscribeRateLimitInterceptor` + ADR-PT-G7-01 accepted-risk.
**Resolution (2026-04-30)**: SubscribeRateLimitInterceptor added to L2; AR-WS-03 accepted.

### DISPUTE on ADR-PT-A04-01 — typed-path divergence
**secure-feature-planner**: divergence is fragile, not intentional.
**ddd-tdd-architect**: accepted; `isOptedIn()` helper extracted and called on all paths.
**Resolution (2026-04-30)**: isOptedIn() production change added to L1.

### ADAPT — matrix scope dynamic
**secure-feature-planner**: endpoint count > 10; hardcoded cell count wrong.
**ddd-tdd-architect**: accepted; scope = EndpointInventoryTest's discovered set.
**Resolution (2026-04-30)**: OWASP_COVERAGE_MATRIX.md scope section references EndpointInventoryTest.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- [glacier-pentest-automator agent](../../.claude/agents/glacier-pentest-automator.md) — OWASP matrix authority (Steps 4a–4e)
- [Pentest Findings F-1 + F-2 — Planning](2026-04-28-planning-pentest-findings.md)
- [Pentest Findings F-1 + F-2 — Acceptance](2026-04-28-acceptance-pentest-findings.md)
- [OWASP Web Top 10 (2021)](https://owasp.org/www-project-top-ten/)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
- [CWE-400: Uncontrolled Resource Consumption](https://cwe.mitre.org/data/definitions/400.html)
- [CWE-290: Authentication Bypass by Spoofing](https://cwe.mitre.org/data/definitions/290.html)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [GDPR Recital 30](https://www.privacy-regulation.eu/en/recital-30-GDPR.htm)
