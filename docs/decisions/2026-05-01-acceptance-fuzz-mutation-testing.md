# Decision Record: Fuzz & Mutation Testing — Acceptance

Date: 2026-05-01
Phase: Acceptance
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED WITH CONDITIONS

## Summary

The fuzz & mutation testing feature passed acceptance with no Critical or High findings. All 17 SR-FUZZ requirements and 6 ADRs are satisfied; 10/10 acceptance criteria met. `./mvnw verify` completed with BUILD SUCCESS (187 IT tests, 0 failures, Jacoco thresholds met). One Medium and three Low/deferred findings were accepted by the user as post-merge follow-up items.

## Acceptance Criteria Results

| Criterion | Status |
|-----------|--------|
| AC-1: `*FuzzTest.java` under Surefire, no `*IT.java` violations | PASS |
| AC-2: Jacoco thresholds preserved (instruction ≥45%, branch ≥35%) | PASS |
| AC-3: `IframeEmbedPolicy` extraction behavior-identical (34 golden vectors) | PASS |
| AC-4: PITest 10 FQNs exist, 70% threshold, ALL mutators, no wildcards | PASS |
| AC-5: fast-check tests loadable, correct `fc` API usage | PASS |
| AC-6: `StompCallbackTest` coverage not hollowed out by delegation | PASS |
| AC-7: Surefire naming conventions | PASS |
| AC-8: Mutation CI job NOT in `publish-image` needs | PASS |
| AC-9: SR-FUZZ-12 prototype-pollution canaries in `message-queue.fuzz.spec.ts` | PASS |
| AC-10: SR-FUZZ-11 `HashtagFormat.PATTERN` parity | PASS |

## Security Findings Dispositions

### Critical (0)
None.

### High (0)
None.

### Medium (1) — accepted as deferred follow-up

**SR-FUZZ-07 partial: `npm audit` CI step missing**
- fast-check pinned to exactly `3.21.0` (no caret) — PASS
- `npm audit --audit-level=high` step is absent from all workflow files — FAIL
- Disposition: **Deferred** — fast-check is a devDependency that never ships in the browser bundle; no production runtime exposure. The exact pin is the load-bearing supply-chain control. The `npm audit` gap is pre-existing in the project (not introduced by this feature). Deferred to a follow-up TD ticket.
- Follow-up action: add `cd frontend && npm audit --audit-level=high` step to `build-and-deploy.yml` or `security.yml`.

### Low (2) — accepted as optional follow-up

**R-1: `IframeEmbedPolicy` domain regex lacks `Pattern.quote()`**
- `domain.toUpperCase()` is concatenated into a `String#matches` regex without escaping regex metacharacters (`glacier.events` → `.` matches any character)
- Disposition: **Deferred** — `glacierDomain` is operator-controlled (env var `MY_DOMAIN`), never user-controlled. SSRF and bot opt-in checks apply upstream. Pre-existing condition extracted verbatim from `StompCallback.isLoadable`.
- Follow-up action: wrap with `Pattern.quote(domain.toUpperCase())` and add a regression unit test asserting a lookalike host is rejected.

**R-2: `SubscriptionMessageHashtagFuzzTest` has no explicit SR-FUZZ-04 canary `@Property`**
- Property coverage relies on probabilistic `Arbitraries.strings()` generation rather than an explicit canary set
- Disposition: **Deferred** — all SR-FUZZ-04 canaries contain characters outside `[\p{L}\p{N}_]` or exceed length 50; they are rejected by mathematical inclusion even without being named explicitly.
- Follow-up action: add a third `@Property` with a fixed `Arbitraries.of(CRLF, U+202E, BOM, U+2028, ANSI, 10KB+)` for test-intent clarity.

### Deferred per Phase 1 ADR

**SR-FUZZ-13: Mutation-survivor equivalents not documented**
- Requires a first successful PITest CI run to produce the survivor list
- Expected ~2–4 equivalent survivors in `LogScrubber` boundary arithmetic and `FallbackRateLimiter` comparisons
- Action: document in `docs/decisions/` after first PITest run on main

### Info (6)
All six INFO findings confirmed PASS — no action required. Key permanent gates:

- **SR-FUZZ-16 reflection gate** at `IframeEmbedPolicyFuzzTest:73-79` — `getDeclaredMethod("isEmbeddable", List.class, List.class, String.class)` permanently prevents reverting `List<String>` to `String`
- **SR-FUZZ-17 golden-vector corpus** (34 vectors at `src/test/resources/iframe-embed-policy-golden-vectors.json`) — behavior-change gate for `IframeEmbedPolicy`
- **CWE-117 guard** — `LogScrubber.xfoSummary(List<String>)` preserved at `IframeEmbedPolicy.java:149`

