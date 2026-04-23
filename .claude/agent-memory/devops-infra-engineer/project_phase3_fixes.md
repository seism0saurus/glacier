---
name: Phase 3 fix cycle — FIX A and FIX B
description: What was done in the devops-infra lane for Phase 3 ws-fallback feature
type: project
---

**FIX A (F-12 / C-03): EmbedTimeoutIT — socket-level timeout enforcement**

New file: `src/test/java/de/seism0saurus/glacier/EmbedTimeoutIT.java`

Proves `GlacierApplication.restTemplate()` timeouts fire at the socket level, not just via reflection-on-private-fields (which was the pre-existing `RestTemplateRedirectTest` coverage). Strategy: WireMock with `withFixedDelay(10_000)`, `glacier.embed.readTimeoutMs=500`, assert `ResourceAccessException` thrown within 2 000 ms, `@Timeout(10)` sentinel as defence-in-depth.

True TCP-connect-timeout simulation (SYN never ACKed) is not achievable in CI without iptables — modern Linux kernels complete three-way handshake in kernel backlog before userspace `accept()`. Both test methods use the read-timeout path; the `RestTemplateRedirectTest` reflection test covers the `connectTimeout` field setter.

TDD falsification: confirmed both tests fail (TimeoutException at 10s) when `restTemplate()` returns `new RestTemplate()` (no timeouts).

**FIX B (F-05): Dead MongoDB config deletion + constraint test**

- Deleted entire MongoDB block from `src/main/resources/application.properties` (lines that included hardcoded `spring.data.mongodb.password=securepwd`). No Java code referenced Mongo — `spring-boot-starter-data-mongodb` was already commented out in `pom.xml`.
- Extended `src/test/java/de/seism0saurus/glacier/util/CodebaseConstraintTest.java` with `assertNoHardcodedPasswords_inApplicationProperties()`: regex `(?i).*[._-]?password=.+` with negative match for `${...}` env-var form, line-by-line scan, reports file:line on failure.

**Why:** Why deadcode with hardcoded credential stayed — MongoDB was likely from early dev scaffolding, never cleaned up. Constraint test prevents recurrence (CI will catch future commits that reintroduce hardcoded passwords).

**Collateral fix:** `RawWallIdLogHygieneTest` (peer-lane file) was calling old 2-arg `SubscriptionListener` constructor after Phase 2 added `MessageCache` as required arg. Fixed by inserting `mock(MessageCache.class)` as second arg in both constructor calls. This was a compilation blocker that prevented any test from running.
