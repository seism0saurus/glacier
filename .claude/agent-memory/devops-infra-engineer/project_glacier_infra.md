---
name: Glacier project infrastructure patterns
description: Key build, test, and CI patterns in the Glacier repo relevant to infra/devops work
type: project
---

Spring Boot 3.4 / Java 23 (JAVA_HOME=/home/ulrich.viefhaus/.jdks/temurin-23.0.2).
Maven wrapper: `./mvnw`. Angular frontend embedded in jar via frontend-maven-plugin.

**Why:** Angular build (`$localize`) is broken in peer lanes during Phase 3; bypass with `-Dskip.npm=true -Dskip.ng=true` when running verify for backend-only changes.

**Jacoco thresholds (pom.xml `check-coverage` execution):** instruction ≥ 45%, branch ≥ 35% bundle-wide. Current state after Phase 3: instruction 56.5%, branch 50.9%.

**Test counts after Phase 3 devops lane:** Surefire 296, Failsafe 71, Karma 187 (unchanged).

**WireMock pattern for ITs:** `WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())` started/stopped in `@BeforeEach`/`@AfterEach` — do NOT use `@WireMockTest` (class-level annotation reuses port, causes isolation issues). Always mock `MastodonClient` via `@MockitoBean` in `@SpringBootTest` contexts to prevent real network calls on context startup.

**Peer-lane collision:** `SubscriptionListener` constructor was extended to require `MessageCache` in Phase 2 (tdd-ddd lane). Any unit test that constructs `SubscriptionListener` directly must pass a `mock(MessageCache.class)` as the second argument.

**Stale class files:** When hitting "wrong number of constructor args" at compile time after clean checkout, always run `./mvnw clean compiler:compile compiler:testCompile` first — IntelliJ and incremental builds can leave stale `.class` files that shadow the source.
