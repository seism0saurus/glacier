# Glacier — Developer Guide

This guide is for people who want to **contribute to Glacier** or run it locally for development.
If you just want to use the demo, see the [main README](../README.md).
If you want to deploy your own instance, see the [admin guide](ADMIN.md).

## Contents

- [Architecture overview](#architecture-overview)
- [Coding conventions](#coding-conventions)
  - [Operational modes — mandatory discipline](#operational-modes--mandatory-discipline)
  - [Log hygiene — mandatory discipline](#log-hygiene--mandatory-discipline)
- [Prerequisites](#prerequisites)
- [Build from source](#build-from-source)
- [Run locally](#run-locally)
- [Testing](#testing)
  - [Unit tests](#unit-tests)
  - [Integration tests](#integration-tests)
  - [End-to-end tests](#end-to-end-tests)
- [Project structure](#project-structure)
- [Decision records](#decision-records)

## Architecture overview

Glacier is a **Spring Boot 3.4 + Angular 19** application:

- The backend subscribes to Mastodon hashtags via the [Bigbone](https://github.com/PattaFeuFeu/bigbone) streaming API and fans matching toots to browsers over **STOMP/WebSocket**.
- The frontend is an Angular SPA served from the Spring Boot jar's static resources. Toots are rendered as embedded iframes so that each Fediverse server's own styling is preserved.
- Identity is cookie-based (no login): each browser receives a UUID `wallId` cookie that acts as the WebSocket principal.

For detailed design decisions see [`docs/decisions/`](decisions/).

## Coding conventions

### Operational modes — mandatory discipline

Every code change must be correct in **all three operational modes**. This is enforced by the `glacier-fallback-mode-discipline` rule and verified by the Playwright test suite.

| Mode | How to trigger | What must work |
|---|---|---|
| **Live** | Default — Mastodon stream connected | Real-time WebSocket streaming to browsers |
| **Fallback** | Stream drops; `GLACIER_FALLBACK_ENABLED=true` | Frontend polls `/rest/messages`; in-memory cache serves recent toots |
| **Killswitch** | `GLACIER_FALLBACK_ENABLED=false` | Polling endpoint returns 404; streaming only |

Before changing anything in `WebSocketConfiguration`, `SubscriptionManagerImpl`, `FallbackController`, `FallbackRateLimiter`, or the Angular auth guards, ask: *does this break any of the three modes?* The Playwright projects `killswitch` and `insecure` exist precisely to catch regressions in non-default modes.

### Log hygiene — mandatory discipline

Glacier logs in structured JSON via Logback. The following values must **never** appear verbatim in any log output (enforced by `LoggingSmokeTest` and `LogScrubber`):

| Sensitive value | Safe alternative |
|---|---|
| Raw `wallId` (UUID) | `LogScrubber.hash8(wallId)` — 8-char SHA-256 prefix |
| Client IP address | `LogScrubber.maskIp(ip)` — last octet replaced with `.xxx` |
| Hashtag text | `LogScrubber.hashtagLen(hashtag)` — length only |
| Cookie / access token | Never log; drop at the Logback filter layer |
| Mastodon event name (from wire) | `LogScrubber.safeEventName(event)` — allowlist guard against CWE-117 log injection |

Rate-limit and authentication events must go through the dedicated `AUDIT` logger:
```java
private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
AUDIT.info("ws.handshake.rate_limited ip-hash={}", LogScrubber.maskIp(ip));
```

These rules are documented in full in the `glacier-structured-logging-logback` skill and in the D-13/SR-8 decision records.

## Prerequisites

- **Java 23 JDK** — [Temurin](https://adoptium.net/de/temurin/releases/) recommended. Set `JAVA_HOME` before running Maven.
- **Node.js / npm** — required by the `frontend-maven-plugin` for the Angular build (downloaded automatically on first build if not present).
- **Docker or a compatible container runtime** — required for the end-to-end test suite (runs against a containerised Mastodon instance).

## Build from source

```bash
git clone git@github.com:seism0saurus/glacier.git
cd glacier
./mvnw clean package
```

`frontend-maven-plugin` runs `npm install` + `ng build` automatically. The resulting `target/glacier-0.0.9.jar` contains both backend and frontend.

### Useful Maven profiles

| Command | What it does |
|---------|-------------|
| `./mvnw clean package` | Full build + unit tests |
| `./mvnw verify` | Full build + unit tests + integration tests + Jacoco coverage check |
| `./mvnw verify -P RunE2ETest` | All of the above + end-to-end Playwright tests |
| `./mvnw package -P SkipUnitTest` | Build without running tests |

## Run locally

Start the Angular dev server and the Spring Boot backend separately so that hot-reload works on the frontend:

```bash
# Terminal 1 — Angular dev server on :4200
cd frontend && npm start

# Terminal 2 — Spring Boot backend on :8080
JAVA_HOME=/path/to/jdk23 \
ACCESS_KEY=my-mastodon-api-key \
HANDLE=glacier@mastodon.social \
INSTANCE=mastodon.social \
MY_DOMAIN=localhost:8080 \
./mvnw spring-boot:run
```

The Angular dev server proxies `/rest/*` and WebSocket traffic to `:8080` via the CORS registration in `GlacierApplication`.

## Testing

### Unit tests

Unit tests are in `src/test/java/**/*Test.java` and run via Surefire:

```bash
./mvnw test

# Run a single class:
./mvnw -Dtest=SubscriptionManagerImplTest test

# Run a single method:
./mvnw -Dtest='StompCallbackTest#isLoadable_*' test
```

Frontend unit tests use Karma/Jasmine:

```bash
cd frontend && npx ng test --watch=false

# Run a single spec:
cd frontend && npx ng test --include='**/wall.component.spec.ts' --watch=false
```

Jacoco enforces **instruction ≥ 45 %** and **branch ≥ 35 %** bundle-wide. The `verify` goal checks this; never lower the thresholds — add the missing tests instead.

### Integration tests

Integration tests are in `src/test/java/**/*IT.java` and run via Failsafe:

```bash
./mvnw verify -DskipUnitTests

# Run a single IT:
./mvnw -Dit.test=MastodonConfigurationIT verify -DskipUnitTests
```

### End-to-end tests

E2E tests run against a **real containerised Mastodon instance** using Playwright. They actually post toots into the container and wait for them to appear on the wall — do not replace this with a mock.

**One-shot (CI-style):**
```bash
cd infrastructure
tar -xf infrastructure-content.tar.gz -C ./
docker compose -f docker-compose.yaml up --build --abort-on-container-exit playwright --exit-code-from playwright
```

**Debug mode** (backend in IDE, Playwright on host):
```bash
# Start only Mastodon + dependencies:
docker compose -f docker-compose.only-mastodon.yaml up -d

# Run backend from IDE with:
#   INSTANCE=proxy  HANDLE=@glacier_e2e_test@proxy
#   ACCESS_KEY=<token from infrastructure-content>  MY_DOMAIN=glacier:8080

# Run Playwright:
cd frontend
MASTODON_USER_API_URL=https://proxy \
MASTODON_USER_ACCESS_TOKEN=<token> \
GLACIER_HANDLE=@glacier_e2e_test@proxy \
BASE_URL=http://glacier:8080 \
npx playwright test
```

See [`infrastructure/README.md`](../infrastructure/README.md) for the full fixture setup and seed-data instructions.

Playwright has five test projects (not interchangeable — place specs in the project matching the required backend state):

| Project | Backend mode |
|---------|-------------|
| `chromium` / `firefox` / `webkit` | Live mode |
| `killswitch` | `glacier.fallback.enabled=false` |
| `insecure` | HTTP (no TLS) |

## Project structure

```
glacier/
├── src/main/java/de/seism0saurus/glacier/
│   ├── mastodon/          # Bigbone streaming, StompCallback, SubscriptionManager
│   ├── share/             # Share-link feature (application, domain, web, infra)
│   ├── util/              # LogScrubber and other utilities
│   └── webservice/        # Spring MVC controllers, WebSocket config, security filters
├── src/test/java/de/seism0saurus/glacier/
│   ├── security/          # OWASP-coverage security tests (UT + IT)
│   └── ...                # Unit and integration tests mirroring main
├── frontend/
│   ├── src/               # Angular application
│   └── e2e/               # Playwright end-to-end tests
├── infrastructure/        # Docker Compose stacks and Mastodon fixture
└── docs/
    ├── decisions/         # Architecture and security decision records
    ├── ADMIN.md           # Operator guide
    └── DEVELOPER.md       # This file
```

## Accessibility implementation

The main wall is **not** accessible to screen readers because each toot is rendered as a third-party iframe. This is a known, accepted limitation.

The share view (`frontend/src/app/share/`) is the accessible alternative and must stay that way. When modifying share-view components, preserve:

| Feature | Where | Why |
|---|---|---|
| `role="feed"` + `aria-busy` on the toot list | `readonly-wall.component.ts` | Signals a dynamic feed to assistive technologies |
| `LiveAnnouncer` call on new toots | `readonly-wall.component.ts` | Announces arrivals politely without interrupting the reader |
| Skip links to feed and status banner | share route template | Keyboard navigation |
| Native HTML rendering (no iframes) | `readonly-toot.component.*` | Allows screen readers to read toot text directly |

The Angular CDK `a11y` module (`LiveAnnouncer`, `AriaDescriber`) is already imported — use it rather than manual `aria-live` attributes.

## Decision records

All architecture decisions, security requirements, and feature scopes are documented in [`docs/decisions/`](decisions/). Read the relevant decision records before making changes to core subsystems:

- WebSocket / STOMP configuration → `*-ws-fallback*`
- Share link feature → `*-share-link-qr*`
- Log hygiene (D-13/SR-8) → `*-f6-log-scrubbing*`
- OWASP security coverage → `*-owasp-matrix-completion*`
