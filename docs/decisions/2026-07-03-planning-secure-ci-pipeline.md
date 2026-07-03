# Decision Record: Secure CI-Pipeline (Trust-Tier Topology) — Planning

Date: 2026-07-03
Phase: Planning (P1)
Agents: ddd-tdd-architect, secure-feature-planner (2 rounds each)
Status: Accepted

## Summary

Rebuild the GitHub Actions CI/CD so that (a) every commit on any branch triggers quality
checks, (b) a pull request runs a very extensive suite that can never reach secrets, (c) fork
PRs never touch secret-bearing jobs, and (d) the deploy credentials are provably unreachable
from any PR or `workflow_run` path. The design partitions workflows by **trust tier** (a
secret-free reusable core vs. a single privileged deploy context) rather than by event, and
enforces every security invariant as an executable SnakeYAML structure-gate under
`src/test/java/de/seism0saurus/glacier/ci/`. Releases move to **git tags** (`v*.*.*`).

Feature request (verbatim): *"design the pipeline in a way, i can do a full test of my feature
branch without worying that bad actors could extract secrets from ci or trigger malicious
actions through pull requests. If there is an official github actions skill use that. Every
commit on a branch should trigger quality checks. A merge request should do a very extensive
check but never leak secrets. Security is very important"*

Normative reference (there is no official GitHub-Actions Claude skill): GitHub's official
security-hardening documentation — Security-hardening guide, `GITHUB_TOKEN` permissions,
events (`pull_request` vs `pull_request_target`), GitHub Security Lab "Preventing pwn requests"
and "Untrusted input", cache isolation, secrets/environments. These are now recorded as the
normative reference block in `.claude/agents/devops-infra-engineer.md`.

## Target topology

Secret-free reusable core (`workflow_call`, own minimal `permissions:`, never `secrets: inherit`):
`_build.yml`, `_e2e.yml`, `_security-dast.yml`.

| Workflow | Trigger | Calls | Trust tier |
|---|---|---|---|
| `quality.yml` (new) | `push: branches: ['**']` | `_build` | untrusted-safe |
| `pull-request.yml` (rebuilt) | `pull_request` (incl. fork) | `_build` + `_e2e` (5 legs) + `_security-dast` | untrusted-safe |
| `pr-comment.yml` (new) | `workflow_run` | — | privileged (only `pull-requests: write`) |
| `full-suite.yml` (new) | `workflow_dispatch` (any branch) | `_build` + `_e2e` + `_security-dast` | untrusted-safe |
| `build-and-deploy.yml` (hardened) | `push: tags: ['v*.*.*']` | core + publish + deploy | **privileged (only secret context)** |
| `security.yml` (repaired) | `schedule` + `workflow_dispatch` | `_build` + `_security-dast` | untrusted-safe |
| `codeql.yml`, `mutation.yml` | unchanged | — | untrusted-safe |

## Key Decisions (ADRs)

