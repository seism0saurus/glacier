# Decision Record: Frontend Architecture Enforcement with Sheriff (DDD + layers)

Date: 2026-06-22
Phase: Tooling / Architecture
Status: Adopted — `npm run arch` (sheriff verify) green and gated in CI

## Context

The Java backend enforces architecture with **ArchUnit** (e.g. the Sec-24 IP-classifier gate).
The Angular frontend had no equivalent automated check that its module structure stays consistent
with the intended Domain-Driven Design boundaries and layer direction. This record adds one.

## Tool choice — Sheriff (`@softarc/sheriff-core`)

Candidates evaluated against the `dependency-vetting` skill:

| Tool | Verdict |
|---|---|
| **Sheriff** (`@softarc/sheriff-core`) | **Chosen.** Angular-DDD-native (softarc-consulting / ANGULARarchitects), MIT, **zero runtime deps**, multi-maintainer, actively maintained (v0.19.6, Sep 2025). |
| ts-arch (`tsarch`) | Viable (MaibornWolff, MIT, mature) but generic TS and would need a separate Node test runner — it can't run in the project's Karma (browser) runner. |
| ArchUnitTS (`archunit`) | Rejected on vetting — single-maintainer / ~200 stars fails the "more than a single-person bus factor" criterion. |

### Vetting facts (dependency-vetting)
- `@softarc/sheriff-core@^0.19.6` — devDependency. Latest version published **2025-09-22** (well past the 72 h cooldown).
- License **MIT**; maintainers Rainer Hahnekamp, Manfred Steyer, Auke van Oost (softarc-consulting).
- **Zero runtime dependencies** (TypeScript peer only) → minimal supply-chain surface.
- No known unpatched advisories.

### Why the CLI, not the ESLint plugin
The user's first preference was the `@softarc/eslint-plugin-sheriff` integration. That plugin
peer-requires `eslint@^8 || ^9`, but Glacier is already on **ESLint 10** — the plugin does not yet
support it, and forcing an unsupported peer would violate the vetting rules. So enforcement uses the
**`sheriff verify` CLI** (`@softarc/sheriff-core`, no ESLint peer) instead. The CLI runs the same
dependency-rule + encapsulation engine and is arguably better for CI — a single authoritative check
with an exit code. Revisit the ESLint-plugin integration once it supports ESLint 10 (for inline
editor feedback).

## The rules (`frontend/sheriff.config.ts`, barrel-less mode)

Each module folder carries one `type:` (layer) tag and one `domain:` (bounded context) tag.

**Bounded-context isolation (DDD strategic design):** `wall → share → shared`.
- `domain:shared` (util, icons, framework-free models, qr-code widget) → `shared` only.
- `domain:share` (the read-only share-view feature + the sharer-side share-dialog) → `share`, `shared`.
- `domain:wall` (the hashtag wall host + its root-level core services) → `wall`, `share`, `shared`, `root`.
- The share context can **never** reach back into the wall host (`share ⇏ wall`) — verified already true.

**Layer direction (tactical / clean architecture):** `ui → data → domain → util`.
- `type:domain` and `type:util` are kept **strictly pure** (no `root`): a model or util can never pull
  in a service or the app shell.
- `type:ui` / `type:data` may consume the loose root-level services
  (`subscription*`, `rx-stomp`, `animation`), which live directly under `src/app` and are classified
  as the composition `root`.

## Verification
- `npm run arch` → `sheriff verify` (entryFile `src/main.ts`) — **No issues found**.
- Negative-tested: injecting a forbidden `type:domain → type:data` import is caught (exit 1); clean tree exits 0.
- Gated in CI: `.github/workflows/verify.yml` runs `npm run arch` alongside `npm run lint` (after the
  Maven compile installs `node_modules`).

No runtime code changed, so the unit/integration/e2e testing policy does not apply; the architecture
gate itself is the test, and its catch behaviour was demonstrated above.

## Follow-ups
- Adopt `@softarc/eslint-plugin-sheriff` for in-editor feedback once it supports ESLint 10.
- Consider extracting the loose root-level wall services (`subscription*`, `rx-stomp`, `animation`)
  into a tagged `core`/`data` module so the `root` escape hatch in the layer rules can be removed.
