---
name: spring-error-handling-problem-details
owner: "@seism0saurus"
description: Structure error handling in Glacier's Spring Boot backend using RFC 7807 ProblemDetail (Spring 6 native), @ExceptionHandler hierarchy in FallbackControllerAdvice, and error-code conventions that align with the frontend's i18n catalog keys. TRIGGER when editing FallbackControllerAdvice, @ControllerAdvice classes, @ExceptionHandler methods, custom exception classes under webservice/, ResponseStatusException usage, error DTOs, or when the user mentions error handling, ProblemDetail, RFC 7807, exception mapping, error code, @ControllerAdvice, @ExceptionHandler. SKIP for client-side error handling (Angular), log-side error handling (glacier-structured-logging-logback), or non-error code paths.
---

# Error Handling with RFC 7807 ProblemDetail for Glacier

Spring Framework 6 ships native `ProblemDetail` (RFC 7807). Glacier already has `FallbackControllerAdvice` — new error-handling logic belongs there (or in a peer advice scoped to specific controllers).

## Why RFC 7807 / ProblemDetail

- Standardized machine-readable error response format across clients.
- Browser-compatible `Content-Type: application/problem+json`.
- Extensible with custom fields without breaking consumers.
- Spring 6 has first-class support — no third-party library needed.

Shape:
```json
{
  "type": "https://glacier.example.com/errors/validation-failed",
  "title": "Validation Failed",
  "status": 400,
  "detail": "One or more fields failed validation",
  "instance": "/api/subscriptions",
  "errorCode": "validation_failed",
  "fields": ["hashtag: must match [A-Za-z0-9...]"]
}
```

`type` (URI), `title`, `status` are RFC-standard. `errorCode`, `fields` are Glacier-specific extensions — they map to frontend i18n keys.

## @ExceptionHandler basic pattern

```java
@RestControllerAdvice
public class FallbackControllerAdvice {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail onValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setType(URI.create("https://glacier.example.com/errors/validation-failed"));
    pd.setTitle("Validation Failed");
    pd.setDetail("One or more fields failed validation");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("errorCode", "validation_failed");
    pd.setProperty("fields", ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
        .toList());
    return pd;
  }
}
```

Key rules:
- Return `ProblemDetail` directly — Spring serializes it with the correct `application/problem+json` content type.
- Never return raw `Exception::getMessage()` in `detail` — it may leak internal paths, IPs, or framework internals.
- Log the full exception server-side (see `glacier-structured-logging-logback` skill); expose only what the client needs.

## Exception hierarchy for Glacier

Define domain exceptions, map each to a specific `ProblemDetail`:

```java
public class RateLimitedException extends RuntimeException {
  private final long retryAfterSeconds;
  public RateLimitedException(long retryAfter) { this.retryAfterSeconds = retryAfter; }
  public long getRetryAfterSeconds() { return retryAfterSeconds; }
}

public class SsrfBlockedException extends RuntimeException { /* ... */ }
public class UnknownSubscriptionException extends RuntimeException { /* already in cache package */ }
public class CacheCapacityException extends RuntimeException { /* already in cache package */ }
```

Map each in the advice:
```java
@ExceptionHandler(RateLimitedException.class)
public ResponseEntity<ProblemDetail> onRateLimited(RateLimitedException ex) {
  ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
  pd.setTitle("Rate Limited");
  pd.setDetail("Too many requests; please retry later.");
  pd.setProperty("errorCode", "rate_limited");
  pd.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
  return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
      .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
      .body(pd);
}

@ExceptionHandler(SsrfBlockedException.class)
public ProblemDetail onSsrf(SsrfBlockedException ex) {
  // DO NOT leak the blocked IP / resolved host
  ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
  pd.setTitle("URL not allowed");
  pd.setDetail("The supplied URL is not permitted.");
  pd.setProperty("errorCode", "url_not_allowed");
  return pd;
  // Full details go to server log (see glacier-structured-logging-logback skill)
}
```

## `errorCode` — the bridge to the i18n catalog

The frontend's `messages.en.json` uses keys like `rate.limited.snackbar`, `session.expired.banner`. The backend's `errorCode` field **must match** or map 1:1 to these keys so the Angular app can render localized messages:

| Backend `errorCode` | Frontend i18n key | User-visible (DE source) |
|---|---|---|
| `rate_limited` | `rate.limited.snackbar` | "Zu viele Anfragen..." |
| `validation_failed` | depends on `fields` — frontend renders field-specific | — |
| `url_not_allowed` | e.g., `url.invalid.message` | "Die URL ist nicht zulässig." |
| `session_expired` | `session.expired.banner` | "Ihre Sitzung ist abgelaufen." |
| `unknown_subscription` | custom | — |

Keep the `errorCode` vocabulary **stable** — changes break the frontend's i18n mapping. Extend by adding new codes; don't rename existing ones without a coordinated frontend change.

## Catch-all handler — last line of defense

```java
@ExceptionHandler(Exception.class)
public ProblemDetail onUnexpected(Exception ex, HttpServletRequest req) {
  log.error("unexpected error at {}: {}", req.getRequestURI(), ex.toString(), ex);
  ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
  pd.setTitle("Internal Server Error");
  pd.setDetail("An unexpected error occurred.");     // generic, no stacktrace/message
  pd.setProperty("errorCode", "internal_error");
  return pd;
}
```

Without this, unhandled exceptions fall through to Spring's default error handler — which may leak the class name or trace depending on dev-mode config. Always have an explicit catch-all.

## Never leak: stacktraces, internal paths, DB details, IP addresses

Things to keep **server-side only** (log them, don't return them):
- Stack traces.
- Internal file paths.
- Resolved IP addresses (SSRF context).
- Database error messages (would also expose table/column names).
- Bigbone/HTTP internal exception text ("Connection refused to 10.0.1.23:443").

Return a generic `detail` to the client; log the rich detail. See `glacier-structured-logging-logback` skill for the logging side.

## `ResponseStatusException` — when to use (rarely)

Spring's `ResponseStatusException` is a quick-and-dirty shortcut:
```java
throw new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription unknown");
```

It works but:
- No `errorCode` / i18n mapping.
- Detail string goes straight to the response (easy to leak info).
- Advice mapping is preferred for consistency.

Use `ResponseStatusException` only in one-off scenarios; for anything the frontend translates, use a domain exception + advice mapping.

## Testing error handlers

Unit-test the advice with MockMvc (see `spring-boot-testing-patterns` skill):

```java
@WebMvcTest(SubscriptionController.class)
@Import(FallbackControllerAdvice.class)
class SubscriptionControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean SubscriptionManager manager;

  @Test
  void rateLimitExceptionMapsTo429() throws Exception {
    when(manager.subscribe(any(), any())).thenThrow(new RateLimitedException(30));

    mvc.perform(post("/api/subscriptions").contentType(APPLICATION_JSON)
            .content("""{"hashtag":"test"}"""))
       .andExpect(status().isTooManyRequests())
       .andExpect(header().string("Retry-After", "30"))
       .andExpect(jsonPath("$.errorCode").value("rate_limited"))
       .andExpect(jsonPath("$.retryAfterSeconds").value(30));
  }
}
```

Glacier has `FallbackControllerAdviceTest` already — follow its patterns.

## What Claude gets wrong without this skill

- Returns `new Error(ex.getMessage())` or `Map.of("error", ex.getMessage())` → leaks internal details.
- Uses a custom `ErrorResponse` record instead of `ProblemDetail` → reinvents RFC 7807, clients miss the standard content type.
- Forgets to set `errorCode` → frontend can't localize the error.
- Puts the catch-all `@ExceptionHandler(Exception.class)` before specific handlers → specific handlers never get a chance.
- Uses `throw new ResponseStatusException(HttpStatus.BAD_REQUEST, userInput)` → user input echoed into response (XSS vector if HTML-rendered).
- Logs the full exception inside the handler AND returns the message → leak + double-emission.
- Doesn't test the mapping → regression on exception type changes.

## References
- Primary advice: `src/main/java/de/seism0saurus/glacier/webservice/FallbackControllerAdvice.java`
- Advice test: `src/test/java/de/seism0saurus/glacier/webservice/FallbackControllerAdviceTest.java`
- Custom exceptions: `CacheCapacityException`, `UnknownSubscriptionException` (both in `webservice/cache/`)
- Frontend i18n keys (for `errorCode` alignment): `frontend/src/assets/i18n/messages.en.json`
- RFC 7807 spec: https://www.rfc-editor.org/rfc/rfc7807
- Spring `ProblemDetail`: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ProblemDetail.html
