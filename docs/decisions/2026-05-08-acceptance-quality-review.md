# Decision Record: Comprehensive Quality Review — Phase 3 Acceptance

Date: 2026-05-08
Phase: Acceptance
Agents: security-auditor (Round 1), acceptance-test-auditor (Round 1)
Status: **ACCEPTED**

## Summary

All 38 P1 and P2 items across four implementation lanes (A Reliability, B Security, C CI/CD, D Frontend/UX) are verified implemented, tested, and green. One security finding (XFF off-by-one, HIGH) was identified and fixed in-phase. Three items are explicitly deferred as known gaps.

## Acceptance verdict

**ACCEPTED** — User approval received 2026-05-08.

## Evidence

- `./mvnw verify`: **BUILD SUCCESS** — 315 integration tests + all unit tests pass (commit `732c47e`).
- Karma/Jasmine frontend suite: **558/558 tests pass**.
- Security audit: **PASSED-WITH-FINDINGS** → finding F-1 fixed before approval.
- Acceptance audit: **ACCEPTED-WITH-GAPS** → all gaps explicitly acknowledged below.

## Verified items

### Lane A — Reliability / Concurrency

| Item | Status | Location |
|------|--------|----------|
| Q-01 `SubscriptionManagerImpl` ConcurrentHashMap | PASS | `SubscriptionManagerImpl.java:60,126,148` |
| Q-02 `SubscriptionListener.disconnectTimer` ConcurrentHashMap | PASS | `SubscriptionListener.java:126,136` |
| P1-05 Mockito BOM | PASS | `pom.xml:268-276` |
| P1-07 `BigboneKeepAliveIT` Thread.sleep invariant | PASS | `BigboneKeepAliveIT.java` |
| P2-01 Apache HC5 connection pool | PASS | `EmbedRestTemplateConfiguration.java` |
| P2-02 Exponential back-off on Bigbone reconnect | PASS | `SubscriptionListener.java:205-248` |
| P2-09 `MastodonConfiguration` 4-helper refactor | PASS | `MastodonConfiguration.java:88-188` |
| P2-12 `MessageDeliveryException` retry | PASS | `MessageCacheImpl.java:256-314` |
| P2-13 Edit guard against unseen creates | PASS | `StompCallback.java:138-143` |

### Lane B — Security

| Item | Status | Location |
|------|--------|----------|
| Sec-01/P1-08 devmode startup-fail guard | PASS | `StartupSanityChecker.java:101-118` |
| Sec-11+Sec-15/P1-10 `IpAddressClassifier` IPv4-mapped-IPv6 | PASS | `IpAddressClassifier.java:46-94` |
| Sec-24 ArchUnit gate for `InetAddress.isXAddress` | PASS | `IpAddressClassifierArchitectureTest.java` |
| Sec-12/P1-11 `glacier.domain` CRLF/localhost validation | PASS | `DomainSafetyValidator.java` |
| Sec-14/P1-12 `ClientIpResolver` XFF trusted-hops | PASS | `ClientIpResolver.java` (+ F-1 fix) |
| Sec-16/P1-04 `ShareViewController` owner-scoped 401 | PASS | `ShareViewController.java:94-151` |
| Sec-23 cookie.secure startup guard | PASS | `StartupSanityChecker.java:27-29` |
| Sec-13/P2-15 `FallbackController` jitter + statistical IT | PASS | `FallbackController.java:82-86` |
| Sec-17/P2-16 CORS allowlist audit + ADR | PASS | `docs/decisions/2026-05-07-cors-audit.md` |
| Sec-18/P2-17 PITest curated set expansion | PASS | `pom.xml:97-102` |
| Sec-25 Rate-limit AUDIT naming convention test | PASS | `AuditEventNamingConventionTest.java` |
| P2-11 PITest mutation regex lockstep test | PASS | `MutationRegexLockstepTest.java` |

### Lane C — CI/CD

| Item | Status | Location |
|------|--------|----------|
| CI-01 Playwright e2e matrix strategy | PASS | `.github/workflows/verify.yml:103-180` |
| P1-06 `GLACIER_SHARE_IMGPROXY_HMAC_SECRET` in ADMIN.md | PASS | `docs/ADMIN.md:14,89,105,109` |
| P2-06 CycloneDX + syft SBOM | PASS | `build-and-deploy.yml:127-130,232-243` |
| P2-07 Dependabot targets main | PASS | `.github/dependabot.yaml` |
| P2-08 Reusable `setup-java-cache` workflow | PASS | `.github/actions/setup-java-cache/` |
| P2-10 Mode-specific debug recipes in README | PASS | `infrastructure/README.md:122-248` |

### Lane D — Frontend / UX / a11y / i18n

