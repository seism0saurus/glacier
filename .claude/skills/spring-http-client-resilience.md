---
name: spring-http-client-resilience
owner: "@seism0saurus"
description: Configure timeouts, retries, circuit breakers, and rate-limit respect for outbound HTTP calls in the Glacier backend — primarily bigbone (Mastodon API) and the embed fetcher. TRIGGER when modifying MastodonConfiguration, bigbone client setup, RestClient/WebClient/RestTemplate usage, OkHttp client config, outbound HTTP timeouts, Retry-After handling, 429/5xx retry logic, circuit-breaker state, or when the user mentions circuit breaker, resilience, retry, backoff, timeout, rate limit, Retry-After, Mastodon API, outbound HTTP. SKIP for inbound request handling (that's in spring-security-hardening), WebSocket code (spring-websocket-performance), or unrelated code.
---

# Outbound HTTP Resilience for Glacier

Glacier's main outbound dependency is the Mastodon API via **bigbone** (OkHttp-based under the hood), plus a custom embed fetcher. Mastodon instances are unreliable by nature — rate limits, downtime, slow responses. Outbound resilience is a core correctness concern.

## Timeouts — always all three, no exceptions

Every HTTP client MUST have connect, read, and write timeouts. Without them the default is "wait forever" → a single slow remote parks a virtual thread indefinitely.

Glacier's existing pattern in `application.properties`:
```properties
mastodon.connectTimeout=${CONNECT_TIMEOUT:240}       # seconds
mastodon.readTimeout=${READ_TIMEOUT:240}
mastodon.writeTimeout=${WRITE_TIMEOUT:240}

glacier.embed.connectTimeoutMs=${GLACIER_EMBED_CONNECT_TIMEOUT_MS:3000}  # ms
glacier.embed.readTimeoutMs=${GLACIER_EMBED_READ_TIMEOUT_MS:5000}
```

**Different operation classes deserve different timeout budgets**: embed-fetch (3s/5s) is user-latency-sensitive; Mastodon streaming/sync (240s) can afford more. Follow this naming pattern (`<operation>.connectTimeout*`, `.readTimeout*`) for any new client.

### Bigbone / OkHttp config

Bigbone's `MastodonClient.Builder` exposes timeouts — wire them from Spring properties in `MastodonConfiguration.java`. If bigbone accepts a pre-configured `OkHttpClient`, configure interceptors (retry, logging, metrics, auth) there.

```java
OkHttpClient httpClient = new OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
    .readTimeout(Duration.ofSeconds(readTimeoutSec))
    .writeTimeout(Duration.ofSeconds(writeTimeoutSec))
    .callTimeout(Duration.ofSeconds(connectTimeoutSec + readTimeoutSec + 10))
    .addInterceptor(metricsInterceptor)
    .addInterceptor(retryAfterInterceptor)
    .build();
```

`callTimeout` is a total-wall-clock cap — defensive upper bound independent of connect/read/write phases.

### For new HTTP needs: `RestClient` (Spring 6.1+), not `RestTemplate`

```java
@Bean
RestClient embedRestClient(
    @Value("${glacier.embed.connectTimeoutMs}") int connectMs,
    @Value("${glacier.embed.readTimeoutMs}") int readMs
) {
  SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(Duration.ofMillis(connectMs));
  factory.setReadTimeout(Duration.ofMillis(readMs));
  return RestClient.builder().requestFactory(factory).build();
}
```

Do **not** use `RestTemplate` for new code — soft-deprecated. `RestClient` is the synchronous successor (plays well with virtual threads; see `spring-virtual-threads` skill). `WebClient` only if you genuinely need reactive composition.

## Retry with exponential backoff — Resilience4j

```java
Retry retry = Retry.of("mastodon-timeline", RetryConfig.custom()
    .maxAttempts(3)
    .intervalFunction(IntervalFunction.ofExponentialBackoff(
        Duration.ofSeconds(1), 2.0))
    .retryExceptions(IOException.class, SocketTimeoutException.class)
    .retryOnResult(result -> shouldRetryResult(result))
    .build());

Supplier<Status> call = Retry.decorateSupplier(retry,
    () -> mastodonClient.statuses().getStatus(id).execute());
Status status = call.get();
```

Dependency (not yet in pom.xml — add when building the first resilience-protected client):
```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

### HTTP status retry rules

**Retry on**: 408 (timeout), 425 (too early), 429 (rate limit, with `Retry-After`), 502/503/504, network errors.

**Never retry on**: 400 (bad request), 401 (unauthorized), 403 (forbidden), 404 (not found), 409 (conflict). These are semantic client errors — retrying wastes quota and amplifies load during outages.

## Respect `Retry-After` on 429

Mastodon returns 429 with `Retry-After` (seconds or HTTP-date). Blind exponential backoff ignores this and triggers more limits.

```java
if (response.code() == 429) {
  long waitSec = parseRetryAfter(
      response.header("Retry-After", "60"));
  throw new RateLimitedException(waitSec);
}

static long parseRetryAfter(String header) {
  if (header.chars().allMatch(Character::isDigit)) {
    return Long.parseLong(header);
  }
  // HTTP-date form
  ZonedDateTime target = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME);
  return Math.max(0, ChronoUnit.SECONDS.between(ZonedDateTime.now(), target));
}
```

In the retry config, feed this into the backoff:
```java
.intervalBiFunction((attempt, either) -> {
  Throwable t = either.isLeft() ? either.getLeft() : null;
  if (t instanceof RateLimitedException rle) {
    return Duration.ofSeconds(rle.waitSeconds()).toMillis();
  }
  return (long) (1000 * Math.pow(2, attempt - 1));
})
```

Glacier already surfaces "rate-limited" state to the frontend (`rate.limited.snackbar` in `messages.en.json`) — ensure the backend actually honors the `Retry-After` before propagating the state.

## Circuit breakers — **per instance**, not global

Different users use different Mastodon instances. One failing instance should not degrade service for users on healthy ones. Circuit-breaker state **per hostname**:

```java
CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
    CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .slowCallRateThreshold(80)
        .slowCallDurationThreshold(Duration.ofSeconds(10))
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .permittedNumberOfCallsInHalfOpenState(3)
        .minimumNumberOfCalls(10)
        .slidingWindowSize(20)
        .build());

