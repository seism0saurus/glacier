# Security Test Suite — Glacier

## Overview

This document describes the Glacier security test suite: what it covers, how to run it locally,
how it maps to CI jobs, and how to extend it safely.

The test suite closes the gaps identified in the OWASP coverage matrix and is the primary
evidence for the security posture described in
[`OWASP_COVERAGE_MATRIX.md`](OWASP_COVERAGE_MATRIX.md).

The suite is structured as a full test pyramid:

1. **Unit tests** (Surefire, `*Test.java`) — fast, isolated, no Spring context (or `webEnvironment=MOCK`)
2. **Integration tests** (Failsafe, `*IT.java`) — full Spring context, real WebSocket connections
3. **End-to-end tests** (Playwright) — full stack against containerised Mastodon
4. **CI scanning** (ZAP baseline, Trivy image scan, Trivy filesystem scan)

---

## Security Test Layers

### Layer 1: Unit Tests (Surefire)

Location: `src/test/java/de/seism0saurus/glacier/security/`

| Test class | Test IDs | OWASP coverage | WSTG ID | ASVS Shortcode | Proactive Control |
|------------|----------|----------------|---------|----------------|-------------------|
| `StompCallbackOptInEnforcementTest` | UT-sec-01..04c, T-empty-shorthandle-01..05 | A04:2021 — bot opt-in enforced on ALL event paths (GenericMessage, StatusCreated, StatusEdited); SR-NEW-02 — empty shortHandle rejected | WSTG-AUTHZ-04 | V2.2.1 (L1) | C3 |
| `StompCallbackHostileResponseTest` | UT-sec-07..11 | API10:2023 — null mentions, malformed timestamps, null stream, empty URL, oversize ID handled safely | WSTG-INPV-11 | V2.2.1 (L1) | C4 |
| `EndpointInventoryTest` | UT-sec-06 | API5:2023 + API9:2023 — authoritative HTTP endpoint inventory; undocumented routes fail CI | WSTG-APIT-01, WSTG-AUTHZ-01 | n/a | C1 |
| `WebSocketEndpointInventoryTest` | (multiple) | API9:2023 — authoritative STOMP destination inventory | WSTG-APIT-01 | n/a | C1 |
| `HandshakeRateLimitInterceptorTest` | UT-WS-RL-01..05 | API4:2023 — IP-based WS CONNECT rate limit; fail-open on Throwable; masked-IP AUDIT logging | WSTG-ATHN-03 | V2.4.1 (L2) | C6 |
| `SubscribeRateLimitInterceptorTest` | UT-SUBRL-01..05 | API6:2023 — STOMP SUBSCRIBE rate limit; silent drop; masked-IP + hashed-wallId AUDIT logging | WSTG-ATHN-03 | V2.4.1 (L2) | C6 |
| `TrivyignoreExpiryTest` | SR-CI-03 | A06:2021 — validates `# expires: YYYY-MM-DD` on every suppression in both Trivy ignore files | n/a | n/a | C2 |
| `SubscriptionHashtagValidationFindingTest` | ADR-PT-03 | A03:2021 — hashtag input validated with `@Pattern(^[\p{L}\p{N}_]{1,50}$)` | WSTG-INPV-05 | V2.2.1 (L1) | C3 |
| `StompCallbackEmbedSsrfFindingTest` | ADR-PT-01/02 | A10:2021 — `SafeUrlValidator` blocks loopback, RFC1918, cloud-metadata, link-local, non-HTTP(S) URLs | WSTG-INPV-19 | V1.3.6 (L2) | C10 |
| `WallTopicAuthInterceptorTest` | (multiple) | A01:2021 + API1:2023 — cross-principal topic subscription silently dropped; AUDIT event logged | WSTG-AUTHZ-04 | n/a | C7 |
| `CookieBasedFallbackAuthGuardTest` | (multiple) | A07:2021 — wallId cookie validation; blank/short/missing values rejected | WSTG-SESS-02 | V3.3.1 (L1) | C6 |
| `OwaspMatrixCookieAttributesLockstepTest` | UT-sec-LOCK-01a/b/c, UT-sec-LOCK-03 | SR-NEW-04 — lockstep test prevents SameSite/Secure/HttpOnly documentation drift between matrix and code | WSTG-SESS-02 | V3.3.1 (L1), V3.3.2 (L2), V3.3.4 (L2) | C5 |
| `HttpMethodRejectFilterTest` | (multiple) | A05:2021 — `HttpMethodRejectFilter` rejects disallowed HTTP methods (TRACE, arbitrary verbs) at Servlet filter level | WSTG-CONF-06 | V3.5.3 (L1), V4.1.4 (L3) | C8 |
| `AuditEventNameLockstepTest` | (multiple) | SR-8 — AUDIT event name constants verified against production logger usage | n/a | n/a | C9 |
| `LogStabilityTest` | (multiple) | D-13/SR-8/CWE-117 — log output stability and sensitive-data absence | n/a | n/a | C9 |