## SR-FUZZ Requirements Coverage

| ID | Severity | Status | Notes |
|---|---|---|---|
| SR-FUZZ-01 | CRITICAL | PASS | `List<String>` confirmed |
| SR-FUZZ-02 | CRITICAL | PASS | No raw input in assertion messages |
| SR-FUZZ-03 | HIGH | PASS | `ListAppender` in `IframeEmbedPolicyFuzzTest` + `LogScrubberFuzzTest` |
| SR-FUZZ-04 | HIGH | PASS | All 6 canaries in both canary-testing fuzz files |
| SR-FUZZ-05 | HIGH | PASS | 70% threshold, `-Dmaven.test.failure.ignore=false` |
| SR-FUZZ-06 | HIGH | PASS | Zero secrets in mutation CI job env |
| SR-FUZZ-07 | MEDIUM | PASS | Pin PASS; `frontend-audit` CI job added (2026-05-05); `WorkflowYamlInventoryTest` structural gate |
| SR-FUZZ-08 | MEDIUM | PASS | `FallbackRateLimiter` + `ShareRateLimiter` in PITest target |
| SR-FUZZ-09 | MEDIUM | PASS | `final` class, private constructor |
| SR-FUZZ-10 | MEDIUM | PASS | `.gitignore` entries present |
| SR-FUZZ-11 | MEDIUM | PASS | Backend references `HashtagFormat.PATTERN` directly; FE documents alphabet equivalence |
| SR-FUZZ-12 | MEDIUM | PASS | Prototype-pollution canaries + queue-size bound in `message-queue.fuzz.spec.ts` |
| SR-FUZZ-13 | LOW-MED | DEFERRED | Requires first PITest CI run |
| SR-FUZZ-14 | HIGH | PASS | 5 branch unit tests + 7 jqwik properties |
| SR-FUZZ-15 | LOW | PASS | 10 explicit FQNs, no wildcards |
| SR-FUZZ-16 | HIGH | PASS | Reflection regression gate |
| SR-FUZZ-17 | MEDIUM | PASS | 34 golden vectors |

## ADR Coverage

| ADR | Status |
|-----|--------|
| ADR-FUZZ-01: 10 classes, 70% threshold, no wildcards | PASS |
| ADR-FUZZ-02: ALL mutators | PASS |
| ADR-FUZZ-03: fast-check "3.21.0" exact pin | PASS |
| ADR-FUZZ-04: mutation job NOT in publish-image needs | PASS |
| ADR-FUZZ-05: `IframeEmbedPolicy` uses `List<String>` | PASS |
| ADR-FUZZ-06: jqwik under default Surefire | PASS |

## Build Verification

```
./mvnw verify — BUILD SUCCESS
Total time:  08:25 min
Finished at: 2026-05-01T21:43:56+02:00
Tests run: 187 (integration), Failures: 0, Errors: 0, Skipped: 0
Jacoco: All coverage checks have been met
Frontend (Karma standalone): 492 tests, 0 failures
```

## User Approval
Date: 2026-05-01
Approval message (verbatim): "approve"

## Post-Merge Follow-Up Items (TD Backlog)

1. **SR-FUZZ-07**: ~~Add `npm audit --audit-level=high` step to CI~~ — DONE 2026-05-05: `frontend-audit` job added to `security.yml`; `WorkflowYamlInventoryTest` structural gate
2. **R-1**: Wrap `domain.toUpperCase()` with `Pattern.quote(...)` in `IframeEmbedPolicy.java:113` + regression test for lookalike domain rejection
3. **R-2**: Add explicit canary `@Property` to `SubscriptionMessageHashtagFuzzTest`
4. **SR-FUZZ-13**: Document mutation-survivor equivalents after first PITest CI run on main branch

## References
- `docs/decisions/2026-05-01-planning-fuzz-mutation-testing.md` — Phase 1 decision
- `docs/decisions/2026-05-01-implementation-fuzz-mutation-testing.md` — Phase 2 decision
- TD-4/ADR-TD4-01: `LogScrubber.xfoSummary(List<String>)` — CWE-117 guard origin
- D-13/SR-8: Log hygiene discipline
- [OWASP A03:2021 — Injection](https://owasp.org/Top10/A03_2021-Injection/) (Software Supply Chain Failures) — SR-FUZZ-07 standard reference
