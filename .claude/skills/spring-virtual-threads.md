---
name: spring-virtual-threads
owner: "@seism0saurus"
description: Enable and use Java virtual threads (production-ready on Java 23) in Spring Boot 3.4 for IO-bound workloads in the Glacier backend. TRIGGER when editing Executor/TaskExecutor beans, @Async methods, WebSocket handlers, HTTP controllers that make external calls, thread-pool sizing, ThreadFactory construction, or when the user mentions virtual threads, Loom, thread pool, @Async, executor, carrier thread, pinning, parallelism, scaling. SKIP for CPU-bound computation, pure algorithm code, or frontend work.
---

# Spring Boot Virtual Threads on Java 23

Glacier runs on **Java 23 + Spring Boot 3.4.5** — virtual threads are production-ready and the natural choice for the backend's IO profile (Mastodon API calls via bigbone, WebSocket handlers, embed fetching). Every blocking IO call under a virtual thread parks the carrier thread at no cost — millions of parallel IO-waiting VTs are cheap.

## Enable virtual threads globally

```properties
# src/main/resources/application.properties
spring.threads.virtual.enabled=true
```

This single flag:
- Switches embedded Tomcat to virtual-thread request handling (Tomcat 10.1+).
- Switches the default `TaskExecutor` to `SimpleAsyncTaskExecutor(virtualThreads=true)` for `@Async`.
- Runs WebSocket inbound/outbound channels on virtual threads.

Verify in `application.properties` — a comment in the file already hints at virtual-thread awareness around timeouts (embed fetcher 3s/5s).

## Per-executor virtual threads (when you want explicit control)

```java
@Bean("mastodonCallExecutor")
Executor mastodonCallExecutor() {
  return Executors.newVirtualThreadPerTaskExecutor();
}

@Service
class EmbedFetcher {
  @Async("mastodonCallExecutor")
  public CompletableFuture<EmbedResult> fetch(URI url) { ... }
}
```

Use a named executor only when the IO class has distinct scheduling/observability needs — e.g., tagging metrics by "executor".

## Always bound IO time — a parked virtual thread is still a leaked one

Virtual threads remove thread-count pressure, not IO-duration pressure. A VT waiting on `socketRead` forever is leaked resource until the socket closes or times out. Glacier's existing pattern:
```properties
mastodon.connectTimeout=${CONNECT_TIMEOUT:240}       # seconds
mastodon.readTimeout=${READ_TIMEOUT:240}
mastodon.writeTimeout=${WRITE_TIMEOUT:240}
glacier.embed.connectTimeoutMs=${GLACIER_EMBED_CONNECT_TIMEOUT_MS:3000}
glacier.embed.readTimeoutMs=${GLACIER_EMBED_READ_TIMEOUT_MS:5000}
```

Follow this precedent for any new HTTP client. See `spring-http-client-resilience` skill for timeout configuration details.

## Pinning traps — when a virtual thread pins the carrier

The carrier thread is held for the duration of the pinned section, negating the VT benefit.

### 1. `synchronized` blocks around blocking IO

```java
// Bad — pins the carrier for the entire HTTP call
synchronized (lock) {
  httpClient.call(...);
}

// Good — use ReentrantLock, or narrow the synchronized scope
private final Lock lock = new ReentrantLock();

lock.lock();
try {
  httpClient.call(...);  // virtual thread unmounts freely
} finally {
  lock.unlock();
}
```

**On Java 23, `synchronized` pinning is still real** for blocking operations (JDK 24/25 progressively fix this). Audit `synchronized` blocks that wrap any `Thread.sleep`, IO, or RPC. `ReentrantLock` is the safe default for new code.

### 2. Native methods (JNI) that block

Rare in Glacier — no JNI bindings in the core code path. If a dependency pulls in JNI (e.g., native TLS, some file-IO libs), profile before assuming VT scaling applies.

### 3. `ThreadLocal` multiplication

Virtual threads are cheap; each still carries its own `ThreadLocal` values. If you spin up 100k concurrent VTs and each has a `ThreadLocal` holding a 1 MB object → 100 GB heap.

- Audit `ThreadLocal` in hot paths. Prefer method parameters or scoped beans.
- Consider `ScopedValue` (preview in JDK 21+, standard in newer JDKs) for safer request-scoped state.

## Diagnostic: detect pinning

```bash
./mvnw test -DargLine="-Djdk.tracePinnedThreads=short"
```

Prints stacktraces where VTs pinned during the run. Enable for integration tests in CI to catch regressions. `full` for detailed traces, `short` for one-liners.

## Thread naming for debuggable logs

Anonymous virtual threads (default) make log grep painful:
```java
ThreadFactory factory = Thread.ofVirtual()
    .name("mastodon-", 0L)
    .factory();
Executor exec = Executors.newThreadPerTaskExecutor(factory);
```

Produces `mastodon-0`, `mastodon-1`, … in logs.

## What NOT to use virtual threads for

- **CPU-bound work** (image processing, crypto-heavy ops, heavy computation): use a bounded `ThreadPoolExecutor` sized near `Runtime.getRuntime().availableProcessors()`. VTs don't help; they just multiply context-switching.
- **Parallel streams** (`Stream.parallel()`): use the common ForkJoinPool, not virtual threads. Don't mix.
- **Short non-blocking tasks**: the VT creation/destruction overhead may outweigh platform-thread pool reuse. VTs shine for blocking IO.

## Interaction with Glacier's specific subsystems

- **Bigbone/Mastodon calls**: IO-bound → VT-friendly. Ensure OkHttp (bigbone's transport) uses blocking calls, which it does by default. Async callback APIs do not need VTs (they unmount on non-blocking paths anyway).
- **WebSocket handlers** (`@MessageMapping`): VT-enabled when the global flag is on. Blocking IO inside a handler is acceptable; see `spring-websocket-performance` skill.
- **Embed fetcher**: blocking HTTP with short timeouts, many concurrent requests per toot render — prime VT use case.
- **MessageCache operations**: in-memory work, not IO. VTs neither help nor hurt.

## What Claude gets wrong without this skill

- Configures `ThreadPoolTaskExecutor` with fixed size (8–16) for IO-bound work, then wonders why throughput collapses when Mastodon is slow.
- Forgets `spring.threads.virtual.enabled=true` → Tomcat stays capped around 200 worker threads (default max).
- Uses `synchronized` around blocking IO → carrier pinning, throughput limited to carrier-pool size.
- Creates unbounded `ThreadLocal`s without considering VT multiplication → memory growth under concurrent load.
- Uses `Stream.parallel()` expecting VT semantics.
- Writes a `new Thread(...).start()` loop for "concurrency" instead of `Executors.newVirtualThreadPerTaskExecutor()`.

## References
- Enablement flag: `src/main/resources/application.properties`
- Existing timeout precedents: `mastodon.*Timeout`, `glacier.embed.*TimeoutMs`
- JDK 23 VT release notes: https://openjdk.org/jeps/444
- Loom pitfalls (Oracle blog): https://openjdk.org/jeps/444#Pinning