Additional pre-existing security unit tests (outside `security/` package):

| Test class | Coverage | WSTG ID | ASVS Shortcode | Proactive Control |
|------------|----------|---------|----------------|-------------------|
| `RawWallIdLogHygieneTest` (two copies: `mastodon/` + `util/`) | D-13/SR-8/CWE-117 — raw wallId never appears in JSON log output | n/a | n/a | C9 |
| `LogScrubberTest` | CWE-117 — IP masking, hash8, URL scrubbing utilities | n/a | n/a | C9 |

### Layer 2: Integration Tests (Failsafe)

Location: `src/test/java/de/seism0saurus/glacier/security/`

| Test class | Test IDs | OWASP coverage | WSTG ID | ASVS Shortcode | Proactive Control |
|------------|----------|----------------|---------|----------------|-------------------|
| `StompPayloadDeserializationIT` | IT-sec-01..03 | A08:2021 — `@JsonIgnoreProperties(ignoreUnknown=true)` on STOMP DTOs; unknown fields silently ignored | WSTG-INPV-11 | V2.2.1 (L1) | C3 |
| `StompMassAssignmentIT` | IT-sec-04..06 | API3:2023 — injected `principal`, `rejection`, `isSubscribed` fields cannot override server state | WSTG-AUTHZ-04 | n/a | C7 |
| `WebSocketFrameSizeLimitIT` | IT-sec-07a/b/c | API4:2023 — 64 KB frame-size cap enforced; oversize frames rejected without server crash; no raw hashtag in logs | WSTG-CONF-06 | V2.4.1 (L2) | C3 |
| `StompEnumerationIndistinguishabilityIT` | IT-sec-08..09 | API6:2023 — identical silence for foreign live wallId vs. non-existent wallId; AUDIT log uses hashed form | WSTG-AUTHZ-04 | n/a | C7 |
| `ResponseBodySecretLeakIT` | (multiple) | A02:2021 + A09:2021 — no secrets, stack traces, or internal paths echoed in HTTP error responses | WSTG-ERRH-01 | V16.5.1 (L2) | C4, C9 |
| `CookieEmissionIT` | (multiple) | A02:2021 + A07:2021 — wallId and CSRF cookies carry Secure, HttpOnly, SameSite=Lax flags verified end-to-end | WSTG-SESS-02 | V3.3.1 (L1), V3.3.2 (L2), V3.3.4 (L2) | C5 |
| `HttpMethodHardeningIT` | (multiple) | A05:2021 — `HttpMethodRejectFilter` (servlet filter) rejects TRACE/TRACK at HIGHEST_PRECEDENCE; Spring MVC default 405 for undeclared verbs; OPTIONS permitted for CORS preflight | WSTG-CONF-06 | V3.5.3 (L1), V4.1.4 (L3) | C8 |
| `CorsHardeningIT` | (multiple) | A05:2021 — CORS allowlist validated; cross-origin requests from unlisted origins rejected | WSTG-CONF-07 | n/a | C8 |

### Layer 3: End-to-End Tests (Playwright)

Location: `frontend/e2e/workflows/` and `frontend/src/app/`

| Spec file | Test IDs | OWASP coverage | WSTG ID | ASVS Shortcode | Proactive Control |
|-----------|----------|----------------|---------|----------------|-------------------|
| `security-opt-in.spec.ts` | PW-sec-01 | A04:2021 — bot opt-in enforced E2E: toot without bot mention not displayed; toot with mention appears | WSTG-AUTHZ-04 | V2.2.1 (L1) | C3 |
| `toot.component.spec.ts` | AC-02 | A05:2021 — iframe sandbox attribute carries exactly `allow-scripts allow-popups allow-popups-to-escape-sandbox`; allow-same-origin absent | WSTG-CLNT-09 | V3.4.6 (L2) | C8 |

Playwright specs run in the `chromium` project (live WebSocket streaming mode). The `security-opt-in.spec.ts` spec requires a running Mastodon instance and posts real toots via `mastodon-client.ts`.

### Layer 4: CI Scanning

Configured in `.github/workflows/security.yml`:

| Scan | Tool | Coverage |
|------|------|----------|
| ZAP baseline scan | OWASP ZAP (passive) | A01, A05, A07, API8 — HTTP security headers, cookie flags, response analysis |
| ZAP diff against allowlist | `diff-zap-baseline.py` | New findings above Medium threshold fail CI |
| Header audit | `header-audit.sh` | Security response headers on all endpoints |
| WebSocket BOLA probe | `ws-bola-probe.py` | API1:2023 — verifies cross-wallId STOMP topic subscription is rejected |
| Trivy image scan | Trivy (image mode) | A06:2021 — base OS + installed packages in the Docker image; HIGH/CRITICAL CVEs fail CI |
| Trivy filesystem scan | Trivy (fs mode) | A06:2021 (SR-CI-01) — Java dependency CVEs in `target/` jar; HIGH/CRITICAL not in `.trivyignore-fs` fail CI |

---

## Running Tests Locally

**Prerequisites**: `JAVA_HOME` must point to a Java 23 installation.

```bash
export JAVA_HOME=/home/ulrich.viefhaus/.jdks/temurin-23.0.2
```

### All unit + integration security tests

```bash
./mvnw verify
```

This runs the full Surefire + Failsafe suite, including Jacoco coverage enforcement (instruction >= 45%, branch >= 35%). Security tests are picked up automatically from the `de.seism0saurus.glacier.security` package.

### Single security test class

```bash
# Surefire unit test
./mvnw -Dtest=TrivyignoreExpiryTest test

# Failsafe integration test
./mvnw -Dit.test=StompPayloadDeserializationIT verify -DskipUnitTests
```

### Playwright security E2E test

Requires the dockerised Mastodon stack (see `infrastructure/README.md`):

```bash
cd frontend && npx playwright test e2e/workflows/security-opt-in.spec.ts --project chromium
```

Or via the full one-shot Docker Compose flow:

```bash
cd infrastructure && tar -xf infrastructure-content.tar.gz -C ./
docker compose -f docker-compose.yaml up --build --abort-on-container-exit playwright --exit-code-from playwright
```

### ZAP baseline scan (local)

Requires the Glacier stack running locally via Docker Compose:

```bash
cd infrastructure && tar -xf infrastructure-content.tar.gz -C ./
docker compose -f docker-compose.yaml -f docker-compose.override.security.yaml up --build -d glacier proxy

docker run --rm \
  --network=infrastructure_mastodon \
  -v "$(pwd)/security:/zap/wrk" \
  -t ghcr.io/zaproxy/zaproxy:stable \
  zap.sh -cmd -autorun /zap/wrk/zap/baseline.yaml
```

