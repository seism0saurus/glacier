# Decision Record: Share Link with QR Code (Readonly Wall View) — Planning

Date: 2026-04-22
Phase: Planning
Agents: `ddd-tdd-architect`, `secure-feature-planner`, `ux-ui-designer`
Status: Accepted

## Summary

Glacier gains a **share-link** feature that lets a sharer publish a time-bounded, revocable readonly URL of their own wall, displayed as a small QR code on the main wall (expandable into a share dialog) and reached through an opaque shareable link. The readonly view is **accessibility-first** — it exists to give screen-reader users, zoom users, and keyboard/motor-impaired users a navigable, high-fidelity feed that the iframe-based main wall cannot deliver. Every toot is rendered via **structured native rendering** (server-side Jsoup text extraction → `ReadonlyTootView` DTO → Angular interpolation only — no `[innerHTML]`, no iframes). Links, images, mentions, hashtags, custom emojis, media and polls each arrive as structured DTO fields with explicitly scheme-allowlisted URLs. All avatars / emojis / media are fetched through a hardened server-side **image proxy** (HMAC-signed URL, SSRF-guarded, 1 MB cap, non-SVG content-type allowlist, private-IP blocklist with DNS pinning, 1 h + 60 s negative cache, CORP/nosniff/CSP-enforced responses). Viewers are issued a distinct typed principal (`ShareViewerPrincipal`) bound to the specific share link, scoped to a dedicated origin (`share.${glacier.domain}`) so cookies, local storage, service-worker contexts and CSP cannot cross-pollinate with the main wall. Link lifetime is **7 days**; sharers can revoke at any time; revocation pushes a `/topic/share/{id}/control` frame that severs viewer sessions within 1 s.

## Key Decisions

### Decision: New bounded context `de.seism0saurus.glacier.share`

**Decision**: Introduce a new DDD bounded context with `domain`, `application`, `infrastructure`, and `web` subpackages, orthogonal to `mastodon`, `webservice.cache`, `webservice.messaging`. The `ShareLink` aggregate has its own lifecycle (ACTIVE / EXPIRED / REVOKED), repository (`InMemoryShareLinkRepository` + scheduled sweeper), and authorization model (sharer-only revocation). Share-link issuance does **not** live in `SubscriptionManagerImpl`.
**Rationale**: Lifecycle + authorization + consistency boundary are orthogonal to hashtag subscriptions. A separate context lets the security planner apply a dedicated threat model to `share.*` without re-threading `mastodon.*`, allows full isolation testing, and preserves existing invariants.
**Alternatives considered**: Extending `SubscriptionController`; stashing share state in `MessageCacheImpl`.
**Source**: `ddd-tdd-architect` Round 1 §2; ADR-SHARE-01.

### Decision: Typed principals replace the `sv_` string prefix

**Decision**: Introduce a sealed `GlacierPrincipal` interface with `WallPrincipal` and `ShareViewerPrincipal` (the latter bound to a `shareLinkId` at handshake). Migrate `MessageCacheImpl` and rate-limit buckets from `String`-keyed to `PrincipalKey(kind, name)`-keyed — compile-enforced.
**Rationale**: A cookie-name prefix (`sv_`) is client-controlled and a convention; a type hierarchy is structural and cannot be spoofed. This closes cross-namespace collision attacks in the cache and audit log.
**Alternatives considered**: Cookie-prefix discipline (Round 1); entirely separate cache stores per principal kind.
**Source**: `secure-feature-planner` Round 1 ⚡#4; accepted in architect Round 2 §4; ADR-SHARE-05 (revised).

### Decision: Structured native rendering on the readonly view (Option 4C + 4D combined)

**Decision**: Main wall unchanged (iframes; `StompCallback.isLoadable` authoritative). Readonly view renders toots from a `ReadonlyTootView` DTO produced by `ShareRenderingService`, which uses Jsoup `Jsoup.parse(html).text()` with paragraph/break handling for body text, plus structured fields (`mentions`, `tags`, `emojis`, `media_attachments`, `poll`, `spoiler_text`) for everything else. Angular renders each field through `{{ }}` interpolation; zero `[innerHTML]` anywhere. URLs validated by `DefaultSafeUrlValidator` (scheme allowlist `http|https`, no userinfo, no control/bidi chars, IPv6 normalisation, private-IP blocklist for the image-proxy variant). Input is capped at 8 KB; over-size content is replaced by a localised placeholder.
**Rationale**: Structural XSS defence with minimal attack surface (Angular's interpolation escaping + a narrow `isSafeUrl()` function) instead of policy-based sanitizer allowlists (historically CVE-prone). Single auditable server-side choke point.
**Alternatives considered**: DOMPurify client-side; Angular default sanitiser; dual extraction (server + client).
**Source**: User Step-0 decision (Option 4C+4D); `ddd-tdd-architect` Round 1 §7; ADR-SHARE-03.

### Decision: Server-side image proxy for all viewer-visible images

**Decision**: `GET /rest/share/img-proxy?u={signed-url}` with HMAC-SHA256 signing keyed by `glacier.share.imgproxy.hmacSecret`. Connect/read timeouts 3s/5s, redirects disabled, 1 MB size cap via bounded `InputStream` reader, content-type allowlist `image/png,image/jpeg,image/gif,image/webp` (SVG rejected). `SafeUrlValidator` blocks private-IP / loopback / link-local / carrier-grade-NAT / IPv6-mapped-private ranges; DNS is resolved once and the resolved IP pinned for the fetch (no rebinding). Caffeine cache: 10 000 entries, 1 h TTL positive, 60 s TTL negative. Response headers: `Cross-Origin-Resource-Policy: same-origin`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'`, `Content-Disposition: inline`, `Cache-Control: public, max-age=3600, immutable`. Only `ShareRenderingService` can sign URLs; the HMAC secret never reaches the wire.
**Rationale**: Eliminates viewer-IP deanonymisation (the CSP `img-src 'self'` + proxy is stricter than `img-src https:`). Prevents hotlink tracking pixels. Contains cross-origin request-forgery surface.
**Alternatives considered**: `img-src https:` open allowlist (rejected — open-internet tracking surface).
**Source**: `secure-feature-planner` Round 1 ⚡#2; accepted in architect Round 2; tightened in security Round 2 new ⚡ (headers + negative cache); ADR-SHARE-07.

### Decision: Double-submit CSRF on mutating share-link endpoints

**Decision**: `GET /rest/share-csrf` mints a random token, returned in body *and* set as `__Host-shareCsrf` cookie (`SameSite=Strict`, `HttpOnly=false` so JS can read). Mutating endpoints (`POST /rest/share-links`, `DELETE /rest/share-links/{id}`) require an `X-Share-CSRF` header matching the cookie via `MessageDigest.isEqual` (constant-time), plus exact-Origin check (scheme + host + port). Owned by `secure-tdd-implementer` (reassigned from the CRUD lane in Round 2).
**Rationale**: Glacier has no pre-existing CSRF infrastructure; `SameSite=Lax` on the `wallId` cookie is insufficient for POST/DELETE. Constant-time compare + Origin binding + `__Host-` prefix triangulates defence.
**Alternatives considered**: `SameSite=Strict` on `wallId` alone (rejected — breaks the main wall's copy-link flows); origin-check alone (rejected — bypassable by some legacy browsers).
**Source**: `secure-feature-planner` Round 1 ⚡#1; lane reassignment Round 2; ADR-SHARE-06.

### Decision: Uniform-200 catalog response (closes state-discrimination side-channel)

**Decision**: `GET /rest/share/{id}/catalog` returns `200` with a `{state: "active"|"expired"|"revoked", ...}` discriminator for *any known* shareLinkId; `404` is returned *only* for truly unknown IDs. Full-HTTP-handler timing test (`ShareCatalogEndpointTimingIT`) asserts p95 timing variance ≤ 15 % between unknown-404 and known-any-state-200 paths.
**Rationale**: A status-code difference between "expired" and "killswitch-empty" was itself a side-channel on operator state. Collapsing to a single response shape with a payload discriminator eliminates the oracle while keeping the UX signal.
**Alternatives considered**: Uniform 404 for both (rejected — viewer can't distinguish "your link expired" from "typo in URL"); distinct status codes (rejected — leaks operator state).
**Source**: `secure-feature-planner` Round 1 ⚡#5; accepted in architect Round 2; ADR-SHARE-08.

### Decision: Dedicated origin `share.${glacier.domain}`

**Decision**: Readonly view lives at `share.${glacier.domain}` (e.g. `share.glacier.events`). Traefik host-rule + TLS SAN + `ShareHostRouter` component (enforces that `/share/*` SPA and share-view WebSocket / proxy / catalog endpoints reach only this host; main wall hostname rejects `/share/*`). Cookie jar, localStorage, sessionStorage, service-worker context, and CSP are all physically isolated between the sharer's wall and the viewer's view.
**Rationale**: Browser-enforced isolation strictly dominates engineering-enforced isolation. A single future refactor or Angular version bump cannot weaken origin separation; it can weaken typed-principal checks.
**Alternatives considered**: Same-origin with path isolation (defensible fallback but explicitly labelled "second-best" by security planner; rejected per user's "as hardened as possible" directive).
**Source**: `secure-feature-planner` Round 1 ⚡#6 / Round 2 §6 (strong ACCEPT recommendation); user resolution 2026-04-22 (option A).

### Decision: `__Host-shareViewerId` cookie

**Decision**: Viewer cookie named `__Host-shareViewerId` with `Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=<=TTL`. Insecure-transport fallback drops the `__Host-` prefix and `Secure` flag. The cookie never reaches `/rest/wall-id`, `/rest/messages`, or the main `/websocket` endpoint (those handlers read only `wallId`); handler-level dispatch is enforced by the typed-principal layer.
**Rationale**: `__Host-` prefix prevents the cookie from being set by non-secure origins or narrower paths — a stronger guarantee than `Path=/share` alone.
**Alternatives considered**: `Path=/share` scoping (sufficient on a same-origin design; redundant once a dedicated origin is adopted).
**Source**: `secure-feature-planner` Round 1 ⚡#3; accepted in architect Round 2.

### Decision: Strict CSP on `/share/*` with Trusted Types

**Decision**:
```
Content-Security-Policy: default-src 'none';
  script-src 'self' 'nonce-{NONCE}';
  style-src 'self' 'nonce-{NONCE}';
  font-src 'self';
  img-src 'self' data:;
  connect-src 'self';
  frame-ancestors 'none';
  base-uri 'none';
  form-action 'none';
  object-src 'none';
  manifest-src 'self';
  require-trusted-types-for 'script';
  trusted-types default;
  upgrade-insecure-requests
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Cache-Control: no-store
```
Per-response nonce via `CspNonceFilter`; Angular's `CSP_NONCE` token reads `<meta name="csp-nonce">` injected by the SPA index transformer. `require-trusted-types-for 'script'` plus Angular's built-in `default` policy means any `DomSanitizer.bypassSecurityTrustHtml` fails loudly at runtime — aligned with the ESLint rule `no-share-dangerous-html`.
**Rationale**: Nonce + Trusted Types + `default-src 'none'` removes inline injection and DOM-clobbering vectors; COOP/COEP closes cross-origin script/embed pivots; `img-src 'self' data:` is the counterpart to the image-proxy decision.
**Source**: `secure-feature-planner` Round 1 §5 (CSP spec); Round 2 §5 (final spec with COEP/COOP/HSTS); architect Round 2 accepts verbatim.

### Decision: 7-day TTL, sharer-only revocation, hard caps

**Decision**: `ShareLinkLifetimePolicy.ttl = P7D`. Revocation requires the caller's `wallId` to equal `sharerWallId`; mismatches and not-found return the **same** response shape (anti-enumeration). Caps: `maxActivePerSharer=3`, `maxActivePerIp=10`, global `10_000`, viewer concurrency `maxViewersPerLink=100`.
**Rationale**: Bounds abuse surface (DoS via mass link creation); aligns with user's 7-day requirement; anti-enumeration closes timing/body oracle.
**Source**: User Step-0 (7-day TTL, sharer-revokable); architect Round 2 caps; `secure-feature-planner` Round 1 authorization matrix.

### Decision: Dedicated STOMP endpoint `/share-view-ws` + `ShareViewStompRelay`

**Decision**: New endpoint `/share-view-ws` (distinct from `/websocket`). A server-side `ShareViewStompRelay` observes outbound messages destined for `/topic/hashtags/{sharerWallId}/...` and republishes them (for each active `ShareLink` keyed on that `sharerWallId`) onto `/topic/share/{shareLinkId}/{hashtag}/{creation|modification|deletion}`. Payloads transform to `ReadonlyTootView` at publish time; viewer wire never sees `sharerWallId` or raw `StatusCreatedMessage`. Revocation push uses `/topic/share/{id}/control {type: "revoked"}` — viewer frontend disconnects within 1 s.
**Rationale**: Single server-side fan-out boundary enforces wallId confinement structurally. No subscription-rewrite dependency on `StompCallback`. Topic interceptor (`ShareViewTopicAuthInterceptor`) rejects any SUBSCRIBE whose path does not match the viewer's `boundShareLinkId`.
**Alternatives considered**: Rewrite destinations in `StompCallback` (rejected — couples Bigbone ingress to share state); subscribe viewers directly to sharer topics (rejected — leaks wallId).
**Source**: `ddd-tdd-architect` Round 1 §4; ADR-SHARE-02.

### Decision: Fail-closed HMAC secret at boot (prod)

**Decision**: In `glacier.cookie.secure=true` (prod-like) profile, the backend refuses to start if `glacier.share.imgproxy.hmacSecret` is unset or < 32 bytes. Dev profile (`glacier.dev.autoGenerateSecrets=true`) retains auto-generation with a one-time WARN.
**Rationale**: Soft-fail contradicts "as hardened as possible"; multi-replica deployments silently diverge if secrets auto-generate independently.
**Source**: `secure-feature-planner` Round 2 new ⚡; user resolution 2026-04-22 (option A).

### Decision: Fallback-mode discipline across all five Playwright projects

**Decision**: Share-link behaviour specified and tested in each mode:
- **live**: catalog 200, WS relay, STOMP delivery to viewer.
- **fallback**: catalog 200, WS connect fails, frontend polls `GET /rest/share/{id}/messages?hashtag=&since=` at 5 s. Rate-limited per `ShareViewerPrincipal` + per-IP.
- **killswitch** (`glacier.fallback.enabled=false`): catalog 200 with `initialToots: []`, live WS relay continues, polling returns 404 (mirroring existing `/rest/messages` kill-switch rule).
- **insecure**: works end-to-end; `__Host-` prefix + `Secure` flag dropped.
- Dedicated Playwright spec in each of the 5 projects (chromium / firefox / webkit / killswitch / insecure) asserts mode-specific behaviour without overlap.
**Source**: `glacier-fallback-mode-discipline` skill (mandatory); `ddd-tdd-architect` Round 1 §8; Round 2 test pyramid.

### Decision: Structured-native accessibility semantics (WCAG 2.2 AA + EAA)

**Decision**: Readonly wall is `role="feed"` with `<article>` per toot, skip-links ("Skip to feed", "Skip to next toot"), semantic heading hierarchy, `<time datetime="…">`, `aria-label`/`aria-describedby` for CWs and polls, polite live region for incoming toots, native `<details>` for spoiler/CW content, focus management on dialog open/close, 200 %/400 % reflow at 360×640, 24×24 (prefer 44×44) touch targets, keyboard nav with visible focus ring, `prefers-reduced-motion` + forced-colors support. `bidiStripped: boolean` on `ReadonlyTootView` surfaces silent bidi-override neutralisation with an info-icon tooltip. Phase 3 acceptance gate: **zero critical or serious `@axe-core/playwright` findings**.
**Source**: `ux-ui-designer` Round 1 §4; architect Round 2 accepts DTO additions; Phase 3 auditor has the gate.

### Decision: i18n discipline (German source, EN catalog)

**Decision**: Every new user-visible string carries an explicit `@@id` matching a catalog key in `messages.en.json` / `messages.de.json`. Expiry dates use `Intl.DateTimeFormat`; countdowns use ICU plural forms. Initial catalog sketch (`ux-ui-designer` §5) includes ~15 keys under `share.*`, `connection.share.*`, and `rate.limited.share.*`; `frontend-designer` finalises the complete list during Phase 2.
**Source**: `angular-i18n-localize` skill (mandatory).

### Decision: Test pyramid (non-negotiable per `CLAUDE.md`)

**Unit (Surefire + Karma)**: `ShareLinkTest`, `ShareLinkIdTest`, `ShareLinkServiceTest` (incl. constant-time), `InMemoryShareLinkRepositoryTest`, `DefaultSafeUrlValidatorCorpusTest` (exhaustive XSS corpus), `JsoupTextExtractorCorpusTest` (OWASP + mutation XSS), `ShareRenderingServiceTest`, `ShareViewPrincipalHandlerTest`, `ImageProxyHmacSecretValidatorTest`, `ReadonlyTootComponentSpec` (XSS corpus via DTO inputs), `QrCodeComponentSpec`, `ShareDialogComponentSpec`, `ShareLinkServiceSpec` (frontend), `ReadonlyWallServiceSpec`.

**Integration (Failsafe)**: `ShareLinkControllerIT`, `ShareViewControllerIT`, `ShareViewStompRelayIT` (wallId-leakage string-scan), `PrincipalHandlerIT` (both-cookie coexistence), `FallbackRateLimiterShareIT`, `FallbackRateLimiterKeyingTest`, `KillswitchShareIT`, `ShareLinkExpirationIT`, `CspOnShareRouteIT`, `WallIdLeakageIT`, `ShareCatalogEndpointTimingIT` (p95 ±15 %), `AntiEnumerationIT`, `ShareLinkCsrfIT`, `ImageProxyIT` (CORP/nosniff/CSP/negative-cache/DNS-pinning), `ShareViewRevocationPushIT` (1 s disconnect SLA).

**E2E (Playwright against dockerized Mastodon, per CLAUDE.md)**: `workflows/share-link.spec.ts` (chromium happy path), `share-link-a11y.spec.ts` (chromium, axe-core + zoom + keyboard), `share-link-qr.spec.ts` (chromium, QR decode + second context), `share-link-firefox.spec.ts` (firefox happy path), `share-link-webkit.spec.ts` (webkit happy path), `share-link-killswitch.spec.ts` (killswitch project), `share-link-insecure.spec.ts` (insecure project), `share-link-fallback.spec.ts` (chromium, WS forced off), `share-link-rate-limit.spec.ts` (chromium abuse path).

**Source**: Combined `ddd-tdd-architect` §10 + `secure-feature-planner` test requirements + `ux-ui-designer` §10; `spring-boot-testing-patterns` + `playwright-e2e-patterns` + `playwright-angular-a11y` + `angular-karma-jasmine-testing` skills.

## Resolved Conflicts

### Round 1 conflicts (resolved by architect Round 2)

- **⚡#1 CSRF defence on mutating endpoints** → `__Host-shareCsrf` double-submit + exact-Origin + constant-time compare. (RESOLVED)
- **⚡#2 `img-src https:` is an open-internet allowlist** → server-side image proxy + `img-src 'self' data:`. (RESOLVED)
- **⚡#3 Cookie `Path=/share` insufficient** → `__Host-shareViewerId; Path=/` backed by typed principals. (RESOLVED)
- **⚡#4 `sv_` prefix insufficient** → sealed `GlacierPrincipal` + `PrincipalKey`-keyed cache. (RESOLVED)
- **⚡#5 Killswitch-empty vs expired-link 404 is a side-channel** → uniform 200 `{state}` discriminator. (RESOLVED)
- **UX ⚡ Silent bidi-stripping** → `bidiStripped: boolean` flag on DTO + UI affordance. (RESOLVED)

### Round 2 conflicts (resolved by user 2026-04-22)

#### Dedicated origin for readonly view
**`ddd-tdd-architect`**: "Conditionally accept — escalate infra call to the user."
**`secure-feature-planner`**: "ACCEPT — browser-enforced isolation strictly dominates code-enforced isolation."
**Resolution (2026-04-22)**: User choice **A** — ACCEPT. Readonly view lives on `share.${glacier.domain}`. Traefik + TLS SAN + `ShareHostRouter` added to the `devops-infra-engineer` Phase 2 lane.

#### Image-proxy HMAC secret boot policy
**`ddd-tdd-architect`**: "Generated at boot if not configured, logged once as WARN."
**`secure-feature-planner`**: "Fail-closed in prod — multi-replica deployments diverge; soft-fail contradicts 'as hardened as possible'."
**Resolution (2026-04-22)**: User choice **A** — fail-closed in prod (`glacier.cookie.secure=true`); dev auto-generates with WARN. `ImageProxyHmacSecretValidator` in `secure-tdd-implementer` lane.

#### Image-proxy response hardening + negative-caching
**`ddd-tdd-architect`**: (silent — specified functional controls only.)
**`secure-feature-planner`**: "Add CORP, nosniff, CSP, Content-Disposition, Cache-Control immutable + 60 s negative cache (stampede + COEP compatibility)."
**Resolution (2026-04-22)**: User choice **A** — ACCEPT. All listed response headers; `glacier.share.imgproxy.negativeCacheSeconds=60`.

#### CSRF endpoint lane-ownership
**`ddd-tdd-architect`**: "Thin 30-LoC controller → `tdd-ddd-implementer`."
**`secure-feature-planner`**: "Security-primitive logic (constant-time compare, SecureRandom, Origin binding) → `secure-tdd-implementer`."
**Resolution (2026-04-22)**: User choice **A** — move to `secure-tdd-implementer`. CRUD controllers import the interceptor as a bean.

## Architecture Decision Records

| ADR | Title | Source |
|---|---|---|
| ADR-SHARE-01 | Share-linking is a new bounded context | Architect Round 1 §13 |
| ADR-SHARE-02 | Topic relay, not subscription rewriting | Architect Round 1 §13 |
| ADR-SHARE-03 | Server-side Jsoup extraction, no frontend HTML parsing | Architect Round 1 §13 |
| ADR-SHARE-04 | Separate STOMP endpoint `/share-view-ws` | Architect Round 1 §13 |
| ADR-SHARE-05 | Typed principals (sealed `GlacierPrincipal`) replace the `sv_` prefix | Architect Round 2 (revised from Round 1) |
| ADR-SHARE-06 | Double-submit CSRF token on mutating share-link endpoints | Architect Round 2 §4 |
| ADR-SHARE-07 | Server-side image proxy with HMAC + CORP + negative cache | Architect Round 2 §4 + security Round 2 |
| ADR-SHARE-08 | Uniform-200-with-`state` catalog response | Architect Round 2 §4 |
| ADR-SHARE-09 | Dedicated origin `share.${glacier.domain}` for readonly view | User resolution 2026-04-22 |

## Security Requirements

All 19 requirements `SR-SHARE-01..19` from `secure-feature-planner` Round 1 are **ADDRESSED** per the Round 2 disposition table (Section 2 of `secure-feature-planner` Round 2 output). Notable items:

- `SR-SHARE-02` (sharerWallId never on the wire) enforced by `ReadonlyTootViewContractIT` (reflective DTO scan) + `WallIdLeakageIT` (string-scan of captured STOMP frames).
- `SR-SHARE-06` (anti-enumeration) enforced by uniform body + timing bound in `ShareCatalogEndpointTimingIT`.
- `SR-SHARE-15` (image proxy) with the Round-2 tightenings on CORP/nosniff + negative cache + DNS pinning.
- `SR-SHARE-16` (revocation push) with 1 s disconnect SLA in `ShareViewRevocationPushIT`.
- `SR-SHARE-17` (audit events) — `share.link.created`, `.revoked`, `.accessed`, `.expired_access_attempt`, `.sweep`, `.ratelimit.exceeded`, `.cap.exceeded`, `.csrf.fail`, `.proxy.fetch_blocked`, `.viewer.handshake_rejected`, `.render.oversize` — all hashed via `LogScrubber.hash8`.
- `SR-SHARE-19` (fallback-mode matrix) — dedicated Playwright project per mode.

## Final Phase 2 Lane Partition

### `tdd-ddd-implementer` lane — domain + CRUD

- `share.domain.*`: `ShareLink`, `ShareLinkId`, `ShareViewerId`, `ShareLinkStatus`, `ShareLinkLifetimePolicy`, `ShareLinkCapPolicy`, `ShareLinkRepository` interface.
- `share.infrastructure.InMemoryShareLinkRepository` + `SecureRandomTokenGenerator` + scheduled sweeper.
- `share.application.ShareLinkService` (create, resolve, revoke, listBySharer) with `Clock` injection + cap enforcement + constant-time resolve.
- `share.web.ShareLinkController`: `POST /rest/share-links`, `DELETE /rest/share-links/{id}`, `GET /rest/share-links` (sharer listing). Registers the CSRF interceptor from the security lane.
- Unit tests for all domain + application components.
- `ShareLinkControllerIT` (CRUD happy path + cap + rate limit + anti-enumeration).
- Publishes the sealed `GlacierPrincipal` interface used by `secure-tdd-implementer`.
- Owner of ADR-SHARE-01, -05 (domain portion).

### `secure-tdd-implementer` lane — security, rendering, transport

- `share.application.ShareRenderingService` + `JsoupTextExtractor` + `DefaultSafeUrlValidator` (all with exhaustive XSS corpus unit tests).
- `share.web.ShareViewController`: `GET /rest/share/{id}/catalog` + `GET /rest/share/{id}/messages` (viewer fallback polling).
- `share.web.ShareImgProxyController` + `ShareImageProxyService` + `ShareImageProxyUrlBuilder` (HMAC signing) + `ImageProxyHmacSecretValidator` (fail-closed in prod) + bounded `InputStream` reader.
- `share.web.ShareCsrfGuard` + `CsrfTokenCookieFactory` + `GET /rest/share-csrf` (reassigned from CRUD lane).
- `ShareViewPrincipalHandler` + `ShareViewAuthGuard` (viewer cookie auth) + `ShareViewTopicAuthInterceptor` (STOMP SUBSCRIBE filter) + `/share-view-ws` endpoint registration in `WebSocketConfiguration`.
- `ShareViewStompRelay` + revocation-push plumbing on `/topic/share/{id}/control`.
- `ShareSecurityHeadersFilter` + `CspNonceFilter` + `CspNonceAccessor` (per-response nonce; SPA index transformer injects into `<meta>` tag).
- `ShareHostRouter` (enforces dedicated-origin routing; `/share/*` only reachable on the share host; main wall host rejects `/share/*`).
- Cookie-naming bean (`__Host-shareViewerId` vs insecure-fallback).
- Typed-principal infrastructure: sealed `GlacierPrincipal`, `WallPrincipal`, `ShareViewerPrincipal`, `PrincipalKey`; migrate `MessageCacheImpl` and `FallbackRateLimiter` keying.
- `LogScrubber` extensions: `hash8(ShareLinkId)`, `hash8(ShareViewerId)`.
- Rate-limit axis registrations in `FallbackRateLimiter`: `share.create.perMinutePerWallId`, `share.create.perMinutePerIp`, `share.csrf.perMinutePerIp`, `share.fallback.perMinutePerViewer`, `share.fallback.perMinutePerIp`, `share.imgproxy.perMinutePerIp`.
- Integration tests: `ShareViewControllerIT`, `ShareViewStompRelayIT`, `ImageProxyIT`, `CspOnShareRouteIT`, `WallIdLeakageIT`, `ShareCatalogEndpointTimingIT`, `AntiEnumerationIT`, `ShareLinkCsrfIT`, `ShareViewRevocationPushIT`, `FallbackRateLimiterShareIT`, `FallbackRateLimiterKeyingTest`, `KillswitchShareIT`, `PrincipalHandlerIT`, `ShareLinkExpirationIT`, `ImageProxyHmacSecretValidatorTest`.
- Owner of ADR-SHARE-02, -03, -04, -05 (security portion), -06, -07, -08.

### `devops-infra-engineer` lane — real lane, not a footnote

- Traefik host-rule for `share.${glacier.domain}` (router + service + middleware) in `infrastructure/traefik.yml` + `dynamic.yml`.
- TLS SAN addition: generate new cert or extend existing one (`v3.ext` in `infrastructure/`).
- Env-var matrix updates for docker-compose + killswitch override + insecure override: `GLACIER_SHARE_HOST`, `GLACIER_SHARE_IMGPROXY_HMAC_SECRET`, `GLACIER_SHARE_MAX_ACTIVE_PER_SHARER=3`, `GLACIER_SHARE_MAX_ACTIVE_PER_IP=10`, `GLACIER_SHARE_MAX_VIEWERS_PER_LINK=100`, `GLACIER_SHARE_TTL=PT7D` (or `P7D`), `GLACIER_SHARE_SWEEP_INTERVAL_MS=300000`, `GLACIER_SHARE_IMGPROXY_POSITIVE_CACHE_SECONDS=3600`, `GLACIER_SHARE_IMGPROXY_NEGATIVE_CACHE_SECONDS=60`.
- Production secret handling: ensure `GLACIER_SHARE_IMGPROXY_HMAC_SECRET` is present in every production-like compose/env; backend refuses to boot otherwise.
- `.github/workflows/verify.yml`: cache invalidation if `pom.xml` gains `org.jsoup:jsoup`; Playwright project matrix expands to cover the new share-link specs.
- If Mastodon seed data needs adjusting for the share flow (probably not), follow the re-pack note in `.github/workflows/verify.yml`.

### `frontend-designer` lane — UI, a11y, i18n, e2e

- Lazy-loaded `share` Angular feature module: `/share/:shareId` (`ShareLinkGuard`), `/share/:shareId/expired` routes.
- `ReadonlyWallComponent`, `ReadonlyTootComponent` (interpolation-only, XSS-test-covered), `ShareExpiredComponent`, `MediaRefComponent`, `PollRefComponent`, `CwToggleComponent`.
- Main wall additions: `QrCodeComponent` (pure-JS `qrcode` npm), `ShareDialogComponent` (Material dialog with list-create-revoke flows + Clipboard API + fallback).
- Frontend services: `ShareLinkService` (sharer; HTTP client with CSRF-token handshake), `ReadonlyWallService` (viewer; no localStorage; STOMP on `/share-view-ws`; fallback polling at `/rest/share/{id}/messages`; revocation-frame router).
- `safeUrlPipe` (defence-in-depth frontend URL re-validation).
- i18n: additions to `messages.de.json` (source) and `messages.en.json`; ICU plurals for countdowns; `Intl.DateTimeFormat` for expiry.
- Angular `CSP_NONCE` wiring from `<meta name="csp-nonce">`; Trusted Types adoption.
- ESLint custom rule: `no-share-dangerous-html` (forbids `[innerHTML]`/`bypassSecurityTrust*` inside the `share` module).
- Playwright specs: all 9 share-specific specs across the 5 projects; `@axe-core/playwright` integration.
- Karma unit specs for every new component/service/pipe.
- Owner of i18n catalogs, theming compliance, QR library integration, axe-core wiring.

### Cross-lane sequencing rules

- `tdd-ddd-implementer` lands the sealed `GlacierPrincipal` interface and the `ShareLinkService` interface first (other lanes depend on these).
- `ReadonlyTootView` record lives in `secure-tdd-implementer` lane; `frontend-designer` mirrors as a TypeScript interface — coordinate via code review before Phase 2 Round 2.
- `devops-infra-engineer` Traefik + TLS work must ship in the same PR or behind a feature flag; the backend's `ShareHostRouter` hard-fails if the expected host header isn't present.

## User Approval

**Date**: 2026-04-22
**Approval message (verbatim)**:

Step 0 + conflict resolution:
> "Yeah, do that. But make sure it is as hardened as possible."

Conflict resolution (Round 2):
> "1: A, 2: a, 3:a, 4:a."

Phase 1 Approval Gate:
> "I approve"

## Open Risks (accepted by the user)

- **KILL-01** — Under killswitch, a viewer with a valid `shareLinkId` can infer the operator flipped killswitch because the catalog returns `200` but `initialToots: []`. Accepted: hiding killswitch state from authenticated viewers provides no threat-model value. No mitigation planned.
- **Image-proxy timing observability** — A viewer can indirectly observe which federation instances respond slowly via image-load latency. Accepted: viewer already knows the author's instance from the toot's URL. No mitigation planned.
- **Cross-replica proxy-cache staleness** — Avatars updated upstream between cache-fill and cache-expiry show stale content for up to 1 h. Accepted: cache TTL is short enough that staleness windows are narrow and non-security.
- **Sweeper race at expiry boundary** — A concurrent `resolve` call may observe stale status for microseconds at the expiry boundary. Mitigated: `status(now)` is always recomputed on each `resolve` call; the sweeper is an optimisation, not the source of truth.
- **i18n drift** — German source + EN catalog must stay in sync across every new user-visible string. Mitigated: `angular-i18n-localize` skill enforced at implementation and acceptance gates; Phase 3 auditor checks catalog completeness.
- **HMAC secret rotation** — Rotating `glacier.share.imgproxy.hmacSecret` invalidates any outstanding proxy URLs. Accepted: URL lifetime is bounded by 7-day share-link TTL, so rotation can be paired with releases; documented in ops notes.

## References

- Architect Round 1 plan: `/tmp/glacier-pipeline-share-link/arch_plan.md`
- Security Round 1 plan: `/tmp/glacier-pipeline-share-link/security_plan.md`
- UX Round 1 plan: `/tmp/glacier-pipeline-share-link/ux_plan.md`
- Architect Round 2 review: `/tmp/glacier-pipeline-share-link/arch_review.md`
- Security Round 2 cross-review (summary): `/tmp/glacier-pipeline-share-link/security_final.md`
- Skills: `glacier-fallback-mode-discipline`, `glacier-structured-logging-logback`, `angular-i18n-localize`, `spring-security-hardening`, `spring-input-validation-ssrf`, `angular-a11y-patterns`, `playwright-angular-a11y`, `spring-boot-testing-patterns`, `playwright-e2e-patterns`, `spring-websocket-performance`, `spring-virtual-threads`, `angular-material-theming`, `angular-karma-jasmine-testing`.
- Prior fallback-mode decisions: `docs/decisions/2026-04-21-planning-ws-fallback.md`, `-implementation-ws-fallback.md`, `2026-04-22-acceptance-ws-fallback.md`.
- CLAUDE.md testing-policy, fallback-mode-discipline, i18n, agent-management sections.
