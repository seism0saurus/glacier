# Decision Record: Secure CI-Pipeline (Trust-Tier Topology) — Implementation

Date: 2026-07-03
Phase: Implementation (P2)
Agents: tdd-ddd-implementer (Lane A), secure-tdd-implementer (Lane B), devops-infra-engineer (Lane C) — 2 rounds
Status: Accepted

## Summary

The trust-tier CI topology from the planning phase is implemented and `./mvnw verify`-green.
A secret-free reusable core (`_build.yml`, `_e2e.yml`, `_security-dast.yml`) is driven by
`quality.yml` (push to any branch), `pull-request.yml` (full suite incl. forks), `full-suite.yml`
(dispatch) and a repaired `security.yml` (cron/dispatch). Secrets live only in `build-and-deploy.yml`
(tag-triggered, environment-gated). 13 SnakeYAML structure-gate test classes enforce the security
invariants. Releases moved to git tags (`v*.*.*`).

Sequence (per project rule + technical dependency): Lane A (harness, gates RED) → Lane B (security
gates RED) → Lane C (workflows, gates GREEN).

## Files

### Workflows created (`.github/workflows/`)
- `_build.yml` — reusable core: compile → test (lint, sheriff-arch, i18n-parity, sqlite+bigbone SHA-256 checksums, `mvnw verify`) → package (jar + SBOM); plus relocated `frontend-audit` (npm audit) + `secret-scan` (gitleaks, `fetch-depth: 0`). Own minimal `permissions:`, no `secrets: inherit`.
- `_e2e.yml` — reusable: full 5-leg Playwright matrix (standard/killswitch/a11y/insecure/share-https), inputs `legs`/`build-image`.
- `_security-dast.yml` — reusable: builds jar+image in-run, ZAP baseline/header/BOLA/Trivy, no GHCR `docker manifest inspect`, fork-tolerant SARIF.
- `quality.yml` — `push: branches: ['**']` → `_build`, secret-free.
- `pr-comment.yml` — `workflow_run`; `prepare` job (`contents:read`+`actions:read`, run-id-scoped download, `^[0-9]+$` PR-number validation) + `pr-comment` job (`pull-requests:write`).
- `full-suite.yml` — `workflow_dispatch`, secret-free, calls `_build`+`_e2e`+`_security-dast`.

### Workflows changed
- `pull-request.yml` — top-level `permissions:`, calls core+e2e+dast, `pull`/`pr` cache prefixes, restore-key typo fixed, writes `pr-number.txt` artifact, `gh pr comment` job removed (→ pr-comment.yml).
- `build-and-deploy.yml` — `pull_request:closed` trigger removed (ADR-CI-10), release trigger `push: tags: ['v*.*.*']`, top-level `permissions: {}`, `test` job delegates to `_build.yml` (G6 consolidation + closes the checksum-tripwire gap), trivy jobs renamed `*-sarif`, `dependency-submission` split out, fork-tolerant SARIF.
- `security.yml` — cron/dispatch wrapper calling `_build`+`_security-dast`.
- `codeql.yml` — top-level `permissions:`, event-name-dependent cache prefix (push vs pull_request isolation).
- `setup-java-cache.yml` — top-level `permissions:`.
- `verify.yml` — **deleted** (ADR-CI-18; replaced by quality.yml + _build + _e2e).
- `push_version.sh` — new signature `push_version.sh <version>`: bumps pom.xml/package.json/README, commits, creates annotated tag `v<version>`, does **not** auto-push (tag push triggers the privileged deploy — an explicit manual step).

### Tests created/changed (`src/test/java/de/seism0saurus/glacier/ci/`)
- Harness (new): `WorkflowInventory.java`, `WorkflowFile.java`, `WorkflowJob.java`, `WorkflowStep.java`.
- Gate classes (new): `WorkflowSecretIsolationTest`, `WorkflowPermissionsMinimizationTest`, `WorkflowDeployTriggerTest`, `WorkflowRunPrCommentSafetyTest`, `EveryBranchQualityTriggerTest`, `SecurityDastSelfContainedTest`, `WorkflowCachePoisoningIsolationTest`, `WorkflowScriptInjectionTest`, `WorkflowActionsPinnedTest`, `WorkflowNoSelfHostedRunnerTest`, `WorkflowSarifForkToleranceTest`, `WorkflowEnvironmentSecretTest`, `BuildAndDeployChecksumTripwireTest`.
- Migrated: `WorkflowYamlInventoryTest` (bijective completeness + SR-NA relocation), `BigboneChecksumPinTest` (→ `_build.yml`, `Files.exists` guard, harness-based).

