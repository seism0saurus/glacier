# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project summary

Glacier is a Spring Boot + Angular "social wall" for the Fediverse: it subscribes to Mastodon hashtags through the Bigbone streaming API and fans matching toots out to browsers over STOMP/WebSocket, which render them as embedded iframes. The backend jar also serves the built Angular SPA from its static resources.

- Backend: Spring Boot 3.4 on Java 23, Kotlin stdlib pulled in for Bigbone.
- Frontend: Angular 19 + Angular Material, STOMP via `@stomp/rx-stomp`, Karma/Jasmine unit tests, Playwright e2e.
- Mastodon client: `social.bigbone:bigbone:2.0.0-SNAPSHOT` (requires the Sonatype snapshots repo declared in `pom.xml`).

## Testing policy (non-negotiable)

Every code change — feature, bug fix, refactor, config tweak that affects behavior — **must ship with tests that fail before the change and pass after**. A PR without matching tests is incomplete; ask before skipping. The repository is kept at a full test pyramid, and new code is expected to preserve that shape:

1. **Unit tests** (`src/test/java/**/*Test.java`, Surefire; `frontend/src/**/*.spec.ts`, Karma/Jasmine)
   - Cover every non-trivial branch of the touched class/component in isolation. Use Mockito / Jasmine spies for collaborators; do not reach over the wall into Spring context unless the test is really about wiring.
   - Jacoco enforces instruction ≥ 45 % / branch ≥ 35 % bundle-wide (see `pom.xml` `check-coverage` rule). Do not bypass by lowering the threshold — add the missing tests instead.
2. **Integration tests** (`src/test/java/**/*IT.java`, Failsafe; e.g. `MastodonConfigurationIT`)
   - Required whenever a change crosses a boundary: Spring context wiring, WebSocket handshake, `RestTemplate` calls out, Bigbone `MastodonClient` construction, STOMP message mapping. Use `wiremock-spring-boot` (already on the classpath) for HTTP fakes so the assertions cover real serialization, headers, and status-code handling — not just mocked method returns.
3. **End-to-end tests** (`frontend/e2e/**/*.spec.ts`, Playwright)
   - Required for any user-visible behavior change (new hashtag/subscription flow, toot rendering, GDPR/legal pages, UI regressions) and for any change to the fan-out chain `Bigbone → StompCallback → SimpMessagingTemplate → RxStomp`.
   - E2E **must run against a complete Mastodon instance**, not a mock. Use the dockerized stack from `infrastructure/docker-compose.yaml` (web + streaming + sidekiq + postgres + redis + traefik), seeded from `infrastructure-content.tar.gz`. See `infrastructure/README.md` for the two supported flows:
     - One-shot: `cd infrastructure && tar -xf infrastructure-content.tar.gz -C ./ && docker compose -f docker-compose.yaml up --build --abort-on-container-exit playwright --exit-code-from playwright`.
     - Debug: `docker compose -f docker-compose.only-mastodon.yaml up -d` + run the backend in the IDE with the env vars listed in `infrastructure/README.md`, then `npx playwright test` with `MASTODON_USER_API_URL=https://proxy`, `MASTODON_USER_ACCESS_TOKEN=...`, `GLACIER_HANDLE=@glacier_e2e_test@proxy`, `BASE_URL=http://glacier:8080`.
   - Playwright actually posts toots into the containerized Mastodon via `frontend/e2e/helper/mastodon-client.ts` and waits for them to surface on the wall. Do not replace that with a shortcut that injects messages directly into the Spring app — that path bypasses the federation/streaming behavior the test is meant to guard.

Seed-data changes: if you need to edit the Mastodon snapshot, re-pack it per the note in `.github/workflows/verify.yml`:
`sudo tar -czf infrastructure-content.tar.gz mastodon mastodon.env postgres redis proxy.* v3.ext dynamic.yml traefik.yml`.

Before reporting a change as done, run at minimum `./mvnw verify` locally (unit + integration + Jacoco check). Run the Playwright suite for UI/streaming-path changes. CI (`.github/workflows/verify.yml`) will run all three layers on push; do not rely on CI as your first check.

## Claude Code skill usage

