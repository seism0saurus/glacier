---
name: spring-observability-micrometer
owner: "@seism0saurus"
description: Add Micrometer metrics and configure Actuator observability for the Glacier Spring Boot backend, keyed to Glacier-specific signals (Mastodon call latency per instance, WebSocket sessions, cache hit ratio, fallback activation, rate-limit hits). TRIGGER when adding or editing MeterRegistry usage, Counter/Timer/Gauge code, Actuator exposure config, @Timed/@Counted annotations, management.endpoints properties, HealthIndicator implementations, or when the user mentions metrics, monitoring, observability, Prometheus, Actuator, Micrometer, dashboard, histogram, percentile, tracing. SKIP for logging-only changes, frontend analytics, or unrelated test code.
---

# Observability with Micrometer for Glacier

Glacier already has `micrometer-core` + `spring-boot-starter-actuator`. Current Actuator exposure in `application.properties` is deliberately restrictive:

```properties
management.endpoints.web.exposure.include=health,info
management.endpoints.web.base-path=/internal/actuator
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never
```

**Keep it that way.** Broader exposure only behind an authenticated path or a management port bound to a private network.

## Inject `MeterRegistry`, never instantiate

```java
// Bad — isolated registry, metrics don't publish anywhere
MeterRegistry reg = new SimpleMeterRegistry();

// Good — Spring's auto-configured composite registry
@Component
class MastodonMetrics {
  private final MeterRegistry registry;
  MastodonMetrics(MeterRegistry registry) { this.registry = registry; }
}
```

The auto-configured registry publishes to whatever backends are on the classpath (Prometheus scrape when `micrometer-registry-prometheus` is added, JMX, etc.).

## Glacier-relevant custom metrics

### Mastodon API call latency + outcome

```java
Timer.Sample sample = Timer.start(registry);
String outcome = "success";
try {
  return call();
} catch (RateLimitedException e) { outcome = "rate_limited"; throw e;
} catch (SocketTimeoutException e) { outcome = "timeout"; throw e;
} catch (Exception e) { outcome = "error"; throw e;
} finally {
  sample.stop(Timer.builder("glacier.mastodon.call")
      .description("Latency of outbound Mastodon API calls")
      .tag("instance", host)          // bounded if instance set is bounded
      .tag("operation", "timeline.hashtag")
      .tag("outcome", outcome)
      .publishPercentileHistogram()   // enables p50/p95/p99 queries in Prometheus
      .register(registry));
}
```

`Timer` measures both count and duration. `publishPercentileHistogram()` is the flag that enables Prometheus histogram buckets — without it you lose percentile queries.

### WebSocket session count (Gauge — read live state)

```java
Gauge.builder("glacier.websocket.sessions",
              sessionManager, SessionManager::activeCount)
    .description("Currently active WebSocket sessions")
    .register(registry);
```

**Don't** drive Gauges with `+1`/`-1` counters on connect/disconnect — they drift on missed events. Read live state from the authoritative source.

### Connect / disconnect counters (Counter — event stream)

```java
Counter.builder("glacier.websocket.disconnect")
    .tag("reason", reason)   // client / server / timeout / buffer_full / killswitch
    .register(registry)
    .increment();
```

Paired with `glacier.websocket.connect.total` (tagged `outcome`), these give you the connection churn picture.

### Cache instrumentation

`MessageCacheImpl` should expose hit/miss counts. Register as Gauges:
```java
Gauge.builder("glacier.cache.entries", cache, MessageCache::size).register(registry);
Gauge.builder("glacier.cache.hits",    cache, MessageCache::hitCount).register(registry);
Gauge.builder("glacier.cache.misses",  cache, MessageCache::missCount).register(registry);
Gauge.builder("glacier.cache.evictions", cache, MessageCache::evictionCount).register(registry);
```

Derive hit ratio in the dashboard (`rate(hits) / (rate(hits) + rate(misses))`) rather than storing a pre-computed gauge — more accurate across time windows.

### Fallback-mode activation

```java
Counter.builder("glacier.fallback.activation")
    .tag("reason", reason)   // ws_failure / killswitch / initial / insecure
    .register(registry)
    .increment();
```

### Rate-limit hits (inbound)

```java
Counter.builder("glacier.fallback.ratelimit.hit")
    .tag("scope", scope)     // per_ip / per_wallid
    .register(registry)
    .increment();
```

### Embed fetcher