| ADR | Title | Status |
|---|---|---|
| ADR-CI-01 | Trust-tier topology (trusted vs untrusted workflows) instead of event topology | Accepted |
| ADR-CI-02 | Reusable `_*.yml` declare their own minimal top-level `permissions:` (else inherit caller scope) | Accepted |
| ADR-CI-03 | Least-privilege `GITHUB_TOKEN`, explicit top-level `permissions:` everywhere; `pull_request_target` repo-wide forbidden | Accepted |
| ADR-CI-04 | *(superseded by ADR-CI-14)* | Superseded |
| ADR-CI-06 | `_security-dast.yml` self-contained (build jar+image in-run; drop the GHCR `docker manifest inspect` precondition) | Accepted |
| ADR-CI-07 | CI invariants as SnakeYAML unit-test gates (extends `WorkflowYamlInventoryTest`/`BigboneChecksumPinTest`) | Accepted |
| ADR-CI-08 | Deploy trigger is security-neutral; the real protection is environment-protection (ADR-CI-16) | Accepted |
| ADR-CI-09 | Cache-family isolation; PR caches carry a `pull`/`pr` prefix and never reference the `main` family | Accepted |
| ADR-CI-10 | Remove the `pull_request: closed` trigger from `build-and-deploy.yml` (redundant — `push` on the release ref covers merge-deploy — and dangerous: unguarded downstream jobs run deploy in PR context) | Accepted (new) |
| ADR-CI-11 | Script-injection ban: attacker-controlled `${{ github.event.* }}` only via `env:` intermediary + `"$VAR"` (gate 5.9) | Accepted (new) |
| ADR-CI-12 | 40-hex SHA pin for every `uses:` (gate 5.10, freezes the current state) | Accepted (new) |
| ADR-CI-13 | No self-hosted `runs-on` (gate 5.11) | Accepted (new) |
| ADR-CI-14 | `workflow_run` PR comment: PR number from an artifact file, `^[0-9]+$`-validated (fail-closed), `run-id`-scoped download; never interpolate untrusted data into `run:` | Accepted (new, supersedes ADR-CI-04) |
| ADR-CI-15 | Fork-tolerant SARIF uploads (`continue-on-error` + fork guard); the finding gate stays on the scanner exit-code, not the upload | Accepted (new) |
| ADR-CI-16 | Environment-scoped deploy secrets + deployment-branch/tag restriction, as a documented manual control (partly gate-checkable) | Accepted (new) |
| ADR-CI-17 | `docs/MAINTAINER.md` as maintainer-facing operations documentation | Accepted (new) |
| ADR-CI-18 | Release marker mechanism | **Resolved: git tags `v*.*.*`** |

### ADR-CI-18 — Release via git tags (user decision)
**Decision**: Versioned releases are cut as annotated git tags `v*.*.*`; `build-and-deploy.yml`
triggers on `push: tags: ['v*.*.*']`. Retires the `*.*.*`-branch release marker and the
`0.0.8-verify-relay` temp-branch workaround.
**Rationale**: Immutable release semantics; `github.ref_name` yields the version tag as the GHCR
image tag; decouples release identity from the CI trigger.
**Consequences**: `push_version.sh` signature changes (creates/pushes a tag instead of taking a
branch arg); `.github/dependabot.yaml` moves to `target-branch: main`; the `*.*.*` push triggers
in `verify.yml`/`security.yml`/`build-and-deploy.yml` become `tags: ['v*.*.*']`; README release
docs and `docs/MAINTAINER.md` §Releases follow tag semantics.
**Source**: secure-feature-planner (security-neutral) + user decision 2026-07-03.

## Security Requirements → Gate coverage

20 requirements (SR-CI-01..20); planner final pass: 17/20 fully covered, SR-CI-13/20 tag-coupled
(now resolved by ADR-CI-18), SR-CI-15/16 part-structural + accepted residual risk.