This project ships domain-specific Claude Code skills under `.claude/skills/`. They trigger automatically on description match; the mapping below tells you which are authoritative for which kind of change.

**Project-specific skills (apply only in this codebase):**

- `glacier-fallback-mode-discipline` — **mandatory** consult whenever a change touches streaming (`WebSocketConfiguration`, `SubscriptionManagerImpl`), the fallback path (`FallbackController`, `FallbackRateLimiter`, `*AuthGuard`), cache (`MessageCacheImpl`, `PerTagRing`), rate limiting, or any Playwright spec under `frontend/e2e/workflows/fallback-*`. Glacier has three operational modes (live / fallback / killswitch) plus the insecure transport variant — each change must preserve correctness in all of them.
- `glacier-structured-logging-logback` — **mandatory** consult for any new logger call, MDC usage, or change to `logback.xml`. Enforces the D-13 / SR-8 sensitive-data rules: `cookie`, `setCookie`, `authorization`, wallId, and access tokens must never reach the JSON encoder; route auth-failure and rate-limit events through the dedicated `AUDIT` logger; use `LogScrubber` helpers.
- `angular-i18n-localize` — **mandatory** consult for any user-visible string change. Glacier uses **German source** with runtime-loaded `messages.<locale>.json` catalogs (not the AOT `--localize` pipeline). Every string needs an explicit `@@id` that matches a catalog key.
- `spring-boot-testing-patterns` — consult when adding or modifying backend tests. `*Test.java` = Surefire (unit), `*IT.java` = Failsafe (integration), MockWebServer over Mockito for bigbone HTTP-level mocking, Jacoco thresholds are enforced.
- `playwright-e2e-patterns` — consult when modifying e2e specs. The five projects (chromium / firefox / webkit / killswitch / insecure) are not interchangeable — place specs in the project that matches the backend state they require.

**Portable skills (apply in this project and other Spring/Angular codebases):**

Performance: `spring-virtual-threads` · `spring-http-client-resilience` · `spring-websocket-performance` · `spring-observability-micrometer`
Security: `spring-security-hardening` · `spring-input-validation-ssrf` · `spring-error-handling-problem-details`
Supply chain: `dependency-vetting` (proven/maintained dependency selection, version pinning, OWASP A06 — consult before adding/upgrading any Maven/npm/Action/Docker dependency)
Frontend: `angular-material-theming` · `angular-a11y-patterns` · `angular-reactive-forms-ux` · `angular-karma-jasmine-testing` · `playwright-angular-a11y`

Each agent under `.claude/agents/` declares a "Preferred Claude Code Skills" section listing which of these apply to its role. That agent-file section is the **authoritative** per-agent mapping, and the `/feature` command injects skill names into subagent prompts per Phase based on those sections.

**Rule of thumb for AI agents**: if a change touches a file or concern matching a skill's TRIGGER, read that skill before proposing the change — the skills document the conventions this codebase actively enforces, and bypassing them usually produces review findings.

## Agent management policy

Glacier ships 11 specialist agents under `.claude/agents/` (project-local), orchestrated by the `/feature` slash command (`.claude/commands/feature.md`) — there is intentionally no separate `feature-pipeline` agent. Identical filenames for some of these agents may also exist under `~/.claude/agents/` (user-global). **The two sets are deliberately different, not out-of-sync copies** — treat divergence as design, not drift:

| Location | Role | Characteristics |
|----------|------|-----------------|
| `.claude/agents/` (this repo) | Glacier-specific forks | `owner: "@seism0saurus"`, `memory: project`, examples use Glacier concepts (SubscriptionManager, hashtag subscriptions, fallback mode), reference Glacier skills like `glacier-fallback-mode-discipline` |
| `~/.claude/agents/` (user-global) | Generic cross-project templates | No `owner`, `memory: user`, generic examples (e.g., homelab, Kubernetes), useful starting point when bootstrapping a new project's agents |

Claude Code loads project-local agents with precedence, so for any work inside this repository the `.claude/agents/` versions are authoritative.

**Editing rules:**

