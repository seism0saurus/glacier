---
name: dependency-vetting
owner: "@seism0saurus"
description: Decide whether a new or upgraded third-party dependency is allowed into Glacier — Maven (pom.xml), npm (frontend/package.json), GitHub Actions, or Docker base images. Enforces "proven, well-established, actively-maintained" selection plus version pinning and supply-chain hygiene. TRIGGER when adding/upgrading any dependency, plugin, action, or base image; when editing pom.xml, frontend/package.json / package-lock.json, .github/workflows/*, .github/dependabot.yaml, or any Dockerfile/compose image tag; when proposing a library in a plan; or when the user mentions library/dependency choice, "welche Bibliothek", maintained/unmaintained, CVE, supply chain, SNAPSHOT, version pin, license. SKIP for pure code changes that add no dependency, and for test-only assertions about already-approved libraries.
---

# Dependency Vetting for Glacier

Glacier is a public-facing Fediverse service. Every dependency is part of its attack surface and its long-term maintenance burden. A library that is unmaintained, niche, or unpinned is a future incident. This skill is the **single source of truth** for "geprüfte, gut etablierte, gewartete Bibliotheken" — proven, well-established, maintained dependencies.

Applies to **all** dependency surfaces:
- Maven: `pom.xml` (`<dependency>`, `<plugin>`, BOM imports)
- npm: `frontend/package.json` + `frontend/package-lock.json`
- CI: `.github/workflows/*.yml` (pinned actions), `.github/dependabot.yaml`
- Containers: `Dockerfile`s and image tags in `infrastructure/docker-compose*.yaml`

## The vetting checklist — ALL must pass

Before introducing or upgrading a dependency, confirm each. If any fails, do **not** add it silently — escalate (see below).

1. **Actively maintained** — a release within the last **~18 months**, and recent commit/issue activity. No project that is archived, marked deprecated, or whose last release is >2 years old.
2. **Well established / proven** — meaningful adoption (download counts, dependent projects, a recognizable maintainer org), **more than a single-person bus factor** where possible, and a real release history — not a 0.x with two commits.
3. **Security posture clean** — no **known unpatched** critical/high CVEs. Check the advisory databases (GitHub Advisories / OSV / `npm audit` / OWASP Dependency-Check). A CVE that is patched in the version you pin is fine; an open one is a blocker.
4. **Clear provenance + acceptable license** — published from the canonical org/registry namespace, OSI-approved license compatible with Glacier (Apache-2.0/MIT/BSD/EPL-class fine; copyleft/AGPL or "no license" → escalate). No typosquat-adjacent names.
5. **Pinned, reproducible version** — exact version, never a floating range. See pinning rules below.
6. **Justified need** — it earns its place: not trivially replaceable by the standard library, an existing dependency, or ~30 lines of in-house code. Fewer dependencies = smaller attack surface.
7. **Version maturity (cooldown) — at least 3 days old** — never adopt a version that was published less than **72 hours** ago. Most supply-chain attacks (compromised maintainer account, malicious post-install script, typosquat) are detected and the bad release is yanked within the first 1–2 days; a fixed version is then republished. Waiting 3 days lets that detect-and-fix cycle complete before the artifact reaches Glacier. This applies to upgrades too, not just brand-new dependencies — including transitive bumps you pin explicitly. A genuine emergency security patch may be adopted sooner, but only as a deliberate, recorded exception (see escalation below).

This formalizes the `secure-tdd-implementer` "Dependency Hygiene" rule and maps directly to **[OWASP A06:2021 — Vulnerable and Outdated Components](https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/)**.

## Version pinning rules (Glacier conventions)

- **Maven**: pin an exact released version (`<version>1.2.3</version>`), no version ranges. Releases only — **SNAPSHOTs are forbidden** except the one documented exception below.
- **npm**: `frontend/package.json` uses exact versions and `package-lock.json` is committed; upgrades go through Dependabot PRs, not hand-edits to ranges.
- **GitHub Actions**: pin to a commit SHA (or at minimum a released tag), never a moving `@main`.
- **Docker**: pin a specific tag/digest for base and service images; avoid `:latest`.
- Dependency updates are tracked via `.github/dependabot.yaml` — keep new ecosystems registered there so updates surface as reviewable PRs.

### Cooldown enforcement (the 72-hour rule)

Don't rely on memory to honor criterion 7 — enforce it in the update tooling so PRs for too-fresh versions simply aren't opened:

- **Dependabot**: add a `cooldown` block per ecosystem in `.github/dependabot.yaml` (e.g. `default-days: 3`, with a higher value acceptable for major bumps). This is the preferred channel since Glacier already uses Dependabot. Note the cooldown delays **version** updates only — Dependabot **security** updates (advisory-driven) are a separate pathway and must NOT be delayed, so an urgent CVE fix is never held back by the maturity window.
- **Renovate** (if ever adopted): `minimumReleaseAge: "3 days"`.
- **Manual edits / agent-proposed bumps**: before pinning a version by hand, confirm its publish date — Maven Central, the npm registry page, or the GitHub release timestamp — and reject anything younger than 72 hours. State the publish date in the PR/summary alongside the other vetting facts.

Never hand-bump a third-party dependency to a same-day release to "unblock" a build — that is exactly the window the cooldown exists to close.

### The one documented SNAPSHOT exception: `bigbone`

`social.bigbone:bigbone:2.0.0-SNAPSHOT` is the **only** SNAPSHOT allowed (Sec-02 / ADR-SEC-02). It is a known, accepted supply-chain risk because SNAPSHOT is the only version bigbone publishes upstream. The mitigation (single controlled snapshot fetch in CI, no `-Pallow-snapshot` escape hatch) and the exit plan (pin a real release + add `requireReleaseDependencies` once upstream tags one) live in the comment block in `pom.xml`. **Do not** add further SNAPSHOTs, and do not weaken that comment. Track upstream at https://github.com/andregasser/bigbone/releases.

## When a dependency fails vetting

Do not just drop it in. In priority order:
1. **Prefer what's already on the classpath / in the framework** — Spring Boot, Angular, Angular Material, OkHttp (via bigbone), the JDK. Most needs are already covered.
2. **Pick a vetted alternative** that passes the checklist.
3. **Write it in-house** if it's small and the dependency is marginal.
4. **Escalate to the user** with the specific failing criterion (e.g. "last release 2021, 1 maintainer, no alternative") and let them decide — record the decision (ADR or acceptance doc) if accepted as an exception.

## What to report when you DO add one

State, for each new/upgraded dependency: **name + exact version, last release date, maintainer/adoption signal, license, and CVE-check result**. This belongs in the plan (planner agents) and in the PR/implementation summary (implementer agents) so review can confirm the vetting without re-deriving it.

## Verification hooks

- Maven: `./mvnw verify` (build + tests); OWASP Dependency-Check / GitHub Dependency Scanning in CI flags vulnerable components.
- npm: `npm audit` in `frontend/`; Dependabot alerts.
- A new dependency still obeys the **testing policy** — code paths using it ship with unit + integration tests (and e2e if user-visible), per `CLAUDE.md`.