| Gate (`ci/*Test.java`) | Invariant | SR-CI | Owner lane |
|---|---|---|---|
| 5.1 SecretIsolation/PwnRequest | no `pull_request_target`; no non-`GITHUB_TOKEN` secret in untrusted paths (run/env/with/uses); incl. `_*.yml` | 01,03 | secure-tdd |
| 5.2 PermissionsMinimization | top-level `permissions:` everywhere; elevated scopes only on allowlist; not in PR workflow except `security-events:write` | 02,06 | secure-tdd |
| 5.3 DeployTrigger/NoDeploySecrets | `build-and-deploy.yml` has no `pull_request` trigger; deploy secrets only in the `environment:` job; no `set -x` in deploy | 04,05,17 | secure-tdd |
| 5.4 WorkflowRunPrCommentSafety | `pr-comment.yml` `workflow_run`-only, `run-id`-scoped, `^[0-9]+$`, `permissions:{}` + `pull-requests:write`, no PR-code build/exec | 10,11 | secure-tdd |
| 5.5 EveryBranchQualityTrigger | `quality.yml` `push: branches:['**']`, secret-free, calls only `_build` | 13 | secure-tdd |
| 5.6 SecurityDastSelfContained | no `docker manifest inspect ghcr.io/`; image built in-run | 14 | secure-tdd |
| 5.7 CachePoisoningIsolation | PR cache keys carry `pull`/`pr` prefix; no `main` family in PR workflow | 12 | secure-tdd |
| 5.8 WorkflowYamlInventory | bijective completeness; SR-NA-02 + frontend-audit invariants migrated to new location (else vacuously green) | — | tdd-ddd |
| 5.9 ScriptInjection | no `run:` interpolates attacker-controlled `${{ github.event.* }}` directly | 07 | secure-tdd |
| 5.10 ActionsPinned | every `uses:` a 40-hex SHA (except `./` + `_*.yml`) | 08 | secure-tdd |
| 5.11 NoSelfHostedRunner | `runs-on` always GitHub-hosted; `self-hosted` forbidden | 09 | secure-tdd |
| +SARIF fork-tolerance | every `upload-sarif` fork-tolerant | 15 | secure-tdd |
| +Environment/Chain | deploy job has `environment:`; `deploy needs publish-image needs e2e+trivy+zap` | 16,19 | secure-tdd |

## Live defects this rebuild fixes (verified against the repo)

- `build-and-deploy.yml` has a live `pull_request: closed` trigger with no `if`-guards on the
  downstream jobs → closing a same-repo PR against main runs the whole chain **including deploy
  with real SSH secrets** in PR context (ADR-CI-10).
- The current `gh pr comment` runs in the untrusted PR context and is broken for fork PRs anyway.
- `pull-request.yml` has no top-level `permissions:` (inherits wide default scope).
- `security.yml`-baseline was never green (self-contained repair, ADR-CI-06).

## Phase 2 lane partition (disjoint)

Sequence: tdd-ddd → secure-tdd → devops (gates must exist RED before devops makes them GREEN).

- **Lane A — tdd-ddd-implementer**: `src/test/java/.../ci/WorkflowInventory*.java` (SnakeYAML harness/fixture), `WorkflowYamlInventoryTest.java` (gate 5.8 + SR-NA-02/frontend-audit relocation), `docs/MAINTAINER.md` + cross-reference edits in README.md, docs/DEVELOPER.md, CLAUDE.md, infrastructure/README.md, docs/decisions/README.md. Touches no workflow YAML.
- **Lane B — secure-tdd-implementer**: all security gate test classes (each its own file): ScriptInjection, ActionsPinned, NoSelfHosted, PwnRequest, SecretExposure, PrivilegeAllowlist, DeployTrigger, RunPattern, CachePoisoning, SarifForkTolerance, EnvironmentSecret. Consumes Lane-A harness read-only.
- **Lane C — devops-infra-engineer**: all `.github/workflows/*.yml` (existing + new quality/full-suite/pr-comment/_build/_e2e/_security-dast), `.github/actions/**`, `.github/dependabot.yaml`, `push_version.sh` (tag migration). Must preserve the four e2e legs (glacier-fallback-mode-discipline). Makes Lane-A/B gates green.
- **Manual (no lane, USER)**: GitHub settings — environment-scoped deploy secrets + deployment-tag restriction (SR-CI-16/RR-4), branch protection, CodeQL required checks.

No write conflict: A/B write only under `src/test/.../ci/` (disjoint filenames), C only under `.github/` + `push_version.sh`.

## Resolved Conflicts

