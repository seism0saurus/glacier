---
name: glacier-structured-logging-logback
owner: "@seism0saurus"
description: Use Glacier's structured JSON-Logback setup correctly — never leak cookies/tokens/session IDs, route audit events to the AUDIT logger, use MDC for correlation IDs, and apply LogScrubber for sensitive-data helpers. Enforces security requirements D-13 and SR-8. TRIGGER when adding or editing logger calls (log.info/warn/error), LogScrubber usage, MDC put/remove, AUDIT logger usage, changes to logback.xml, or when the user mentions logging, logback, log scrubbing, MDC, correlation ID, structured logging, audit log, sensitive data leak. SKIP for frontend console.log, test-only logging, or non-logging code.
---

# Structured Logging in Glacier (Logback + JSON + Security Hardening)

Glacier's `logback.xml` is not generic — it encodes **explicit security requirements** (D-13, SR-8) that apply to every log call in production code. The setup:

- JSON layout via `ch.qos.logback.contrib.json.classic.JsonLayout` → stdout (for container log collection)
- Sensitive MDC keys `cookie`, `setCookie`, `authorization` are **never** to reach the encoder (enforced by convention + `LogScrubber`)
- Dedicated `AUDIT` logger (name: `"AUDIT"`) for auth-failure + rate-limit events at INFO level
- `LogScrubber` utility class with helpers for scrubbing raw wallIds, cookies, and other sensitive tokens before logging

## Never log these raw — hard rule

Do NOT emit these values into any logger call OR any MDC field:

- **Mastodon access tokens** (`mastodon.accessToken`, request Authorization headers)
- **Session cookies** (session IDs, wall IDs, any auth cookie)
- **Set-Cookie header values**
- **Authorization header values** (any `Authorization:` or bearer token)
- **Personal data** (user-agent when combined with IP, full emails, etc.)

If you need to log *something* about a cookie or token (for debugging), scrub it through `LogScrubber` first — which should give you a fingerprint (first 4 chars + hash) suitable for correlation without revealing the secret.

## Logger usage — basics

Standard pattern:
```java
private static final Logger log = LoggerFactory.getLogger(SubscriptionManagerImpl.class);

log.info("subscription registered for hashtag={}", hashtag);
log.warn("retry after {}s — instance {} rate-limited", retryAfter, host);
log.error("mastodon sync failed for instance={}", host, ex);   // ex LAST arg → logs stacktrace
```

Rules:
- **Parameterized messages** (`{}`), never string concatenation — avoids unnecessary toString calls if level is disabled.
- **Exception as last argument** — Logback recognizes it and logs the stack trace.
- **Lowercase, short messages** — consistent with existing log style.
- **No PII in message fields** — same rule as MDC.

## MDC for correlation — but scrub sensitive fields

MDC (Mapped Diagnostic Context) adds thread-scoped fields to every log line. With JSON Layout, MDC keys become top-level JSON fields. Useful for request-scoped correlation (`requestId`, `instanceHost`, `mode`).

```java
try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
  log.info("processing subscription request");
  // ... work happens ...
}
```

**Never** put `cookie`, `setCookie`, `authorization`, `accessToken`, `sessionId`, `wallId` into MDC directly. The `logback.xml` comment says it: *these are dropped at the encoder level*, but the defence-in-depth rule is to prevent them entering MDC at all.

If you need a log-safe identifier tied to a wallId or session, use `LogScrubber.fingerprint(raw)` (or the project's equivalent) → short hash suitable for correlation without reverse-lookup.

## Log levels — discipline

| Level | Use for | Example |
|---|---|---|
| `TRACE` | Fine-grained flow (rarely committed; use temporarily) | `log.trace("entering method X with args={}", args)` |
| `DEBUG` | Detailed flow for diagnosis (off in production) | `log.debug("cache hit for key={}", key)` |
| `INFO` | Business events worth tracking | `log.info("subscription registered: hashtag={}", hashtag)` |
| `WARN` | Unexpected-but-handled, deserving operator attention | `log.warn("instance {} returned 429 — backing off", host)` |
| `ERROR` | Human attention required; something didn't work as designed | `log.error("mastodon sync failed for instance={}", host, ex)` |

Don't use ERROR for expected failure modes (e.g., a rate-limit is not an error — it's a handled outcome). Operator alerting usually fires on ERROR; overuse creates alert fatigue.

## AUDIT logger — dedicated channel for security events

`logback.xml` defines a logger named `"AUDIT"`. Get it explicitly:
```java
private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

AUDIT.info("auth.failure reason={} ip={} path={}", reason, scrubbedIp, path);
AUDIT.info("rate.limit.hit scope={} count={}", scope, count);
```

