---
name: spring-security-hardening
owner: "@seism0saurus"
description: Harden Glacier's Spring Boot backend against common web-security risks without relying on spring-boot-starter-security — covering CORS, WebSocket Origin allowlist, security response headers (CSP, HSTS, X-Frame-Options, Referrer-Policy), cookie flags, forwarded-headers handling, and Actuator exposure. TRIGGER when editing CorsConfigurer/addCorsMappings, @CrossOrigin, WebSocket setAllowedOrigins, FallbackSecurityHeadersFilter, cookie/session settings, forward-headers-strategy, management.endpoints exposure, or when the user mentions CORS, CSP, HSTS, security headers, Origin, Referrer, cookie flags, Actuator security, CSRF, X-Frame-Options. SKIP for input-validation and SSRF (that's in spring-input-validation-ssrf), authN/authZ implementation, or test-only code.
---

# Spring Boot Security Hardening for Glacier (no Spring Security starter)

Glacier does **not** use `spring-boot-starter-security` — authentication is handled upstream (Mastodon session cookies, Traefik-level auth). That does not eliminate the need for application-level hardening: the backend still accepts requests directly and must defend against CORS misuse, WebSocket cross-origin attacks, header-level attacks, and accidental Actuator disclosure.

Existing touch-points:
- CORS: `GlacierApplication.java` (check for `addCorsMappings` / `CorsConfigurationSource`)
- WebSocket origins: `WebSocketConfiguration.java`
- Security headers: `FallbackSecurityHeadersFilter.java` — new security-header logic belongs here
- Actuator: `application.properties` (`management.*`)
- Cookies: `glacier.cookie.secure=true` property

## CORS — explicit allowlist, narrow scope

Rules of the road:
- **Never** combine `setAllowedOrigins("*")` with `setAllowCredentials(true)`. Browsers reject this, but the combination is a code smell.
- Prefer **exact origin values** over wildcard patterns. `https://*.example.com` rarely matches only what you actually want.
- Allow only the HTTP methods and headers the frontend actually uses.

```java
@Bean
WebMvcConfigurer corsConfigurer() {
  return new WebMvcConfigurer() {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
      registry.addMapping("/api/**")
          .allowedOrigins(
              "https://glacier.example.com",
              "https://glacier-staging.example.com"
          )
          .allowedMethods("GET", "POST", "OPTIONS")
          .allowedHeaders("Content-Type", "Accept")
          .allowCredentials(false)   // only true if session cookies cross origins
          .maxAge(3600);
    }
  };
}
```

If cookie-based authentication crosses origins, `allowCredentials(true)` **requires** specific origins — `"*"` is rejected by the browser. Glacier's `glacier.cookie.secure=true` is compatible either way, but verify whether the cookie actually crosses the CORS boundary.

## WebSocket Origin allowlist — same rigor

STOMP/SockJS endpoint:
```java
registry.addEndpoint("/api/ws")
    .setAllowedOrigins(
        "https://glacier.example.com",
        "https://glacier-staging.example.com"
    )
    .withSockJS();
```

**Never** `setAllowedOriginPatterns("*")` in production. WebSocket handshake is the one place the browser does NOT apply same-origin policy — the server-side Origin check is the backstop against cross-site WebSocket hijacking.

See `spring-websocket-performance` skill for the non-security dimensions of WebSocket config.

## Security response headers — implement in `FallbackSecurityHeadersFilter`

Minimum set to emit on every response:

```java
response.setHeader("Content-Security-Policy",
    "default-src 'self'; " +
    "script-src 'self'; " +
    "style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data: https:; " +
    "connect-src 'self' wss:; " +
    "frame-ancestors 'none'; " +
    "base-uri 'self'; " +
    "form-action 'self'"
);
response.setHeader("X-Content-Type-Options", "nosniff");
response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
response.setHeader("X-Frame-Options", "DENY");  // legacy, CSP frame-ancestors is primary
response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=()");
response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
```

### CSP caveats

- **Start strict, relax deliberately**: `default-src 'self'` as the floor; add only what the app actually needs.
- **`img-src`**: Mastodon avatars/embed images come from arbitrary hosts — `https:` is pragmatic, but consider a specific allowlist if the instance set is bounded.
- **`connect-src 'self' wss:`**: needed for WebSocket; narrow the `wss:` to specific hosts if you can.
- **`'unsafe-inline'` in `style-src`**: often needed by Angular Material's runtime styles. Try removing first and testing; if Material breaks, keep but revisit after migration to CSP nonces.
- **Do NOT add `X-XSS-Protection`** — deprecated, causes issues in some browsers, replaced by CSP.

### Report-only mode for safe CSP rollout

When introducing or tightening CSP:
```java
response.setHeader("Content-Security-Policy-Report-Only", candidatePolicy);
```

Observe `report-to` endpoint (or browser console) for a week before promoting to enforcing. Avoids breaking production.

## Cookie flags

Already defaulted to secure in `application.properties` — also ensure:

```properties
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax    # or strict for tighter CSRF defense
```

- `HttpOnly`: session token unreadable from JS → mitigates XSS token theft.
- `Secure`: cookie only sent over HTTPS. Glacier has `glacier.cookie.secure=true` — aligns.
- `SameSite=Lax` (default since modern browsers): blocks cross-site subrequest cookie sending (CSRF mitigation), allows top-level navigation. `Strict` if the cookie is only ever needed for same-site navigation.

## `server.forward-headers-strategy` — trust only behind a trusted proxy

Glacier currently defaults to `NONE`:
```properties
server.forward-headers-strategy=${FORWARD_HEADERS_STRATEGY:NONE}
```

Override **only** when Glacier is behind a trusted reverse proxy (Traefik/nginx) that sets `X-Forwarded-For`, `X-Forwarded-Proto`, etc.:

```properties
server.forward-headers-strategy=FRAMEWORK   # Spring's ForwardedHeaderFilter
# or NATIVE for Tomcat's RemoteIpValve
```

Danger: if enabled without a trusted proxy, any client can forge source-IP via `X-Forwarded-For` and bypass the rate limiter (`FallbackRateLimiter`'s `per_ip` counters).

Practical rule: set via environment variable only in deployments where Traefik strips/rewrites forwarded headers. Keep `NONE` by default.

## Actuator exposure — Glacier is already right

Current config is the gold standard:
```properties
management.endpoints.web.exposure.include=health,info
management.endpoints.web.base-path=/internal/actuator
management.endpoint.health.show-details=never
```

Rules to preserve:
- **Never** expose `env`, `configprops`, `beans`, `heapdump`, `threaddump`, `metrics`, `prometheus`, `mappings`, `loggers` on the public port.
- If broader exposure is needed, use a **separate management port** bound to a private interface:
  ```properties
  management.server.port=8081
  management.server.address=127.0.0.1
  ```
- Or put management endpoints behind Traefik-level authentication.
- `show-details=never` keeps health response minimal — `UP`/`DOWN` only. `when_authorized` acceptable if actual auth is in place.

## Request-size limits — mitigate DoS

```properties
spring.servlet.multipart.max-file-size=1MB
spring.servlet.multipart.max-request-size=2MB
server.tomcat.max-http-form-post-size=256KB
server.tomcat.max-swallow-size=2MB
```

For JSON bodies, use `@Size` / `@Max` on Bean Validation constraints — see `spring-input-validation-ssrf` skill.

## CSRF — what to do without Spring Security

- **`SameSite=Strict` / `Lax` on session cookies** is the first line of defense (already recommended above).
- For state-changing endpoints (POST/PUT/DELETE), verify `Origin` **or** `Referer` header matches the expected origin:
  ```java
  String origin = request.getHeader("Origin");
  String referer = request.getHeader("Referer");
  String source = origin != null ? origin : referer;
  if (source == null || !ALLOWED_ORIGINS.contains(extractOrigin(source))) {
    throw new ForbiddenException("unexpected Origin/Referer");
  }
  ```
- Or add a classic CSRF double-submit-cookie pattern: server sets a non-HttpOnly CSRF cookie, client echoes value in a header, server compares.

## Security test coverage

Glacier already has Playwright tests with `@axe-core/playwright`. Add security-focused e2e assertions:
```typescript
test('security headers present on /', async ({ request }) => {
  const res = await request.get('/');
  expect(res.headers()['content-security-policy']).toContain("default-src 'self'");
  expect(res.headers()['strict-transport-security']).toMatch(/max-age=\d+/);
  expect(res.headers()['x-content-type-options']).toBe('nosniff');
  expect(res.headers()['x-frame-options']).toBe('DENY');
});
```

## What Claude gets wrong without this skill

- `.allowedOrigins("*")` combined with `allowCredentials(true)`.
- `setAllowedOriginPatterns("*")` on the WebSocket endpoint.
- Forgets `HttpOnly` / `SameSite` on cookies.
- Adds deprecated `X-XSS-Protection: 1; mode=block`.
- Exposes `/actuator/env`, `/actuator/metrics` without noticing the information disclosed.
- Enables `server.forward-headers-strategy=NATIVE` on an app not actually behind a trusted proxy → spoofable source IP → rate-limit bypass.
- Adds CSP directly in enforcing mode without a report-only rollout → breaks Angular Material styles in production.
- Returns stack traces in error responses (default dev error handler) → leaks package paths and versions.

## References
- CORS config: `src/main/java/de/seism0saurus/glacier/GlacierApplication.java`
- WebSocket origins: `src/main/java/de/seism0saurus/glacier/webservice/messaging/WebSocketConfiguration.java`
- Security headers filter: `src/main/java/de/seism0saurus/glacier/webservice/cache/FallbackSecurityHeadersFilter.java`
- Actuator config: `application.properties` (`management.*`)
- Cookie property: `glacier.cookie.secure`
- OWASP Secure Headers: https://owasp.org/www-project-secure-headers/
- MDN CSP: https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP
