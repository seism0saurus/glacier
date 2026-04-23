---
name: glacier-fallback-mode-discipline
owner: "@seism0saurus"
description: Ensure changes across Glacier preserve correct behavior in all three operational modes (live via WebSocket, fallback via HTTP polling, killswitch disabled fallback) plus the insecure-transport variant. Spans backend + frontend + Playwright project mapping + rate-limiter accounting. TRIGGER when editing code that touches WebSocket/STOMP flow, FallbackController, FallbackRateLimiter, connection-status components, fallback-related test specs, GLACIER_FALLBACK_ENABLED config, cookie-based auth guards, or when the user mentions fallback, killswitch, insecure mode, live vs fallback, mode transition, offline state. SKIP for code that is provably mode-neutral (pure business logic far from the streaming path, static text, etc.).
---

# Glacier Fallback-Mode Discipline

Glacier runs in **three operational modes** plus one transport variant. Every change to the streaming, subscription, cache, rate-limit, or authentication paths must be evaluated in each mode — forgetting one is the single most common structural bug in this codebase.

## The four states

| Mode | WebSocket? | Polling? | Backend flag | Transport | User-visible state (frontend) |
|---|---|---|---|---|---|
| **live** | active | no | default | HTTPS | `connection.websocket.label` / "Live" |
| **fallback** | failed | every 5s | `glacier.fallback.enabled=true` | HTTPS | `connection.fallback.label` / "Fallback Mode" |
| **killswitch** | failed | **blocked** | `glacier.fallback.enabled=false` | HTTPS | `connection.killswitched.label` / "Limited" |
| **insecure** (transport variant) | active | — | default | plain HTTP | `connection.insecure.label` / "Insecure Connection" — fallback **disabled** by design |

Plus a `probing` transient state (`connection.probing.label`, "Connecting…") during reconnect attempts, and `offline` when session expired (`session.expired.banner`).

## Playwright project mapping

This is where mode-discipline bugs most often surface:

| Spec file | Goes in project | Why |
|---|---|---|
| `workflows/fallback.spec.ts`, `workflows/fallback-ux.spec.ts` | `chromium` (default) | Verifies normal WS→fallback transition with default flags |
| `workflows/fallback-killswitch.spec.ts` | `killswitch` **only** | Requires `GLACIER_FALLBACK_ENABLED=false` override (via `docker-compose.override.killswitch.yaml`) |
| `workflows/fallback-insecure.spec.ts` | `insecure` **only** | Requires plain-HTTP backend on :8081 (via `docker-compose.override.insecure.yaml`) — `ignoreHTTPSErrors: true`, different BASE_URL |

Misplacing a spec silently breaks mode coverage:
- `fallback-killswitch.spec.ts` in `chromium` → test runs against `GLACIER_FALLBACK_ENABLED=true`, killswitch behaviour untested.
- `fallback-insecure.spec.ts` in any secure-transport project → test tries to hit HTTPS endpoint, fails on certificate or routing.

When adding a new fallback-mode test, choose the project **first** (based on backend state required), then place the spec in the matching directory. See `playwright-e2e-patterns` skill for project rules.

## Backend invariants across modes

These must hold in every mode; when you modify any of them, test in all three.

### Rate limiting — scopes matter

```properties
glacier.fallback.ratelimit.perMinute=${GLACIER_FALLBACK_RATELIMIT_PER_WALLID:30}
glacier.fallback.ratelimit.perMinutePerIp=${GLACIER_FALLBACK_RATELIMIT_PER_IP:120}
```

- `per_wallid` scope: per-user bucket, used in live + fallback.
- `per_ip` scope: per-source-IP bucket, protects against single-IP floods (essential in insecure mode where no CSRF protection applies).
- `FallbackRateLimiter` evicts stale buckets (`glacier.ratelimit.eviction.intervalMs=300_000`).

Changes to rate-limit logic **must** consider:
- Does the new path hit the `per_wallid` or `per_ip` limiter? (usually both)
- Does the change interact with `GLACIER_FALLBACK_ENABLED=false` (killswitch)? In killswitch mode, fallback polling should be rejected before it hits the rate limiter — the limiter is downstream.

### Cookie authentication — mode-dependent

Glacier has `CookieBasedFallbackAuthGuard` and `PassthroughFallbackAuthGuard` — distinct guard implementations depending on whether fallback auth is enforced.

- `CookieBasedFallbackAuthGuard`: verifies session cookie; used when fallback is reachable AND authentication is required for fallback endpoints.
- `PassthroughFallbackAuthGuard`: lets requests through (used for anonymous/permitted paths).

`glacier.cookie.secure=true` defaults cookies to Secure flag — **critical in insecure mode** because in that mode the frontend + backend run over HTTP, and setting `Secure` would cause browsers to drop the cookie entirely. Verify the cookie behaviour is correct for the transport variant being tested.

### Cache (`MessageCacheImpl`) — shared across modes

