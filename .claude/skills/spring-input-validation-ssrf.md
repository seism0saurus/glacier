---
name: spring-input-validation-ssrf
owner: "@seism0saurus"
description: Apply Jakarta Bean Validation and SSRF prevention in the Glacier backend's controllers and outbound URL handling (Mastodon instance URLs, embed-fetcher URLs). TRIGGER when editing @RequestBody DTOs, @RequestParam/@PathVariable handlers, Bean Validation constraints (@Valid, @Validated, @NotNull, @Size, @Max, @Pattern, @URL), URL-accepting endpoints, embed-fetcher URL handling, Mastodon instance parsing, or when the user mentions SSRF, input validation, @Valid, Bean Validation, URL allowlist, URL scheme, private IP, DNS rebinding, redirect handling, path traversal. SKIP for response-side handling, security headers (that's in spring-security-hardening), or non-input code.
---

# Input Validation & SSRF Prevention for Glacier

Glacier accepts user input in two sensitive places:

1. **Inbound controllers** (`SubscriptionController`, `FallbackController`, `InformationController`) receive hashtags, subscription parameters, client metadata.
2. **Outbound URL fetching** — Mastodon instance URLs from `application.properties` (`mastodon.instance`), embed URLs extracted from toot content, media URLs referenced in streams.

The second is a real SSRF vector. Unvalidated URLs reach `http://169.254.169.254/` (cloud metadata), `http://127.0.0.1:8080/internal/actuator` (Glacier's own Actuator), or internal-network services. For a fediverse-adjacent service, this is not theoretical.

## Bean Validation — `@Valid` on `@RequestBody` (don't forget it)

```java
@PostMapping("/api/subscriptions")
public SubscriptionResponse subscribe(
    @Valid @RequestBody SubscribeRequest request) {
  // validation errors become MethodArgumentNotValidException → 400
}

record SubscribeRequest(
    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[A-Za-z0-9äöüÄÖÜß_]{1,100}$",
             message = "hashtag must be alphanumeric with German letters and underscore")
    String hashtag,

    @Min(1) @Max(100)
    int pageSize
) {}
```

Without `@Valid` the constraint annotations are silently ignored. Controller-advice (`FallbackControllerAdvice`) maps `MethodArgumentNotValidException` to 400.

## DoS via unbounded inputs — always cap collections and strings

```java
record BulkRequest(
    @Size(max = 50)
    List<@NotBlank @Size(max = 100) String> hashtags
) {}
```

Without `@Size`, a malicious client sends a 10 MB JSON array and exhausts memory parsing. Pair with request-size limits (`spring.servlet.multipart.max-request-size`, see `spring-security-hardening` skill).

Inner-element validation: `List<@NotBlank String>` validates each element — note the annotation **inside** the type parameter, not on `List`.

## Constraint groups — different flows, different rules

```java
public interface OnCreate {}
public interface OnUpdate {}

record Subscription(
    @Null(groups = OnCreate.class)
    @NotNull(groups = OnUpdate.class)
    UUID id,

    @NotBlank String hashtag
) {}

// Controller
@PostMapping
public Subscription create(@Validated(OnCreate.class) @RequestBody Subscription s) {...}

@PutMapping
public Subscription update(@Validated(OnUpdate.class) @RequestBody Subscription s) {...}
```

Use when the same DTO serves create and update with different requirements. Don't over-engineer: separate DTOs are often cleaner.

## SSRF — the core threat for a fediverse client

User gives you a URL (Mastodon instance, embed target). Before issuing an HTTP request, validate exhaustively.

### 1. Scheme allowlist

```java
private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

URI uri = URI.create(input);
if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
  throw new IllegalArgumentException("scheme not allowed: " + uri.getScheme());
}
```

Block `file://`, `gopher://`, `jar://`, `data:`, `javascript:`, anything not explicitly allowed. Require absolute URIs — a relative URI resolved against a base you don't control is dangerous.

For production, prefer `https` only (`Set.of("https")`).

### 2. Resolve host to IP, block private/internal ranges

```java
try {
  InetAddress[] addrs = InetAddress.getAllByName(uri.getHost());
  for (InetAddress a : addrs) {
    if (isBlockedAddress(a)) {
      throw new SsrfBlockedException("resolved to internal/reserved address");
    }
  }
} catch (UnknownHostException e) {
  throw new IllegalArgumentException("host unresolvable");
}

static boolean isBlockedAddress(InetAddress a) {
  if (a.isLoopbackAddress()) return true;        // 127/8, ::1
  if (a.isLinkLocalAddress()) return true;       // 169.254/16, fe80::/10
  if (a.isSiteLocalAddress()) return true;       // 10/8, 172.16/12, 192.168/16
  if (a.isAnyLocalAddress()) return true;        // 0.0.0.0, ::
  if (a.isMulticastAddress()) return true;
  String addr = a.getHostAddress();
  if (addr.equals("169.254.169.254")) return true;   // cloud metadata (AWS/GCP/Azure)
  if (addr.startsWith("100.64.")) return true;       // CGNAT shared address space
  if (addr.startsWith("fc") || addr.startsWith("fd")) return true;  // ULA (IPv6)
  return false;
}
```

Log blocked attempts server-side (with the resolved IP, for forensic reasons). **Return a generic error to the client** ("URL not allowed") — don't leak the internal IP.

### 3. Prevent DNS rebinding — resolve once, connect by IP

DNS rebinding attack:
- Attacker's domain `rebind.attacker.example` returns `1.2.3.4` during your validation lookup.
- Attacker's DNS re-resolves to `127.0.0.1` before your HTTP library's own lookup.
- HTTP call goes to localhost; validation was useless.

Defense: resolve once yourself, validate, then **connect to the IP directly** while preserving the Host/SNI header:

```java
InetAddress resolved = InetAddress.getByName(uri.getHost());
validateSsrf(resolved);   // as above

// OkHttp: provide a custom Dns that returns the pre-resolved address only.
OkHttpClient safeClient = new OkHttpClient.Builder()
    .dns(hostname -> {
      if (hostname.equals(uri.getHost())) {
        return List.of(resolved);
      }
      return Dns.SYSTEM.lookup(hostname);
    })
    .followRedirects(false)
    .build();
```

For Spring's `RestClient`/JDK `HttpClient`: construct the URL with the IP and set `Host` header explicitly — verify TLS/SNI still works correctly.

### 4. Disable automatic redirect-following

Redirects can redirect `https://user-supplied.example.com` → `http://169.254.169.254/`. Either disable auto-follow and handle manually with re-validation, or inspect the `Location` in a redirect interceptor:

```java
OkHttpClient embedHttp = new OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build();

// Handle 3xx manually:
Response response = embedHttp.newCall(request).execute();
if (response.code() >= 300 && response.code() < 400) {
  String location = response.header("Location");
  // Re-validate the location through the full SSRF pipeline,
  // then re-issue the request if safe.
}
```

### 5. Tight timeouts + response-size cap

Even a legitimate URL can respond slowly or with huge body. Glacier's `glacier.embed.readTimeoutMs=5000` sets the right tone. Also cap the response size:

```java
try (InputStream body = response.body().byteStream()) {
  byte[] buf = body.readNBytes(MAX_EMBED_BYTES);   // e.g., 256 KB
  if (body.read() != -1) {
    throw new ResponseTooLargeException();
  }
  return decode(buf);
}
```

## Glacier's Mastodon-instance URL validation

When a user declares their Mastodon instance (currently via `application.properties`, but if ever user-configurable):
1. Parse with `URI.create`; require absolute URI.
2. Enforce `scheme == "https"` in production (`http` only for dev).
3. Resolve host, apply SSRF blocklist.
4. Optionally require the instance to serve a valid `/.well-known/nodeinfo` endpoint — sanity-checks that it is a real Mastodon-compatible server.

## Path traversal — if Glacier ever accepts filenames

No file-upload or file-by-name endpoint exists as of 2026-04, but if one is added:

```java
Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
Path target = base.resolve(userFilename).normalize();
if (!target.startsWith(base)) {
  throw new IllegalArgumentException("path escapes base");
}
```

`normalize()` collapses `..`; the `startsWith` guard confirms no escape. `File::getCanonicalPath` (follows symlinks) is an additional layer.

## Exception → HTTP mapping

In `FallbackControllerAdvice`:
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> onValidationError(MethodArgumentNotValidException e) {
  List<String> errors = e.getBindingResult().getFieldErrors().stream()
      .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
      .toList();
  return ResponseEntity.badRequest().body(new ErrorResponse("validation_failed", errors));
}

@ExceptionHandler(SsrfBlockedException.class)
public ResponseEntity<ErrorResponse> onSsrfBlocked(SsrfBlockedException e) {
  log.warn("SSRF attempt blocked", e);  // server-side log; leak nothing to client
  return ResponseEntity.badRequest().body(new ErrorResponse("url_not_allowed", List.of()));
}

@ExceptionHandler(ResponseTooLargeException.class)
public ResponseEntity<ErrorResponse> onTooLarge(ResponseTooLargeException e) {
  return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(new ErrorResponse("remote_response_too_large", List.of()));
}
```

Never surface resolved IPs, internal paths, or exception stack traces to the client. Log fully server-side; respond generically.

## Testing the validation pipeline

Integration tests (Failsafe `*IT.java`) for the SSRF pipeline:
```java
@Test
void blocksLoopbackAddress() {
  assertThatThrownBy(() -> embedFetcher.fetch(URI.create("http://127.0.0.1:8080/")))
      .isInstanceOf(SsrfBlockedException.class);
}

@Test
void blocksMetadataEndpoint() {
  assertThatThrownBy(() -> embedFetcher.fetch(URI.create("http://169.254.169.254/")))
      .isInstanceOf(SsrfBlockedException.class);
}
```

Glacier uses Failsafe for `*IT.java` (per CLAUDE.md). Add SSRF-specific integration tests as `EmbedFetcherSsrfIT`.

## What Claude gets wrong without this skill

- Uses `@Valid` on the controller parameter but the DTO fields have no constraint annotations → validation passes silently.
- Validates URLs only with regex — trivially bypassed: `http://evil.com#@169.254.169.254`, `http://evil.com\\@169.254.169.254`, URL-encoded tricks.
- Trusts `uri.getHost()` without DNS resolution → DNS rebinding unprotected.
- Lets the HTTP library follow redirects → SSRF via redirect to internal IP.
- Omits `@Size` on `List`/`String` fields → DoS via huge payloads.
- Returns raw exception messages including internal IPs → information leak.
- Uses Hibernate Validator's `@URL` annotation alone — it checks format, not reachability/target → SSRF not prevented.
- Re-validates the redirect `Location` only against the original input's domain → misses that the `Location` resolves to a different IP.

## References
- Controllers: `src/main/java/de/seism0saurus/glacier/webservice/SubscriptionController.java`, `FallbackController.java`, `InformationController.java`
- Exception mapping: `FallbackControllerAdvice.java`
- Mastodon config: `src/main/java/de/seism0saurus/glacier/mastodon/MastodonConfiguration.java`, `application.properties` (`mastodon.instance`)
- Embed fetcher: search `src/main/java` for `embed` usage (`glacier.embed.*` properties suggest a dedicated fetcher class)
- OWASP SSRF Prevention: https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- Jakarta Bean Validation 3.0: https://jakarta.ee/specifications/bean-validation/3.0/
