# Decision Record: Comprehensive Quality Review — Planning

Date: 2026-05-07
Phase: Planning
Agents: ddd-tdd-architect (R1+R2), secure-feature-planner (R1+R2), ux-ui-designer (R1)
Status: Accepted

## Summary

A cross-cutting review of code, CI/CD, tests, and documentation identified 46 actionable enhancement items (18 P1, 18 P2, 10 P3) across four dimensions: reliability/concurrency, security, CI/CD infrastructure, and frontend UX/a11y/i18n. Phase 2 implements all P1 and P2 items across four parallel lanes. P3 is deferred.

## Key Decisions

### Lane A — Reliability / Concurrency (`tdd-ddd-implementer`)

**Decision**: Replace plain `HashMap` fields in `SubscriptionManagerImpl.subscriptions` and `SubscriptionListener.disconnectTimer` with `ConcurrentHashMap` and atomic `compute`/`computeIfAbsent` patterns. Add concurrency tests.
**Rationale**: Both singletons are mutated from virtual threads, STOMP event-listener threads, and timer threads simultaneously — classic `HashMap` corruption setup. No known incident yet, but latent.
**Alternatives considered**: Synchronized blocks — rejected as coarser and harder to test.
**Source**: arch_plan §2 Q-01/Q-02; arch_review P1-01/P1-02.

**Additional Lane A items**: Mockito BOM bump (P1-05); `BigboneKeepAliveIT` pinning Thread.sleep invariant (P1-07); `RestTemplate` → Apache HttpClient connection pool (P2-01); exponential back-off on Bigbone reconnect (P2-02); `MastodonConfiguration` 4-way nesting refactor (P2-09); `MessageDeliveryException` retry in `MessageCacheImpl` (P2-12); HEAD-check for edits without prior create (P2-13).

### Lane B — Security (`secure-tdd-implementer`)

**Decision**: Promote `glacier.devmode=true + withTrustAllCerts()` to CRITICAL; add startup-fail guard.
**Rationale**: A misconfigured prod deployment with `devmode=true` enables federation MITM on the Bigbone streaming socket. The entire wall content becomes attacker-controlled.
**Source**: security_final Sec-01/P1-08.

**Decision**: Pin `bigbone:2.0.0-SNAPSHOT` by SHA256 via `maven-enforcer requireReleaseDeps`.
**Rationale**: SNAPSHOT is mutable — any upstream commit ships silently to prod. SHA256 pin is the interim mitigation until upstream cuts a release.
**Source**: security_final Sec-02/P1-09.

**Decision**: Introduce shared `IpAddressClassifier` helper as sole consumer of `InetAddress.isXAddress` calls; protect with ArchUnit gate (Sec-24).
**Rationale**: Both GDPR log anonymisation (`InformationController.anonymiseIp`) and SSRF blocklist (`DefaultSafeUrlValidator`) fail on IPv4-mapped IPv6 (`::ffff:a.b.c.d`). A single helper + ArchUnit gate prevents future drift.
**Source**: security_final Sec-11/P1-10, Sec-15/P1-13, Sec-24.

**Additional Lane B items (P1)**: `glacier.domain` non-localhost/non-CRLF validation (Sec-12/P1-11); `ClientIpResolver` with `glacier.proxy.trusted-hops` for XFF spoofing (Sec-14/P1-12); `ShareViewController.getCatalog` with owner-scoped auth — unauthorized → unified 401 (Sec-16/P1-04 revised); startup guard `cookie.secure=true ∧ mastodon.https=false` (Sec-23).

**Additional Lane B items (P2)**: FallbackController unified response path + `SecureRandom` jitter with statistical IT (Sec-13/P2-15); CORS allowlist audit + ADR (Sec-17/P2-16); PITest curated set expanded with `HandshakeRateLimitInterceptor`, `ChannelInterceptor`, `ShareSecurityHeadersFilter`, `IpAddressClassifier` (Sec-18/P2-17); rate-limit AUDIT event naming convention test (Sec-25); PITest mutation regex lockstep test (P2-11).

### Lane C — CI/CD & Operator Docs (`devops-infra-engineer`)

**Decision**: Consolidate four near-duplicate Playwright e2e CI jobs into a matrix strategy.
**Rationale**: Each job independently boots the full docker-compose Mastodon stack — wall-clock CI dominated by stack warmup duplication. Matrix reduces cost by ~3×.
**Source**: arch_plan §8 CI-01.

**Additional Lane C items**: SBOM generation — CycloneDX for the jar, syft for the docker image (P2-06); Dependabot targets `main` branch (P2-07); reusable `setup-java-cache` workflow (P2-08); mode-specific debug recipes in `infrastructure/README.md` (P2-10); `GLACIER_SHARE_IMGPROXY_HMAC_SECRET` promoted to "Required variables" in `docs/ADMIN.md` (P1-06).

### Lane D — Frontend / UX (`frontend-designer`, sequential D.1 → D.2 → D.3)