```java
Timer.builder("glacier.embed.fetch")
    .tag("outcome", outcome)   // success / blocked_ssrf / timeout / too_large / error
    .publishPercentileHistogram()
    .register(registry)
    .record(duration);
```

## Tag cardinality — the silent Prometheus killer

Tags become label dimensions. **High-cardinality tags explode the metric-series count.** Rules:

- **OK**: outcome, mode, reason, operation, scope, instance (if set is bounded).
- **Problematic**: instance hostnames if unbounded (every user's unique fediverse host).
- **Never**: user IDs, session IDs, IP addresses, request IDs, UUIDs.

If Glacier has unbounded Mastodon instances, bound the cardinality with a **top-N + "other"** strategy:
```java
String tag = topInstances.contains(host) ? host : "other";
```

Refresh the "top N" list daily or on startup.

## Timer vs Counter vs Gauge vs DistributionSummary

| Type | Use for |
|---|---|
| **Counter** | Monotonically-increasing event count (connects, retries, errors) |
| **Timer** | Latency + count of time-bounded operations (API calls, handler exec, embed fetch) |
| **Gauge** | Snapshot of current state (active sessions, cache size, queue depth) |
| **DistributionSummary** | Non-time distributions (payload sizes in bytes, message counts per batch) |

Don't use Counter for values that decrease (use Gauge). Don't use Gauge for event streams (use Counter).

## Prometheus exposure (when you're ready)

Add the registry:
```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Expose `/prometheus`:
```properties
management.endpoints.web.exposure.include=health,info,prometheus
# base-path stays /internal/actuator — rely on Traefik / network to restrict
```

**Never expose `/prometheus` on the public port without auth or network restriction.** It reveals:
- Every Mastodon instance you call (from tag values).
- Latency distributions (fingerprints of user behavior).
- Internal resource counts (cache sizes → memory fingerprint).

Traefik middleware or a separate management port bound to a private interface (`management.server.port=8081` + `management.server.address=127.0.0.1`) are the typical restrictions.

## `@Timed` — declarative for simple cases

```java
@Timed(value = "glacier.embed.fetch",
       percentiles = { 0.5, 0.95, 0.99 },
       extraTags = { "source", "embed" })
public EmbedResult fetchEmbed(URI url) { ... }
```

Use when the method needs a single metric name and static tags. For dynamic tags based on arguments (common), go programmatic with `Timer.Sample`.

## Health indicators for business-critical dependencies

```java
@Component
class MastodonHealthIndicator implements HealthIndicator {
  public Health health() {
    return mastodonClient.reachable()
        ? Health.up().withDetail("instance", instance).build()
        : Health.down().withDetail("instance", instance).build();
  }
}
```

With `management.endpoint.health.show-details=never`, the details are logged server-side but not leaked publicly — the overall UP/DOWN still contributes to orchestrator health checks (readiness probes).

## Tracing (optional, for deeper perf investigation)

When request-latency causes become opaque, add distributed tracing:
```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
  <groupId>io.zipkin.reporter2</groupId>
  <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

Auto-instrumentation for `RestClient`/`RestTemplate`/`WebClient` is free. For bigbone → OkHttp, add an `OkHttpClient` interceptor that starts/stops a Micrometer Observation.

## What Claude gets wrong without this skill

- Creates `new SimpleMeterRegistry()` → metrics sink into an isolated bag, invisible to Prometheus.
- Tags with user IDs, IPs, request IDs → unbounded cardinality, Prometheus meltdown.
- Uses Counter for values that can decrease (e.g., "current connections") — must be Gauge.
- Exposes `/actuator/env`, `/actuator/configprops`, `/actuator/metrics`, `/actuator/beans`, `/actuator/heapdump` without auth → information leak or DoS vector.
- Uses `Metrics.counter(...)` (deprecated static API) instead of injected `MeterRegistry`.
- Forgets `.publishPercentileHistogram()` → no percentile queries in Prometheus.
- Writes gauges that increment/decrement manually → drift on missed events.

## References
- Actuator config: `src/main/resources/application.properties` (`management.*`)
- Cache: `src/main/java/de/seism0saurus/glacier/webservice/cache/MessageCacheImpl.java` — add metric hooks here
- Rate limiter: `FallbackRateLimiter.java` — counter increment on rate-limit hit
- Micrometer concepts: https://micrometer.io/docs/concepts
- Prometheus tag-cardinality advice: https://prometheus.io/docs/practices/naming/