AUDIT emits at INFO level to stdout (per logback.xml documentation), separately routable if log aggregation wants to isolate security events. Events that belong here:

- Auth failures (invalid session, expired session, mismatched CSRF)
- Rate-limit hits (per-IP and per-wallId)
- SSRF blocks (at attempt time, with scrubbed attempted URL host)
- Security-header-bypass attempts
- Actuator access (if ever exposed)

Events that do NOT belong in AUDIT:
- Normal request flow
- Successful subscriptions (go to regular logger at INFO)
- Performance metrics (use Micrometer, see `spring-observability-micrometer` skill)

## `LogScrubber` — the utility class for sensitive values

Glacier has `src/main/java/de/seism0saurus/glacier/util/LogScrubber.java` with helpers. Before logging *anything* that might contain a cookie, token, or personal identifier, route it through `LogScrubber`.

Expected signatures (verify against actual code):
```java
LogScrubber.fingerprint(raw)        // → first-4-chars + short hash
LogScrubber.scrubIp(raw)            // → anonymized IP (last octet replaced, etc.)
LogScrubber.scrubCookie(raw)        // → presence/length indicator only
```

New logging code that handles sensitive input should either:
1. Use an existing `LogScrubber` helper, or
2. Add a new `LogScrubber` method + unit test in `LogScrubberTest`, and use it consistently.

Never do ad-hoc scrubbing inline (`raw.substring(0, 4) + "***"`) — it drifts, misses edge cases, and is not unit-tested.

## Request-path correlation

For request-level correlation across log lines, add a servlet filter that sets a requestId into MDC at entry and clears it at exit:

```java
@Component
public class MdcCorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString().substring(0, 8);
    try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
      chain.doFilter(req, resp);
    }
  }
}
```

Then every log line during that request carries `"requestId":"abc12345"` in the JSON output.

## Tests for logging invariants — they exist in Glacier

`LogScrubberTest` and `LoggingSmokeTest` already exist. When you add a new sensitive-data path, add tests:
- `LogScrubberTest` verifies scrubbing correctness (input X → output fingerprint-pattern).
- `LoggingSmokeTest` verifies that logging actually works end-to-end (JSON format valid, MDC emitted, etc.) without raw sensitive values appearing.

Test pattern for "this log call does not leak":
```java
@Test
void subscriptionLogDoesNotLeakWallId() {
  ListAppender<ILoggingEvent> appender = attachAppender(SubscriptionManagerImpl.class);
  subject.subscribe(principalWithWallId("SECRET_WALLID_123"), "#hashtag");
  assertThat(appender.list).noneMatch(e -> e.toString().contains("SECRET_WALLID_123"));
}
```

## Performance — log levels matter at scale

With JSON encoding every log line is serialized via Jackson. At high QPS (especially during a reconnect storm on WebSocket), DEBUG-level logs can become a CPU bottleneck. Rules:
- Set production root level to INFO or WARN.
- Use `log.isDebugEnabled()` guard only for expensive-to-format messages — for simple parameterized messages, Logback's own guard is cheap enough.
- Don't log inside tight loops. Aggregate and log once.

## Container log shipping

With `appendLineSeparator=true` and stdout output, container runtimes (Docker, Kubernetes) capture each log line as a separate record. JSON format means log aggregators (Loki, Elasticsearch, Datadog) can parse fields directly without grok/regex.

Don't add file appenders in a containerized deployment — just stdout. File-based logging defeats the log-collection pipeline.

## What Claude gets wrong without this skill

- Logs Authorization or Cookie headers directly → security violation.
- Puts `sessionId` or `wallId` into MDC → leaks via JSON layout.
- Uses `ERROR` for expected failures (rate-limit, validation) → alert fatigue.
- Uses `log.info("something happened: " + value)` (concatenation) → wasted toString() cycles when INFO is off.
- Forgets to pass exception as last argument → `ex.getMessage()` logged as string, stack trace lost.
- Creates ad-hoc scrubbing (`.substring`) instead of using `LogScrubber`.
- Sends security events to the regular logger instead of `AUDIT`.
- Adds a file appender for "convenience" → breaks container log shipping.

## References
- Logback config: `src/main/resources/logback.xml` — NOTE the security comments at the top (D-13, SR-8)
- Scrubber utility: `src/main/java/de/seism0saurus/glacier/util/LogScrubber.java`
- Scrubber tests: `src/test/java/de/seism0saurus/glacier/util/LogScrubberTest.java`
- Smoke test: `src/test/java/de/seism0saurus/glacier/util/LoggingSmokeTest.java`
- SLF4J parameterized messages: https://www.slf4j.org/faq.html#logging_performance
- Logback MDC: https://logback.qos.ch/manual/mdc.html