CircuitBreaker cb = registry.circuitBreaker("mastodon:" + host);
Supplier<Status> decorated = CircuitBreaker.decorateSupplier(cb, call);
```

Per-host CB registries grow with the number of distinct instances. Bound cardinality if necessary (e.g., evict inactive CBs after N minutes of no calls).

## Bulkheads — isolate instance pools

Prevent one slow instance from hogging the thread pool of all outbound calls:

```java
Bulkhead bulkhead = Bulkhead.of("mastodon:" + host,
    BulkheadConfig.custom()
        .maxConcurrentCalls(20)
        .maxWaitDuration(Duration.ofMillis(500))
        .build());
```

With virtual threads, thread-count bulkheads matter less — but concurrent-call bulkheads still bound the pressure on remote instances.

## Don't follow redirects for user-supplied URLs

For Mastodon calls, redirects to the same-origin host are fine. For **any URL originating from user input or Mastodon-content** (embed fetcher in particular), redirects are an SSRF vector.

```java
OkHttpClient embedHttp = new OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build();
```

Handle 3xx manually and re-validate the `Location` against the SSRF blocklist. See `spring-input-validation-ssrf` skill.

## Response-size caps

Don't read unbounded response bodies. Cap early:
```java
try (InputStream body = response.body().byteStream()) {
  byte[] buf = body.readNBytes(MAX_EMBED_BYTES);   // e.g., 256 KB
  if (body.read() != -1) {
    throw new ResponseTooLargeException(host);
  }
  return decode(buf);
}
```

## Metrics — always instrument outbound calls

Tag by instance, operation, and outcome (see `spring-observability-micrometer` skill):

```java
Timer.Sample sample = Timer.start(meterRegistry);
String outcome = "success";
try {
  return supplier.get();
} catch (RateLimitedException e) {
  outcome = "rate_limited"; throw e;
} catch (SocketTimeoutException e) {
  outcome = "timeout"; throw e;
} catch (Exception e) {
  outcome = "error"; throw e;
} finally {
  sample.stop(Timer.builder("glacier.mastodon.call")
      .tag("instance", host)
      .tag("operation", operation)
      .tag("outcome", outcome)
      .publishPercentileHistogram()
      .register(meterRegistry));
}
```

## What Claude gets wrong without this skill

- Uses `RestTemplate` without timeouts → virtual-thread leak on slow remote.
- Uses `RestTemplate` in new code instead of `RestClient`.
- Retries on 4xx including 401/403/404 → wasted quota, amplified load.
- Ignores `Retry-After` on 429 → gets throttled harder.
- Single global circuit breaker → one bad instance breaks service for everyone.
- Follows redirects on user-provided URLs → SSRF.
- Reads response bodies without a size cap → OOM via huge remote response.
- Sets only `readTimeout`, forgets `connectTimeout` and `writeTimeout`.

## References
- Mastodon client config: `src/main/java/de/seism0saurus/glacier/mastodon/MastodonConfiguration.java`
- Timeout precedents: `application.properties` (`mastodon.*Timeout`, `glacier.embed.*TimeoutMs`)
- Rate-limit UX wiring: `rate.limited.*` keys in `frontend/src/assets/i18n/messages.en.json`
- Resilience4j docs: https://resilience4j.readme.io/docs
- OkHttp timeouts: https://square.github.io/okhttp/recipes/#timeouts-kt-java