ZAP output: `infrastructure/security/zap-baseline.json` and `zap-baseline.html`.

### Trivy filesystem scan (local)

Requires the jar to be built first:

```bash
./mvnw package -DskipTests
trivy fs target/glacier-*.jar --severity HIGH,CRITICAL --ignore-unfixed \
  --ignorefile infrastructure/security/.trivyignore-fs
```

---

## CI Mapping

| GitHub Actions job | Test layers | Trigger |
|-------------------|-------------|---------|
| `verify.yml` — `build-and-test` | Unit tests, Integration tests, Jacoco | Push to any branch |
| `security.yml` — `baseline` | ZAP, Header audit, BOLA probe, Trivy image + fs scans | Push to `*.*.*` release branches; `workflow_dispatch` |
| `security.yml` — `full-scan` | ZAP active scan | `workflow_dispatch` only (manual, ~30 min) |

The Playwright E2E suite (including `security-opt-in.spec.ts`) runs within the `verify.yml` when the `-P RunE2ETest` Maven profile is active.

---

## Adding a New HTTP Endpoint

When adding a new `@RestController` endpoint:

1. Add the route (`METHOD:pattern`) to `EndpointInventoryTest.AUTHORITATIVE_ENDPOINT_ALLOWLIST`.
   The test will fail CI until the route is listed here.
2. Add a row for the new endpoint to both OWASP tables in `OWASP_COVERAGE_MATRIX.md`.
   Every cell must be filled (test ID or `N/A — [structural justification]`). Zero blank cells.
3. Write at minimum one unit test and one integration test covering the OWASP items that
   are not structurally N/A for the new endpoint.
4. If the endpoint accepts user input (query params, request body), add a Bean Validation
   (`@Pattern`, `@NotBlank`, `@Size`) test following the pattern in `SubscriptionHashtagValidationFindingTest`.
5. Add the new test class(es) to the appropriate Layer table in this file, filling in all three
   traceability columns: **WSTG ID** (from WSTG 4.2), **ASVS Shortcode** (from ASVS 5.0), and
   **Proactive Control** (C1–C10 from OWASP Proactive Controls 2024). Use `n/a` only when
   no standard requirement directly applies (e.g. governance/structural tests like `TrivyignoreExpiryTest`).
6. Update the `## Standards Traceability` section of `OWASP_COVERAGE_MATRIX.md` to include
   the new WSTG/ASVS/Proactive mapping rows for the tests added in step 5.

---

## Adding a New STOMP Destination

When adding a new STOMP application destination or topic:

1. Add it to the Endpoint Inventory table and both OWASP tables in `OWASP_COVERAGE_MATRIX.md`.
2. If the destination carries user input, add an integration test following the pattern in
   `StompPayloadDeserializationIT` (unknown fields ignored) and `StompMassAssignmentIT`
   (server-set properties not overridable from the wire).
3. If the destination is a subscription topic scoped by wallId, ensure `WallTopicAuthInterceptor`
   covers it; add a test to `WallTopicAuthInterceptorTest`.
4. Add the new test class(es) to the Layer 2 table in this file, filling in all three traceability
   columns: **WSTG ID**, **ASVS Shortcode**, and **Proactive Control**. Use `n/a` only when no
   standard requirement directly applies.
5. Update the `## Standards Traceability` section of `OWASP_COVERAGE_MATRIX.md` accordingly.

---

## Adding a Trivy CVE Suppression

When suppressing a CVE in either Trivy ignore file:

1. **Choose the right file**:
   - `infrastructure/security/.trivyignore` — for CVEs in the Docker image base OS / system packages.
   - `infrastructure/security/.trivyignore-fs` — for CVEs in Java jar dependencies.
2. **Add the suppression with a mandatory expiry comment**:
   ```
   CVE-2024-XXXX # <justification>; expires: 2026-12-31
   ```
   The `expires: YYYY-MM-DD` part is **required**. `TrivyignoreExpiryTest` will fail the build
   if the format is missing or the date has already passed.