### Docs
- `docs/MAINTAINER.md` (new) — maintainer guide: commits, PRs, releases, pipeline security model, manual settings checklist, accepted residual risks.
- Cross-reference edits: `docs/DEVELOPER.md`, `docs/decisions/README.md`, `README.md`, `CLAUDE.md`, `infrastructure/README.md`.
- Release-flow corrections (self-caused by this feature): `CLAUDE.md` (verify.yml reference → new topology; push_version.sh signature), `README.md` (Versioning and releases → tag flow).
- `.claude/agents/devops-infra-engineer.md` — GitHub Actions security-hardening normative reference block.

## Test Results
`JAVA_HOME=…/temurin-23.0.2 ./mvnw verify` → **BUILD SUCCESS**: 2102 unit (Surefire) + 396 integration (Failsafe), 0 failures/errors, Jacoco "All coverage checks have been met." All 41 `ci` gate tests green.

## Key Decisions

### HIGH finding resolved: release path skipped the checksum tripwire
**Decision**: `build-and-deploy.yml`'s `test` job delegates to `_build.yml` (variant a) instead of running its own `mvnw verify`, so the only workflow that publishes a cosign-signed/SLSA-attested image runs the sqlite/bigbone SHA-256 tripwire like every other path.
**Rationale**: The publishing path skipping supply-chain byte-integrity verification (OWASP A08:2021) while running under the signature/provenance consumers trust is a HIGH gap. Variant a also achieves consolidation goal G6.
**Source**: tdd-ddd-implementer surfaced it, secure-tdd-implementer confirmed (HIGH) + authored `BuildAndDeployChecksumTripwireTest`, user approved fix-now, devops-infra-engineer implemented.

### frontend-audit + secret-scan relocated to `_build.yml` (not pull-request.yml)
**Decision**: Both jobs move into the reusable core.
**Rationale**: `_build.yml` is called by quality.yml, pull-request.yml, full-suite.yml AND security.yml (weekly cron) — only there is SR-NA-03 (weekly CVE re-detection) preserved after security.yml loses its own push/pull_request triggers.
**Source**: tdd-ddd-implementer (Lane A).

### pr-comment.yml security review
**Finding**: safe as designed — PR number fail-closed `^[0-9]+$`-validated before use, all untrusted values via `env:` intermediary, `--body-file` not argument. One Low residual (GITHUB_OUTPUT heredoc delimiter derived from fork-influenced report content — no escalation path, since the derived output feeds no downstream command and pr_number comes from a separate step). Documented for a future hardening pass (randomized delimiter).
**Source**: secure-tdd-implementer (Round 2).

## Resolved Conflicts

### BigboneChecksumPinTest hardcoded verify.yml/pull-request.yml
**Lane C**: could not keep the test green after deleting verify.yml + moving checksums to `_build.yml` (workflow-only change insufficient).
**Resolution** (2026-07-03): routed to Lane A (test lane) — migrated to target `_build.yml` with `Files.exists` guard + a drift-guard against re-introduced duplication. Mechanical migration explicitly foreseen in planning §5.8.

### build-and-deploy.yml skips checksum tripwire (HIGH)
See Key Decisions. User decision (2026-07-03): fix now. The temporary canary test that documented the gap was removed once the permanent gate turned green.

## User Approval
Date: 2026-07-03
Approval message (verbatim): "approve"
(Preceded by the explicit fix-now decision on the HIGH build-and-deploy checksum finding.)

## Open Risks / deferred to follow-up

- RR-4 environment-scoped deploy secrets + deployment-tag restriction — manual GitHub settings, follow-up (task #10).
- RR-6 syft `curl|sh` installer pinning — follow-up (task #11).
- RR-5 `paths-ignore` for doc-only commits — optional, follow-up (task #12).
- pr-comment.yml GITHUB_OUTPUT heredoc delimiter — Low, future hardening (no escalation path).
- The new workflows are validated locally (structure gates + `mvnw verify`) but have not yet run on GitHub — that first happens on push. Phase 3 audit rests on static analysis + optionally a real CI run.
- Pre-existing, unrelated: a11y e2e leg failure (task #9).

## References
- Planning: `docs/decisions/2026-07-03-planning-secure-ci-pipeline.md` (18 ADRs, 20 SR-CI, gate list, lane partition).
- `docs/MAINTAINER.md` — operational guide for the implemented topology.