The message cache is populated from WebSocket live pushes AND from fallback polling results. Cache behavior must be identical regardless of which path filled it:
- De-duplication by message ID (not by source mode).
- Ordering preserved per hashtag (`PerTagRing`).
- Eviction rules (`glacier.cache.size`) identical.

If you add a field that's only set by one path (e.g., `receivedAt` only set by WS push), callers downstream must tolerate its absence from the other path.

## Frontend invariants across modes

### `connection-status` component renders all five states

- `live`, `fallback`, `killswitch`, `insecure`, `offline` (+ transient `probing`).
- Every state has a matching i18n key in `messages.en.json` (`connection.*.label`, `connection.*.detail`).
- New visual state in the component → add matching i18n keys → add matching Playwright assertion.

### Mode transitions have user-visible impact

- `live → fallback`: snackbar announcement ("fallback mode active"), continues polling.
- `fallback → live`: snackbar announcement ("back online").
- `live/fallback → killswitch`: banner + CTA to reload.
- Session expiry (`session.expired.banner`): cross-cuts all modes.

Transitions are tested in `fallback-ux.spec.ts`. When modifying transition logic, run both `chromium` (default flow) and `killswitch` projects.

### Rate-limit snackbar / popover — user-facing signal

`rate.limited.snackbar` and `rate.limited.retry.popover` must fire when the backend returns 429. Present in both live and fallback modes — tests should exercise both.

## Checklist when modifying streaming / subscription / auth code

Before considering the change complete, answer each:

1. **Does the change affect behaviour in `live` mode?** If yes, test in `chromium`.
2. **Does it affect `fallback` mode?** If yes, test in `chromium` (with WS forced off in test, or through a fallback-triggering scenario).
3. **Does it behave correctly when `GLACIER_FALLBACK_ENABLED=false`?** If yes, add/modify a `killswitch`-project test.
4. **Is the change transport-neutral?** If it involves cookies, verify that `insecure` mode (HTTP) still works correctly (or is intentionally blocked).
5. **Does it touch a rate-limit scope?** If yes, consider both `per_wallid` and `per_ip`.
6. **Does it add a new user-visible mode-dependent state?** If yes, add matching `connection.*.label` and `connection.*.detail` i18n keys.
7. **Does it touch `FallbackControllerAdvice`?** If yes, verify the `errorCode` aligns with an i18n key (see `spring-error-handling-problem-details` skill).

## Why this skill exists

Every other skill in the `.claude/skills/` set is **generic knowledge applied to Glacier**. This one is **Glacier-specific structural knowledge that exists nowhere else**. Without it, Claude reliably:

- Writes a feature that works in `live` mode, forgets to update the fallback path → UX bug only visible offline-ish.
- Places a new fallback-scenario spec in `chromium` instead of `killswitch` → test runs but doesn't verify the scenario.
- Adds cookie logic that silently breaks under `insecure` mode (because `Secure` cookies are dropped on HTTP).
- Adds a rate-limit path without the `per_ip` scope → insecure mode unprotected.
- Adds a new UI state without an i18n key → renders German default even in English catalog.

Each of these has happened before (evidence: the current WIP set in `git status` includes fixes for exactly these kinds of gaps — `CorsConfigurationIT`, `FallbackSecurityIT`, `CookieEmissionIT`, `ActuatorExposureIT`).

## What Claude gets wrong without this skill

- Only verifies the "happy path" (live mode) → bug appears in fallback or killswitch.
- Places fallback-killswitch tests in default project → silently untested.
- Forgets the insecure-transport variant → `glacier.cookie.secure=true` default drops cookies on HTTP.
- Uses only `per_wallid` rate limiting on new endpoints → `per_ip` gap.
- Adds a new `connection-status` state without i18n coverage.
- Changes `MessageCache` to depend on a field set only by WS push → fallback path breaks.

## References
- Backend config: `src/main/resources/application.properties` (`glacier.fallback.*`, `glacier.cookie.secure`, `glacier.cache.*`, `glacier.ratelimit.*`)
- Rate limiter: `src/main/java/de/seism0saurus/glacier/webservice/cache/FallbackRateLimiter.java`
- Auth guards: `CookieBasedFallbackAuthGuard.java`, `PassthroughFallbackAuthGuard.java`, `FallbackAuthGuard.java`
- Fallback controller: `FallbackController.java`, `FallbackControllerAdvice.java`
- Cache: `MessageCacheImpl.java`, `PerTagRing.java`
- Playwright config: `frontend/playwright.config.ts` (project definitions)
- Compose overrides: `infrastructure/docker-compose.override.killswitch.yaml`, `docker-compose.override.insecure.yaml`
- Frontend status: `frontend/src/app/connection-status/connection-status.component.*`
- i18n keys: `frontend/src/assets/i18n/messages.en.json` (`connection.*`, `rate.limited.*`, `session.expired.*`, `gap.snackbar.*`)
- Relevant e2e specs: `frontend/e2e/workflows/fallback*.spec.ts`
