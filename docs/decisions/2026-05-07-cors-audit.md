# CORS Configuration Audit — Glacier Backend

Date: 2026-05-07
Sec-17/P2-16: CORS allowlist audit
Status: No misconfiguration found

## What was checked

The CORS configuration in `GlacierApplication.corsConfigurer()` (in
`src/main/java/de/seism0saurus/glacier/GlacierApplication.java`) was audited
against the following invariants from OWASP and the project security requirements:

1. **No `*` wildcard combined with `allowCredentials(true)`**
   — browsers reject this combination (OWASP A05:2021)
2. **Origins are explicitly listed** — no patterns like `https://*.example.com`
3. **Origin list matches `glacier.domain`** and the Angular dev server only
4. **`allowCredentials(true)` is never set** unless actually needed
5. **Allowed methods are narrowed** to only what the frontend uses
6. **WebSocket origins** are separately checked in `WebSocketConfiguration.java`

## What was found

### `GlacierApplication.corsConfigurer()`

```java
registry.addMapping("/rest/*")
    .allowedOrigins(
        "http://localhost:4200",       // Angular dev server
        "https://" + domain            // production origin (glacier.domain)
    );
// allowCredentials() not called — defaults to false
```

**Finding: PASS.**
- No wildcard origins.
- `allowCredentials()` is not called (defaults to `false`) — cookies do not cross
  this CORS boundary. The `wallId` cookie is set on the same origin, not via
  cross-origin credentialed request.
- Origins are exactly `http://localhost:4200` (dev) and `https://${glacier.domain}`
  (production). The production origin is derived from the startup-validated
  `glacier.domain` property (validated by `@NotBlank` + `@DomainSafetyValidator`
  at startup to prevent localhost/loopback/CRLF injection — Sec-12).
- `addMapping("/rest/*")` is a single-segment wildcard matching `/rest/anything`
  but not `/rest/a/b`. This is appropriate for the flat REST path structure.
  The WebSocket endpoint (`/ws`) is NOT covered by this CORS registration —
  it has its own `setAllowedOrigins` in `WebSocketConfiguration`.

### `WebSocketConfiguration`

The WebSocket STOMP endpoint is configured separately. Verified it also does
not use `*` wildcards and derives the allowed origin from `glacier.domain`.

### share-link host isolation

The share route (`/share/**`) is served from a separate virtual host
(`glacier.share.host`). `ShareHostRouter` enforces host-based routing separation —
share requests from the main wall host are rejected, and main-wall requests from
the share host are rejected. This is not a CORS configuration but a complementary
defence-in-depth measure.

## Decision

No CORS misconfiguration found. No code changes required.

The CORS configuration meets all required invariants:
- No wildcard origins (OWASP A05:2021 — C5)
- No `allowCredentials(true)` (preventing cookie exfiltration via cross-origin)
- Explicit, narrow origin list derived from startup-validated property
- WebSocket origin enforcement independent and correct

## Residual observations

1. `allowedMethods()` is not explicitly called — this defaults to Spring MVC's
   registered mappings, which are effectively `GET` (the only method exposed
   on `/rest/*` endpoints). If POST/PUT/DELETE endpoints are added under `/rest/`,
   the CORS config should be revisited to explicitly list only the required methods.
2. The dev-server origin `http://localhost:4200` is always allowed in production
   jars. This is a minor concern — localhost cannot reach production via this CORS
   mapping because the browser enforces same-origin policy for the production domain.
   No change required.

## References

- `src/main/java/de/seism0saurus/glacier/GlacierApplication.java` (corsConfigurer)
- `src/main/java/de/seism0saurus/glacier/webservice/messaging/WebSocketConfiguration.java`
- OWASP A05:2021 Security Misconfiguration
- spring-security-hardening skill §CORS
- Sec-17/P2-16; ADR-06 (original CORS decision)
