    # Decision Record: Share Link with QR Code — Implementation (Phase 2)

Date: 2026-04-23
Phase: Implementation
Agents: `tdd-ddd-implementer`, `secure-tdd-implementer`, `devops-infra-engineer`, `frontend-designer` (nominal) — orchestrator β-path completion for secure + frontend (see "Protocol deviation")
Status: Accepted (with conditions documented below; forward path is P3-C consolidation)

## Summary

Phase 2 Round 1 produced committed work on all four lane-specific worktrees. The Phase-2 decision doc records the committed state and the pragmatic completion path. Round 2 cross-review was **not** executed separately — it was collapsed into explicit `## FIX REQUEST → Phase 2 Round 2` markers in the per-lane summaries, because the Claude Code permission sandbox repeatedly blocked sub-agent Edit/Write even with `bypassPermissions` and `Edit(**)` / `Write(**)` allowlist entries. Two lanes (`secure-tdd-implementer`, `frontend-designer`) were completed by the orchestrator (β path from the user-approved α + β combined) under the explicit directive to *land* prior specialist output rather than to produce new design decisions. The forward path approved is **P3-C consolidation**: the four lane branches are merged onto a single `feature/share-link-qr` branch in the main repo, `./mvnw verify` and `ng test` surface the real state, and Phase 3 acceptance runs against that unified branch.

## Protocol deviation (transparent disclosure)

Four sub-agent dispatches failed this session on permission-sandbox issues:
- `frontend-round1-finish` (v1) — Edit/Write/Bash denied; reported contract-drift audit successfully.
- `secure-round1-finish-v2` — Edit/Write denied across CWD boundary (cross-worktree). Correctly diagnosed the situation; pre-computed the merge resolution for `WebSocketConfiguration.java`.
- `frontend-round1-finish-v3` (bypassPermissions) — Edit denied despite mode; requested allowlist changes.
- `frontend-round1-finish-v4` (bypassPermissions + `Edit(**)` / `Write(**)` in allowlist) — still denied.

Root cause: background async sub-agents have an Edit/Write evaluation anchor outside the spawning session's worktree. User authorized α + β combined (add permissions + orchestrator mechanical finishing). The orchestrator then:
- Resolved 4 merge conflicts in the secure worktree (`WebSocketConfiguration.java`, `WebSocketConfigurationTest.java`, `GlacierApplication.java`, `InformationControllerTest.java`) using the pre-computed specialist outputs.
- Committed the secure-lane extension snapshot (the 34 files the previous sub-agent had written but never committed).
- Applied the 10 TS/Java contract-drift fixes in the frontend worktree (fixes that the blocked sub-agent had already diagnosed in its audit report).
- Committed the frontend lane.
- Wrote both Round-1 summaries.

**No new specialist decisions were made** by the orchestrator. All design calls trace to Phase 1 decisions or prior specialist output.

## Key Decisions

### Decision: Commits landed per lane

**Decision**: Four lane branches now carry these commits:
- `worktree-agent-a304356b` (tdd-ddd): `7fc92a1` — "Add share-link domain, application, infrastructure, and web layers (TDD-first)". `./mvnw verify` passed at the time of this commit (380 unit + 80 IT, Jacoco thresholds met per commit message).
- `worktree-agent-a6085740` (secure): `2091377` + `a9a0716` + `313eb85`. Build **NOT verified** in this session.
- `worktree-agent-af1e95f4` (devops-infra): `19c7b39` — Traefik share.proxy routing, TLS SAN, env vars, jsoup + caffeine deps, killswitch + insecure compose overrides, verify.yml matrix.
- `worktree-agent-a10ed506` (frontend): `ec0e65f` — Angular share module, QR/dialog components, 9 Playwright specs, contract-drift realignment. Build **NOT verified**.
**Rationale**: All four lanes have a stable, readable, reviewable committed artifact. This is the minimum required state for Round 2 cross-review (or Phase 3 audit) to consume.
**Source**: Orchestrator β-path execution 2026-04-23; user-approved path ("α + β combined", then "p3-c").

### Decision: Round 2 cross-review collapsed into FIX REQUEST markers

**Decision**: Skip the separate `## Round 2 — Cross-Review` sub-agent dispatch step. Instead, each lane's summary at `/tmp/glacier-pipeline-share-link/{secure_impl,frontend_impl}.md` carries explicit `## FIX REQUEST → Phase 2 Round 2` items that describe what the would-be-Round-2 reviewers were expected to verify or fix. These items are routed forward as inputs to the P3-C consolidation + Phase 3 audit.
**Rationale**: The sandbox failure makes Round-2 specialist dispatching impossible in this session. Rather than pretend Round 2 happened, the gaps are surfaced honestly for the next stage (consolidation + audit) to address.
**Alternatives considered**: Run Round 2 anyway with expected failures (wasteful); restart session and resume (S2 — rejected here in favor of P3-C); consolidation without Round 2 (P3-C, accepted).
**Source**: User approval "p3-c" 2026-04-23.

### Decision: Secure lane merged main; 4 conflicts resolved mechanically

**Decision**:
- `WebSocketConfiguration.java`: combined HEAD's `@Autowired ShareViewTopicAuthInterceptor` + `configureClientInboundChannel` + `/share-view-ws` endpoint registration with main's `cookieSecure`-conditional allowed-origins list. Kept the secure lane's `secureCookies` field name (to avoid renaming multiple call sites; a future Round-2 ## CLARIFICATION REQUEST may realign to main's `cookieSecure` convention).
- `WebSocketConfigurationTest.java`: kept HEAD's vararg-safe stub comment.
- `GlacierApplication.java`: kept main's `java.io.IOException` + `HttpURLConnection` imports (embed/fetch code uses them).
- `InformationControllerTest.java`: merged @TestPropertySource properties (cookie.secure, fallback rate limits, eviction interval, + the new `glacier.share.imgproxy.hmacSecret` test property so the Spring context can boot under @SpringBootTest with the fail-closed validator).
**Rationale**: Each resolution preserves both sides' functional intent with minimal code churn. Documented verbatim in the merge commit (`a9a0716`).
**Source**: Prior secure sub-agent's pre-computed resolution + orchestrator application.