3. **Justification must include**:
   - Why the CVE does not apply to Glacier (e.g., "affects XML parsing path that Glacier never invokes"), OR
   - Why the fix is not yet available and when re-triage will happen.
4. **Re-triage on or before the expiry date** — the build will fail after the date passes.
5. Open a PR with the suppression and the justification; another engineer must review.

---

## Updating Standard Versions

When a new ASVS, WSTG, or Proactive Controls version is published:

1. **Update the version pin** in the `## Standards Traceability` section of
   `OWASP_COVERAGE_MATRIX.md` (the `Standards versions:` line at the top of that section).
2. **Review all WSTG IDs** in both files (OWASP_COVERAGE_MATRIX.md and this file) for
   renamed or renumbered test IDs — the WSTG occasionally restructures its test numbering
   between releases. Each `WSTG-XXXX-NN` token must resolve to a valid test in the new version.
3. **Review all ASVS shortcodes** (e.g. `V3.3.1`, `V2.2.1`) for renaming or restructuring.
   ASVS 5.0 reorganised several sections relative to 4.0 — future editions may do the same.
4. **Run `OwaspMatrixCookieAttributesLockstepTest`** to verify no documentation drift was
   introduced during the re-pinning (`./mvnw -Dtest=OwaspMatrixCookieAttributesLockstepTest test`).
5. **Update the WSTG ID and ASVS Shortcode columns** in the Layer tables in this file if any
   IDs changed.
6. Open a PR for the version-pin update; another engineer must review it — standard version
   changes can silently remove requirements that Glacier relied on for compliance coverage.

---

## Matrix Maintenance

Update `OWASP_COVERAGE_MATRIX.md` when:

- A new HTTP or STOMP endpoint is added (new row in both tables).
- An existing test is renamed, moved, or removed (update the cell citation).
- A new OWASP Top 10 edition is published and replaces a prior entry.
- A gap is identified in a cell that was previously `N/A` (upgrade to a test ID).
- An accepted residual risk is resolved or re-evaluated.

The matrix scope is defined by `EndpointInventoryTest.AUTHORITATIVE_ENDPOINT_ALLOWLIST` for HTTP
endpoints and by `WebSocketConfiguration.java` for STOMP destinations.

---

## Log Hygiene Rules (D-13 / SR-8 / CWE-117)

All security-relevant log events must follow the rules enforced by `glacier-structured-logging-logback`:

- **wallId** — never log raw UUID; use `LogScrubber.hash8(wallId)` with key `principal-hash=`.
- **IP address** — never log raw IP; use `LogScrubber.maskIp(ip)` with key `ip-hash=`.
  The masked form replaces the last octet with `xxx`.
- **Hashtag** — never log raw hashtag; use `LogScrubber.hash8(hashtag)` with key `hashtag=`.
- **Access tokens / Authorization headers** — strip at the log appender level via `LogScrubber`.
- **AUDIT logger** — use `LoggerFactory.getLogger("AUDIT")` for rate-limit events
  (`ws.handshake.rate_limited`, `ws.subscribe.rate_limited`) and cross-principal rejections.

Tests enforcing these rules:
- `RawWallIdLogHygieneTest` — wallId masking in StompCallback and SubscriptionManagerImpl
- `HandshakeRateLimitInterceptorTest` (UT-WS-RL-04) — masked IP in AUDIT log
- `SubscribeRateLimitInterceptorTest` (UT-SUBRL-05) — masked IP + hashed wallId in AUDIT log
- `StompEnumerationIndistinguishabilityIT` (IT-sec-09) — foreign wallId not logged raw in AUDIT
- `WebSocketFrameSizeLimitIT` (IT-sec-07c) — hashtag not logged after oversize frame
- `StompCallbackOptInEnforcementTest` (UT-sec-01/04) — wallId + hashtag not logged on opt-in drop
