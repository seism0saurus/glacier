# Decision Record: Secure CI-Pipeline (Trust-Tier Topology) — Acceptance

Date: 2026-07-04
Phase: Acceptance (P3)
Agents: security-auditor, acceptance-test-auditor (independent audits) + fix cycle (secure-tdd-implementer, devops-infra-engineer, tdd-ddd-implementer)
Status: Accepted — **PASSED WITH CONDITIONS** (blocking conditions resolved)

## Summary

Two independent audits (security + acceptance) returned **0 Critical, 0 High**. The user's core
requirement — bad actors cannot exfiltrate secrets via PRs/forks, every commit runs quality
checks, a merge request runs the full suite without secret access — is met and tested. All
blocking findings from the audits were fixed in the Phase-3 fix cycle; a real GitHub smoke test
validated the topology and surfaced one further schema bug (now fixed) that the static gates
could not catch. Remaining items are tracked follow-ups the user accepted.

## Audit outcome

| Finding | Severity | Disposition |
|---|---|---|
| Release path skipped supply-chain checksum tripwire | HIGH | **Fixed** (Phase 2: `build-and-deploy.yml` `test` delegates to `_build.yml`; `BuildAndDeployChecksumTripwireTest`) |
| F-1 secret-isolation gate did not scan the reusable cores | Medium | **Fixed** (transitive BFS + reusable-core fallback + canary) |
| F-2 script-injection gate lacked commit-message fields | Medium | **Fixed** (blanket `${{ github.event.* }}` ban in `run:` + canary) |
| F-3 / RR-4 deploy-secret boundary was trigger-only | Medium | **Verified + hardened**: user confirmed environment-scoped secrets, no repo duplicates, deployment-tag policy `v*.*.*` set; in-repo `if: startsWith(github.ref,'refs/tags/v')` guard added |
| D-1 MAINTAINER.md claimed 5 e2e legs on release (real: 4) | Low-Med | **Fixed** (doc corrected; drift tracked as follow-up #13) |
| _e2e.yml used `matrix` context in a job-level `if:` (GitHub-invalid) | HIGH (found by smoke test) | **Fixed** (dynamic `fromJSON` matrix; actionlint added to CI to catch the class) |
| F-4 / RR-6 syft `curl\|sh` on the signing path (2 files) | Medium | **Deferred** follow-up #11 (user-accepted) |
| F-5..F-9 (heredoc delimiter, DoS, cache scope) | Low/Info | Accepted / future hardening |

## Smoke test (real GitHub run)

Branch pushed → `quality.yml` (secret-free `_build` core) ran green on GitHub: frontend audit,
gitleaks (with `fetch-depth: 0`), compile, **actionlint**, test, package — all ✓. This validated
the runtime-only invariants the static gates cannot (reusable-workflow call, every-branch trigger,
permissions, checksum steps). It also caught the `_e2e.yml` schema error, which was fixed and
re-verified: the fix-commit `quality.yml` run is green with no workflow-file-issue for `_e2e.yml`.

The full `pull-request.yml` e2e matrix was **not** used as a sign-off gate: its `a11y` leg has a
pre-existing, unrelated failure (tracked as follow-up #9), which would produce a false red.

## Systemic improvement from the smoke test

The static SnakeYAML gates cannot evaluate GitHub Actions expressions, so a `matrix`-in-job-`if`
schema error passed them. `actionlint` (pinned binary + SHA-256, rhysd/actionlint v1.7.12) is now
a job in the reusable `_build.yml` core — it runs on every push, PR and the weekly security scan,
and fails CI on schema/expression errors. A `WorkflowYamlInventoryTest` assertion guards the
actionlint job against silent removal.

## Test results
`./mvnw verify` green: unit + 396 integration + Jacoco ("All coverage checks have been met"),
43 executable `ci` structure-gate tests green. `actionlint .github/workflows/*.yml`: 0 findings.

## User Approval
Date: 2026-07-04
Approval (verbatim, across the sign-off exchange):
- "1. protection rule is set. 2 do a smoke test. if its green approve this quality gate"
- "1. no copies of the secrets."
- "wait for the gate, then push and sign off"

The user thereby verified RR-4 (environment-scoped secrets, no repo duplicates, tag policy),
authorised the real smoke test, and pre-approved sign-off conditional on a green smoke test —
which passed.

## Open follow-ups (tracked, user-accepted)
- #10 RR-4 GitHub-settings — **verified done** by the user (environment-scoped secrets + tag policy); the "last verified on" date field in `docs/MAINTAINER.md` is the user's to fill.
- #11 syft `curl|sh` installer pinning (2 files: `build-and-deploy.yml`, `_e2e.yml`; signing path).
- #12 `paths-ignore` for doc-only commits (optional).
- #13 `build-and-deploy.yml` e2e delegation to `_e2e.yml` (G6 residual; the working `share-https` leg is missing from the release path — drift, not by design).
- #9 a11y e2e leg failure (pre-existing, unrelated to this feature).

## References
- Planning: `docs/decisions/2026-07-03-planning-secure-ci-pipeline.md`
- Implementation: `docs/decisions/2026-07-03-implementation-secure-ci-pipeline.md`
- Operations: `docs/MAINTAINER.md`
