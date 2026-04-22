---
name: Glacier test conventions
description: Naming rules, constructor signatures, peer-lane risks, Awaitility pattern for async ITs
type: feedback
---

## Surefire vs Failsafe naming

`*Test.java` = Surefire (unit tests). `*IT.java` = Failsafe (integration tests). Non-negotiable — Jacoco coverage aggregates across both but Failsafe only picks up `*IT.java` filenames.

**Why:** CLAUDE.md explicitly states this. Putting a `@SpringBootTest` in a `*Test.java` file means Surefire runs it without Failsafe's lifecycle plugin, which can cause Jacoco exec file issues.

## MessageCacheImpl 6-arg constructor

`new MessageCacheImpl(template, meterRegistry, ringCapacity, maxHashtagsPerPrincipal, maxPrincipals, fallbackEnabled)` — the 6th boolean arg was added for the killswitch (FIX A, ws-fallback feature). Any test that instantiates `MessageCacheImpl` directly must pass all 6 args.

## Awaitility pattern for async ITs

For `@SpringBootTest` ITs with async eviction:
```java
Awaitility.await("alias for error messages")
    .atMost(Duration.ofSeconds(5))
    .pollInterval(Duration.ofMillis(100))
    .untilAsserted(() -> assertThat(cache.isProvisioned(p, h)).isFalse());
```
Upper bound 5s provides ample headroom for GC pressure even when the timer fires after 500ms.

## Peer-lane test file risk

During multi-agent pipeline runs, peer-lane agents write test files for APIs not yet in production. This can break `./mvnw test-compile` for the entire project. Always check whether a compile error originates from an untracked peer-lane file before attempting a fix in this lane.

**How to apply:** If `test-compile` fails, check `git status --short` to see which test files are untracked. Untracked `*Test.java` or `*IT.java` files from other agents may be the source.

## SimpleMeterRegistry for isolated unit tests

When a test class creates multiple `MessageCacheImpl` instances that need separate Micrometer registries, create a `new SimpleMeterRegistry()` per instance (not per class). Reusing the same registry across instances triggers `IllegalArgumentException: Gauge with name X already registered`.