- Always edit `.claude/agents/<name>.md` when refining an agent for Glacier work. Do **not** try to keep the user-global copy in sync — they exist for different reasons.
- Do **not** copy user-global agent changes into `.claude/agents/` wholesale; they will overwrite Glacier-specific examples, skills, and ownership metadata. Cherry-pick only the parts that genuinely apply.
- When bootstrapping a *new* agent for Glacier, you may start from the user-global template and then adapt: change `memory` to `project`, add `owner: "@seism0saurus"`, rewrite examples in Glacier terms, and add a `## Preferred Claude Code Skills` section pointing at applicable `.claude/skills/` playbooks.
- Persistent agent memory lives at `.claude/agent-memory/<agent>/` (gitignored — Glacier-scoped memory stays on the developer's machine).
- When adding a new agent, also update the `/feature` command (`.claude/commands/feature.md`) — the command is the sole orchestrator — to reference the agent in the Specialist Agents table and (if applicable) the Step 0 assessment questions.

## Build, test, run

**Build environment (per developer):** `./mvnw` picks up the JDK from `JAVA_HOME`; ensure it points to a local Java 23 installation before running any Maven command. For Claude Code agents, configure this in `.claude/settings.local.json` (gitignored, never in `settings.json`) so personal JDK paths do not leak into the repo.

The Maven build drives the frontend: `frontend-maven-plugin` runs `npm install` + `ng build` and then `exec-maven-plugin` copies `frontend/dist/glacier-frontend/browser/*` into `src/main/resources/static/`. A single Maven invocation produces a self-contained jar.

```bash
./mvnw clean package          # full build incl. Angular bundle + unit tests (backend & Angular)
./mvnw -P SkipUnitTest package # skip unit tests
./mvnw verify                 # also runs integration tests (maven-failsafe) and, if enabled, Playwright e2e
```

Test profiles (see `pom.xml`):

- Default (`manual`) — runs unit + integration tests, skips Playwright e2e.
- `-P RunE2ETest` — installs Playwright browsers and runs the full e2e suite.
- `-P RunE2ETestWithoutDeps` — runs e2e assuming browsers are already installed.
- `-P SkipUnitTest` / `-P SkipIntegrationTest` — toggles driven through `skipUnitTests` / `skipIntegrationTests` properties.
- `-P WithPlaywrightDeps` — passes `--with-deps` to `playwright install`.

Running a single test:

```bash
./mvnw -Dtest=SubscriptionManagerImplTest test                    # one JUnit class (Surefire)
./mvnw -Dtest='SubscriptionManagerImplTest#subscribeToHashtag' test
./mvnw -Dit.test=MastodonConfigurationIT verify -DskipUnitTests   # one Failsafe IT
(cd frontend && npx ng test --include='**/wall.component.spec.ts' --watch=false) # one Karma spec
(cd frontend && npx playwright test e2e/workflows/toots.spec.ts)  # one Playwright spec
```

Jacoco enforces coverage thresholds on `verify` (instruction ≥ 45%, branch ≥ 35%, bundle-wide). New code that drops these ratios will fail the build.

Local backend run (Angular dev server on a separate port talks to it via the `/rest/*` CORS mapping in `GlacierApplication`):

```bash
cd frontend && npm start                   # Angular dev server on :4200
ACCESS_KEY=... HANDLE=bot@instance INSTANCE=instance MY_DOMAIN=localhost:8080 \
  java -jar target/glacier-0.0.8.jar       # backend on :8080
```

See `README.md` for the complete list of `MY_*` operator/GDPR env vars and `infrastructure/README.md` for the dockerized Mastodon fixture used by the e2e suite.

## Architecture

### Build-time coupling of frontend and backend

The Angular bundle is not a separate artifact — it is embedded in the Spring Boot jar. Because of this, changing frontend code without rebuilding the jar will not be reflected in a packaged run; always use `./mvnw package` (or run the dev server at `:4200`). The CORS registration in `GlacierApplication#corsConfigurer` is what allows the dev-mode `:4200` Angular to talk to the `:8080` backend.

### Cookie-based identity ("wallId")

Glacier has no user login. Identity is a UUID stored in the `wallId` cookie:

- `InformationController#readCookie` (`GET /rest/wall-id`) issues the UUID on first visit (30-day cookie).
- `PrincipalHandler` (registered in `WebSocketConfiguration`) runs during the STOMP handshake, reads the cookie, and uses the value as `Principal.getName()`.
- Every downstream authorization decision — which hashtag subscriptions belong to whom, which topic path a toot is published to, which `/user/...` queue a client receives — keys on this principal.

Topic namespace:

- Client → server (application prefix `/glacier`): `/glacier/subscription`, `/glacier/termination` → handled by `SubscriptionController`.
- Server → one client (`@SendToUser`): `/user/topic/subscriptions`, `/user/topic/terminations` (ack messages).
- Server → wall fan-out: `/topic/hashtags/{wallId}/{hashtag}/{creation|modification|deletion}` — published by `StompCallback`.

When editing WebSocket plumbing, keep these three destination shapes in sync across `SubscriptionController`, `StompCallback`, and `SubscriptionService` in the frontend.

### Subscription lifecycle

`SubscriptionManagerImpl` holds a `Map<principal, Map<hashtag, Future<?>>>`. Each `(principal, hashtag)` pair owns a virtual thread submitted to `Executors.newVirtualThreadPerTaskExecutor()`:

1. `client.streaming().hashtag(tag, false, StompCallback)` opens a Bigbone `Closeable`.
2. The virtual thread then loops on `Thread.sleep(60_000L)` *deliberately* — per the inline comment, any other blocking primitive (`wait()`, etc.) causes Bigbone to close the stream immediately. Do not "clean this up" without testing the stream stays open.
3. `terminateSubscription` cancels the `Future`; the `InterruptedException` path calls `subscription.close()` and self-interrupts.

Reconnect handling lives in `SubscriptionListener`: on STOMP `SessionDisconnectEvent` it schedules a virtual-thread timer (`glacier.timeouts.client_reconnect`, default 5 min) that tears down all subscriptions for that principal if a `SessionConnectedEvent` with the same principal does not arrive first.

### StompCallback and iframe safety

`StompCallback.isLoadable(...)` is the gatekeeper for which toots make it to the wall. Before publishing, it issues a `HEAD` to `<tootUrl>/embed` and inspects `Content-Security-Policy: frame-ancestors` and `X-Frame-Options`. A toot is dropped when the remote instance's headers forbid embedding on `glacier.domain`. Editing this logic has user-visible consequences (toots silently disappearing) — cover changes with tests in `StompCallbackTest`.

The callback also enforces an **opt-in requirement**: the toot's mentions must include the bot's short handle (`mastodon.handle` stripped of the server part). Toots matching the hashtag but not mentioning the bot are ignored. This is why the README tells end users to mention `@glacier@glacier.events`.

### Bigbone event model

Events come in two shapes: strongly typed `MastodonApiEvent.StreamEvent` (`StatusCreated`/`StatusEdited`/`StatusDeleted`) and `GenericMessage` (raw JSON). The callback handles both because different streaming paths emit different shapes. When extending event handling, handle both branches (`onEvent` switch and `processGenericEvent`).

### Frontend state and persistence

`SubscriptionService` is the single source of truth on the frontend. It persists `hashtags` and a bounded 20-item `MessageQueue` into `localStorage`, so reloads restore the wall. `RxStompService` auto-reconnects; topic (re)subscription is driven entirely by the `subscriptions` ack message from the server, not by the client. If you add a new server-side event type, you must both publish it on the `/topic/hashtags/...` tree *and* add a `subscribeToX` in `SubscriptionService` so existing walls pick it up.

## Conventions

- Java package root: `de.seism0saurus.glacier`; tests mirror main. `*Test` = unit (Surefire), `*IT` = integration (Failsafe).
- Lombok is enabled via annotation processor in `pom.xml`; `lombok.config` lives at repo root. `@Builder` is used widely for the messaging DTOs under `webservice.messaging.messages`.
- Logback config at `src/main/resources/logback.xml` emits JSON via `logback-jackson`.
- `push_version.sh <new-version> <new-branch>` bumps the version in `pom.xml`, `frontend/package.json`, and `.github/dependabot.yaml` together. Any version change must touch all three consistently (the jar filename in README and the `copy-and-rename-jar` goal both derive from it).
- When writing a new ADR, add a row to `docs/decisions/README.md` in the acceptance commit.