### Decision: Frontend TS/Java contract drift resolved

**Decision**: 10 field mismatches in `frontend/src/app/share/model/readonly-toot-view.ts` realigned to the authoritative Java record. See the complete table in the frontend lane's summary. Consumer files (components + test fixtures) propagated in the same commit (`ec0e65f`).
**Rationale**: At runtime the TS interface and Java record must match for the readonly view to render anything. This was the #1 ⚡ CONFLICT raised by the first sub-agent audit.
**Source**: Prior frontend sub-agent's audit + orchestrator mechanical application.

### Decision: Orchestrator-side `.claude/settings.local.json` permission additions

**Decision**: Added to the project allowlist in the frontend worktree:
- `Edit(**)`, `Write(**)` — broad file-edit permissions for future sub-agents.
- `Bash(npm *)`, `Bash(./node_modules/.bin/*)`, `Bash(find *)`, `Bash(grep -r *)` — frontend build/test conveniences.
**Rationale**: Required to attempt the later sub-agent dispatches (even though they still failed due to the deeper CWD-anchor issue). Pattern entries are narrow (no dangerously broad wildcards on Bash).
**Source**: User approval at the α + β combined step.

## Known gaps (forwarded to Phase 3 or fix cycle)

**From secure lane summary** (`/tmp/glacier-pipeline-share-link/secure_impl.md`):
1. `## FIX REQUEST → Phase 2 Round 2 / Phase 3`: `MessageCacheImpl` + `FallbackRateLimiter` still use `String`-keyed maps — PrincipalKey migration not yet wired. ADR-SHARE-05 requires compile-enforced typed keying.
2. `## FIX REQUEST → Phase 2 Round 2 / Phase 3`: Several lane-listed ITs not yet present (`ShareViewControllerIT`, `ShareViewStompRelayIT`, `WallIdLeakageIT`, `ShareCatalogEndpointTimingIT` p95 ± 15 %, `ShareViewRevocationPushIT` 1 s SLA, `FallbackRateLimiterShareIT`, `FallbackRateLimiterKeyingTest`, `PrincipalHandlerIT`, `ShareLinkExpirationIT`).
3. `## CLARIFICATION REQUEST → Phase 1`: `secureCookies` vs `cookieSecure` field naming.
4. Build verification NOT run. `./mvnw verify` expected to surface compile errors (PrincipalKey type use-sites) and test failures.

**From frontend lane summary** (`/tmp/glacier-pipeline-share-link/frontend_impl.md`):
1. `## FIX REQUEST → Phase 2 Round 2 / Phase 3`: `npm install` + `ng test` + `npx eslint .` + `npx playwright test --list` not run against the commit.
2. Frontend branch not merged with main (fallback + refactor-agents). Possibly orthogonal to backend changes; to be confirmed.

## Proposed forward path: P3-C consolidation

1. Create `feature/share-link-qr` in `/home/ulrich.viefhaus/git/seism0saurus/glacier` (main repo) based on current `main` (HEAD `9783cb1`).
2. Merge each lane branch into it in an order that minimises conflict churn (likely: devops → tdd-ddd → secure → frontend; devops first because it only touches infrastructure/, verify.yml, compose overrides; tdd-ddd second because it adds new files in `src/main/java/.../share/domain/` and `application/`; secure third because it extends domain with security work on top of tdd-ddd; frontend last because it's orthogonal to backend lanes).
3. Run `./mvnw verify` and `cd frontend && npm install && ng test && npx eslint .`.
4. Each failure is reported as a Phase-3 finding; the Phase-3 audit phase is where the gaps get closed.
5. Phase 3 (acceptance) runs against the consolidated branch.

## User Approval

Date: 2026-04-23
Approval messages (verbatim):

Α + β combined (allow Edit/Write + orchestrator takes over mechanical finishing):
> "α + β combined"

Phase 2 Approval Gate + forward path:
> "p3-c"

## Open Risks (explicitly accepted by user)

- **Unverified build on two orchestrator-completed lanes.** Mitigated by P3-C consolidation where `./mvnw verify` and `ng test` will run and surface real compile/test failures for Phase-3 routing.
- **PrincipalKey migration deferred.** Mitigated by consolidation placing all secure work in one readable branch that the Phase-3 fix cycle can close.
- **Round 2 cross-review skipped.** Mitigated by the explicit `## FIX REQUEST` markers in lane summaries — these become Phase-3 auditor inputs.
- **Frontend branch behind main.** Resolved by the P3-C merge step.
- **Protocol deviation** — orchestrator landed specialist work directly. Transparently disclosed above; scope limited to landing *previously-diagnosed* specialist outputs + mechanical git ops.

## References

- Phase 1 decision: `docs/decisions/2026-04-22-planning-share-link-qr.md`
- Secure lane summary: `/tmp/glacier-pipeline-share-link/secure_impl.md`
- Frontend lane summary: `/tmp/glacier-pipeline-share-link/frontend_impl.md`
- tdd-ddd lane: commit message on `7fc92a1`
- devops-infra lane: commit message on `19c7b39`
- Prior fallback-mode planning: `docs/decisions/2026-04-21-*.md`, `docs/decisions/2026-04-22-acceptance-ws-fallback.md`