| Item | Status | Location |
|------|--------|----------|
| A11Y-F-01 iframe `[title]` on TootComponent | PASS | `toot.component.html:3` |
| A11Y-F-02 `<button>` for Cancel All / Clear Toots / Legal | PASS | `hashtag.component.html`, `footer.component.html` |
| A11Y-F-03 `<html lang="de">` | PASS | `frontend/src/index.html:2` |
| A11Y-F-04 Skip link `href="#toots"` | PASS | `app.component.html:8` |
| D.2 HeaderComponent `h1` i18n (`@@header.h1`) | PASS | `header.component.html:4` |
| D.2 GdprComponent heading i18n (`@@gdpr.dialog.title`) | PASS | `gdpr.component.html:1` |
| D.2 CI i18n-key lint script | PASS | `frontend/scripts/check-i18n-keys.mjs` |
| D.2 Maven wiring for lint | PASS | `pom.xml` (fixed `node` → `npm` goal) |
| P1-17 Share button in HeaderComponent | PASS | `header.component.ts:57-60` |
| P1-18 `Math.max(1, …)` column guard | PASS | `wall.component.ts:138,146` |
| P2 Empty-wall CTA with `role="status"` | PASS | `wall.component.html:42-51` |
| P2 `columnToots = computed(…)` signal | PASS | `wall.component.ts:77,101` |

## Security findings resolved in-phase

### F-1 (HIGH) — ClientIpResolver XFF off-by-one

**Root cause**: The formula `len - trustedHops - 1` returned the entry to the LEFT of the trusted proxy chain — exactly what an attacker can inject. A client sending `X-Forwarded-For: FAKE` through one trusted proxy produces `FAKE, real-client-ip`; the old formula returned `FAKE` instead of `real-client-ip`.

**Fix**: Changed to `len - trustedHops` (commit `732c47e`). Updated 5 test assertions and the Javadoc algorithm bullet. 14/14 `ClientIpResolverTest` pass.

**OWASP**: A05:2021 Security Misconfiguration / API3:2023 Broken Object Property Level Authorisation.

### T6 log-hygiene test ConcurrentModificationException

**Root cause**: `RawWallIdLogHygieneTest.T6` iterated `ListAppender.list` directly while the SubscriptionListener's timer virtual-thread concurrently appended to it.

**Fix**: Defensive `new ArrayList<>(events)` snapshot taken before iteration, both at the T6 call site and inside `assertNoRawUuid`. Commit `732c47e`.

## Known gaps (accepted, do not block)

### SubscriptionService split (D.3)

The split into `SubscriptionStateService` / `SubscriptionPersistence` / `SubscriptionStompClient` was assessed as too high-risk to include in this pipeline run (944-line spec, 5+ injection sites). All other D.3 items shipped. Tracked for a dedicated refactor PR.

### GdprComponent body i18n (Open Risk #3)

Only the heading and close button are i18n'd. Full legal body extraction is deferred due to jurisdictional/legal complexity. Documented in planning ADR Open Risks.

### Sec-02/P1-09 — Bigbone SNAPSHOT SHA256 pin (deferred)

The planning ADR committed to pinning `bigbone:2.0.0-SNAPSHOT` by SHA256 via `maven-enforcer requireReleaseDeps`. This was not implemented because it requires upstream coordination with the bigbone maintainers for a stable hash to pin against. Explicitly deferred: tracked as Open Risk #1 in the planning ADR. A follow-up ticket should be raised once upstream cuts a tagged release or publishes a reproducible build.

## Commits in scope

```
4410b26 docs(planning): comprehensive quality review — Phase 1 accepted 2026-05-07
8ceff4e fix(concurrency): Q-01/Q-02 — replace HashMap with ConcurrentHashMap
82b4687 test(reliability): P1-07 — BigboneKeepAliveIT
583eba1 feat(reliability): P2-01 — Apache HttpClient 5 connection pool
42060b0 feat(reliability): P2-02 — exponential back-off on Bigbone reconnect
5863f67 refactor(mastodon): P2-09 — MastodonConfiguration helpers
8338ac5 feat(reliability): P2-12 — MessageDeliveryException retry
c531159 feat(reliability): P2-13 — guard edit events against unseen creates
37f307d feat(security): Lane B — P1+P2 security hardening
b79f2b7 ci: Lane C — CI-01/P1-06/P2-06/P2-07/P2-08/P2-10 quality review
f3554f3 feat(frontend): Lane D — D.1/D.2/D.3 a11y, i18n, UX polish
d7f5380 fix(build): correct frontend-maven-plugin goal (node → npm)
732c47e fix(security): F-1 ClientIpResolver XFF off-by-one + T6 log-hygiene CME
```

## References

- Planning: `docs/decisions/2026-05-07-planning-quality-review.md`
- CORS audit: `docs/decisions/2026-05-07-cors-audit.md`
- OWASP Top 10 (2025) — A05 Security Misconfiguration
- OWASP API Security Top 10 (2023) — API3 Broken Object Property Level Authorization
- WCAG 2.1 — SC 2.1.1 (Keyboard), SC 3.1.1 (Language of Page), SC 4.1.2 (Name, Role, Value)