**Decision**: Fix three WCAG-A failures before any UX polish (Lane D.1 gates D.2 and D.3).
**Rationale**: Iframes without `title` (WCAG 4.1.2), click-divs in place of buttons (WCAG 2.1.1), and `<html lang="en">` with German source (WCAG 3.1.1) are baseline failures that would fail any EAA/BFSG audit.
**Source**: ux_plan A11Y-F-01/02/03/04; arch_review P1-14/15/16.

**Lane D.1 — A11y Critical (P1)**: iframe `[title]` attribute; `<div (click)>` → `<button>` + confirm dialogs (Cancel All / Clear Toots / Legal); `<html lang="de">` default + `main.ts` runtime update; skip link in `<app-root>`.

**Lane D.2 — i18n Legacy Zone (P2)**: HashtagComponent, HeaderComponent, FooterComponent, GdprComponent heading — all missing `i18n` attributes and catalog keys; new CI lint `frontend/scripts/check-i18n-keys.mjs` wired into Maven `frontend-maven-plugin`.

**Lane D.3 — UX Polish (P1+P2)**: Share-dialog entry point in header — feature is currently zero-entry-point dead code from a UX perspective (P1-17); `Math.max(1, Math.floor(width/408))` mobile reflow fix (P1-18); Angular signals for `getTootsForColumn` memoisation; empty-wall state with focusable CTA; `SubscriptionService` split into `SubscriptionStateService` + `SubscriptionPersistence` + `SubscriptionStompClient`.

## Resolved Conflicts

### Sec-13 jitter RNG source
**ddd-tdd-architect**: "unified response path + sleep jitter" — silent on RNG source.
**secure-feature-planner**: Sleep jitter MUST use `SecureRandom` (ASVS V6.3.1); `ThreadLocalRandom` is predictable and defeats the timing-channel mitigation.
**Resolution (2026-05-07)**: A — `SecureRandom` required; `FallbackJitterRngSourceTest` asserts it.

### Sec-13 IT methodology
**ddd-tdd-architect**: p50/p95 over 1000 invocations, |Δ| < 5ms.
**secure-feature-planner**: n≥1000 with KS-test or fixed-band p95, documented null-hypothesis rejection threshold, 1 warm-up, 1 CI retry max. Deterministic mean-comparison is a false-green.
**Resolution (2026-05-07)**: A — KS-test or p95 fixed-band (20 ms), 1 warm-up, 1 CI retry max.

### Sec-11/15 ArchUnit gate (Sec-24)
**ddd-tdd-architect**: `IpAddressClassifier` helper introduced without structural gate.
**secure-feature-planner**: ArchUnit gate forbidding direct `InetAddress.isXAddress` calls outside `IpAddressClassifier` is required to prevent future drift (established preventive-sibling-invariant pattern).
**Resolution (2026-05-07)**: A — Sec-24 added to Lane B; ArchUnit gate commits in same PR as the helper.

## User Approval
Date: 2026-05-07
Approval message (verbatim): "approve"

## Open Risks

1. **Bigbone SNAPSHOT coordination** (Sec-02): The SHA256 pin requires coordination with upstream bigbone authors for a stable release. The pin may break if upstream rebases. Mitigation: `-Pallow-snapshot` escape hatch documented.
2. **`InMemoryShareLinkRepository` restart-loss** (R-05): Share links are lost on server restart. Accepted as a known limitation (P3 to add SQLite persistence). The ADMIN guide will warn operators.
3. **GdprComponent body i18n** (I18N-F-04): Only heading and close button are i18n'd. Full legal body extraction is deferred (legal/jurisdictional complexity). Documented as a known gap.
4. **Sec-17 CORS audit outcome**: If the CORS audit finds active misconfiguration, it may need to escalate to P1 mid-Phase-2. Risk assessed as low based on existing passing tests.

## P3 Items (deferred, out of Phase 2 scope)

P3-01: `ShortHandle` + `EventTypeMapping` value objects; P3-02: Angular signals for wall column memoisation (superseded by D.3 signal work); P3-03: ADR index by topic; P3-04: README versioning policy; P3-05: Share-link SQLite persistence; P3-06: Cookie-read rate-limit; P3-07: CodeQL cache-key fix; P3-08: Javadoc "ressources" typo cleanup; P3-09: Operational modes user-facing README; P3-10: Operator config `@ConfigurationProperties` `@Pattern` validation.

## References

- arch_plan: ddd-tdd-architect Round 1 output (2026-05-07)
- arch_review: ddd-tdd-architect Round 2 output (2026-05-07)
- security_final: secure-feature-planner Round 2 output (2026-05-07)
- ux_plan: ux-ui-designer Round 1 output (2026-05-07)
- docs/decisions/2026-05-06-acceptance-csrf-cookie-single-emission.md
- docs/decisions/2026-05-04-acceptance-owasp-standards-integration.md
- docs/decisions/2026-05-01-acceptance-fuzz-mutation-testing.md