### PR-number source in the `workflow_run` coverage comment
**secure-feature-planner**: `github.event.workflow_run.pull_requests[0].number` is empty for fork
PRs; the number must come from an artifact file, `^[0-9]+$`-validated, `run-id`-scoped.
**ddd-tdd-architect**: initially proposed `pull_requests[0].number`.
**Resolution** (2026-07-03): architect conceded fully → ADR-CI-14. The untrusted PR run writes
`github.event.number` into `pr-number.txt` and uploads it; `pr-comment.yml` downloads it
`run-id`-scoped, validates `^[0-9]+$` (fail-closed, bounded read, fixed artifact name/extract dir),
passes it via `env:` to `gh pr comment --body-file` — never a direct `${{ }}` interpolation into
`run:`. `pull_requests[0].number` is at most a same-repo fallback.

## User Approval
Date: 2026-07-03
Approval messages (verbatim):
- "ich würde gerne mit git Tags releases machen." (→ ADR-CI-18 = git tags)
- "approve with workflow_run for coverage" (→ gate approved, coverage via `workflow_run` = ADR-CI-14)

The user thereby accepted the plan (18 ADRs, 12 structure gates), the `workflow_run` coverage
mechanism, git-tag releases, and residual risks RR-1..RR-6 with the planner's 6 GO-conditions.

## Open Risks (accepted by the user)

- **RR-1**: fork-PR SARIF does not reach the Security tab (only run checks); the merge gate stays on the scanner exit-code.
- **RR-2**: the `workflow_run` coverage comment keeps a minimal data-only artifact-poisoning surface (mitigated by ADR-CI-14 hardening). Zero-surface alternative (`GITHUB_STEP_SUMMARY`-only) was declined in favour of the inline comment.
- **RR-3**: throwaway fixture token appears in CI logs/artifacts — accepted (committed throwaway value, `.gitleaks.toml`-allowlisted). Guarded by SR-CI-03 (no real secret can reach those stacks).
- **RR-4**: environment-protection + tag restriction live in GitHub settings, not repo files — must be verified manually and recorded in the acceptance commit.
- **RR-5**: `quality.yml` on every push costs runner minutes; optional `paths-ignore` for doc-only commits.
- **RR-6**: `syft` is installed via unpinned `curl|sh` from mutable `main` (`build-and-deploy.yml`) — the SHA-pin gate does not cover `curl|sh`; to be pinned/hardened in Phase 2.

### GO conditions (planner, blocking 1–4 + 6; 5 optional)
1. PR-number artifact: bounded read + validate-before-use (fail-closed), fixed artifact name/extract dir.
2. Migrate deploy secrets to environment scope + deployment-tag restriction (manual GitHub settings).
3. Fork-tolerant SARIF uploads; finding gate stays on scanner exit-code.
4. Remove the `build-and-deploy.yml` `pull_request: closed` trigger.
5. (optional) `paths-ignore` for doc-only commits.
6. Pin/harden the `syft` installer (RR-6).

### Deferral decision (user, 2026-07-03: "accept risks 1-6 for now and schedule them for later")
The residual risks RR-1..RR-6 are accepted. The in-repo security fixes that constitute the
feature itself — GO-conditions 1 (PR-number hardening), 3 (SARIF fork-tolerance), 4 (remove
`pull_request:closed`) — are still built as normal Phase 2 work. The genuinely deferrable
mitigations are scheduled as tracked follow-ups rather than blocking sign-off:
- **RR-4 / condition 2**: manual GitHub settings (environment-scoped secrets + tag restriction) — user action, post-merge.
- **RR-6 / condition 6**: pin/harden the `syft` `curl|sh` installer — follow-up.
- **RR-5 / condition 5**: `paths-ignore` for doc-only commits — optional optimization, follow-up.

## References
- `.claude/agents/devops-infra-engineer.md` — GitHub Actions security-hardening normative reference block.
- `.claude/skills/dependency-vetting.md` — SHA-pin + 72h cooldown rules.
- Prior: `docs/decisions/2026-04-28-{planning,implementation,acceptance}-pentest-findings.md` (origin of `security.yml` + ZAP stack).
- Existing gates: `src/test/java/de/seism0saurus/glacier/ci/{WorkflowYamlInventoryTest,BigboneChecksumPinTest}.java`.
