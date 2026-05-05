# Decision Record: IframeEmbedPolicy Pattern.quote() Regex Bypass — Implementation

Date: 2026-05-05
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2 review), secure-tdd-implementer (Lane B + Round 2 fix)
Status: Accepted

## Summary

8 new unit tests (+8 delta) implement the IframeEmbedPolicy Pattern.quote() bypass fix across two
parallel lanes plus one Round 2 fix cycle:
1. **Lane A** — 5 behavioral regression tests (T-PQ-A..E) confirming bypass class and over-rejection.
2. **Lane B** — production fix (`Pattern.quote()` + blank-domain guard + AUDIT logger), T-PQ-F
   (blank-domain), T-PQ-G (AUDIT logger event), T-PQ-H (structural gate, new file).
3. **Round 2 fix** — AUDIT logger routing corrected; T-PQ-H gate hardened to count-match approach.

## Files Changed

| File | Lane/Phase | Change |
|------|-----------|--------|
| `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java` | B + Round 2 | `Pattern.quote(domain.toUpperCase(Locale.ROOT))` at line 140; blank-domain guard; `AUDIT.info("iframe.embed.rejected reason=domain_mismatch")`; `LOGGER.warn(...)` retained; `AUDIT` logger field added; `import java.util.regex.Pattern` added |
| `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyTest.java` | A + B + Round 2 | T-PQ-A, B, C, D, E (Lane A); T-PQ-F, G (Lane B); T-PQ-G updated to capture `"AUDIT"` logger at `Level.INFO` (Round 2) |
| `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyRegexInterpolationGateTest.java` | B + user-requested | **Created** — T-PQ-H structural gate; count-match assertion hardened per user approval condition |

## Production Fix Detail

### Pattern.quote() — IframeEmbedPolicy.java line 140

Before (vulnerable):
```java
+ domain.toUpperCase(Locale.ROOT)
```

After (safe):
```java
+ Pattern.quote(domain.toUpperCase(Locale.ROOT))
```

`\Q...\E` quoting (Java's Pattern.quote output) causes the regex engine to treat every character
in `domain` as a literal, closing the `.`-as-wildcard bypass (CWE-625).

### Blank-domain guard — function entry

```java
if (domain == null || domain.isBlank()) {
    LOGGER.warn("Rejecting embed check: configured domain is blank — failing closed");
    return false;
}
```

`Pattern.quote("")` returns `\Q\E` — an empty quoted literal that matches the empty string.
Without the guard, an operator-misconfigured empty `glacier.domain` would allow any
`frame-ancestors` directive that contains `https://` or similar short degenerate tokens
to pass `isEmbeddable`. Fail-closed is the correct security default.

### AUDIT logger — dual-channel emission

```java
private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
```
```java
LOGGER.warn("iframe.embed.rejected reason=domain_mismatch");   // operator alerting
AUDIT.info("iframe.embed.rejected reason=domain_mismatch");    // SIEM/aggregator channel
```

Dual-channel per `glacier-structured-logging-logback` skill: `LOGGER.warn` for operator-facing
alerting; `AUDIT.info` for log aggregators that filter on `"AUDIT"` logger name. The static
`reason=domain_mismatch` message contains no raw peer-controlled values (CWE-117 defence).

## Test Results

| Layer | Before | After | Delta |
|-------|--------|-------|-------|
| Unit (Surefire) | 1,261 | **1,269** | +8 |
| Integration (Failsafe) | 298 | 298 | 0 |
| Jacoco | Met | Met | — |
| BUILD | SUCCESS | SUCCESS | — |

+8 unit tests:
- 5 — Lane A behavioral bypass + exact-match tests (T-PQ-A..E) in `IframeEmbedPolicyTest`
- 2 — Lane B blank-domain + AUDIT-logger tests (T-PQ-F, T-PQ-G) in `IframeEmbedPolicyTest`
- 1 — Lane B structural gate (T-PQ-H) in `IframeEmbedPolicyRegexInterpolationGateTest` (new file)

## Security Requirements Delivered

| SR | Status | Key Evidence |
|----|--------|-------------|
| SR-PQ-01 | ✅ | `Pattern.quote(domain.toUpperCase(Locale.ROOT))` at `IframeEmbedPolicy.java:140` |
| SR-PQ-02 | ✅ | `toUpperCase(Locale.ROOT)` precedes `Pattern.quote()` |
| SR-PQ-03 | ✅ | Bounded audit — 5 other sites all SAFE (documented in commit `0bd6371`) |
| SR-PQ-04 | ✅ | T-PQ-A: dot-substitution → `false`; RED commit `29a8b38`, GREEN commit merged `2163cdd` |
| SR-PQ-05 | ✅ | T-PQ-B: underscore substitution → `false`; same commit cadence |
| SR-PQ-06 | ✅ | T-PQ-C: `+` metacharacter → `false`; same commit cadence |
| SR-PQ-07 | ✅ | T-PQ-H: count-match gate; dedicated file `IframeEmbedPolicyRegexInterpolationGateTest.java` |
| SR-PQ-08 | ✅ | T-PQ-D: multi-token frame-ancestors lookalike → `false` |
| SR-PQ-09 | ✅ | T-PQ-E: legitimate exact domain → `true` (GREEN-only) |
| SR-PQ-10R2 | ✅ | T-PQ-G captures `"AUDIT"` logger at `Level.INFO`; `reason=domain_mismatch` present; raw domain absent |
| SR-PQ-11 | ✅ | `import java.util.regex.Pattern;` at `IframeEmbedPolicy.java:10` |
| SR-PQ-12 | ✅ | T-PQ-F: blank/null domain → `false` (fail-closed); guard at function entry |

## Round 2 Cross-Review Findings

| Item | Disposition |
|------|-------------|
| T-PQ-G logger mismatch (A1) | Fixed — migrated from class LOGGER to `"AUDIT"` channel; T-PQ-G updated to capture `Level.INFO`; committed `0bd6371` |
| T-PQ-H CRLF/inline-reformatting fragility (A2) | Fixed (user-requested at approval gate) — replaced `doesNotContain` with count-match assertion; committed `8bc0176` |
| SR-PQ-03 audit scope accepted (infra note Q1) | No additional sites; bounded grep complete |

## Commit History

| SHA | Description |
|-----|-------------|
| `29a8b38` | Lane A — T-PQ-A..E behavioral tests (RED phase) |
| `e742ad6` | Lane B — T-PQ-F/G blank-domain + AUDIT-logger tests (RED phase) |
| `6e13630` | Lane B — Pattern.quote() fix + blank-domain guard + AUDIT log migration (GREEN phase) |
| `0b53fed` | Lane B — T-PQ-H structural gate (initial, always GREEN) |
| `2163cdd` | Merge Lane A + Lane B into main (conflict resolved in IframeEmbedPolicyTest.java) |
| `0bd6371` | Round 2 fix — AUDIT logger routing + T-PQ-G capture corrected; planning doc added |
| `8bc0176` | T-PQ-H count-match hardening (user-requested before Phase 3) |

## Deviations from Phase 1 Plan

None substantive. The AUDIT logger fix was a Round 2 finding, not a plan deviation — the planning
doc specified SR-PQ-10R2 AUDIT logger explicitly; the initial implementation omitted it (correctly
caught by Round 2 cross-review). T-PQ-H gate hardening was requested by the user at the Phase 2
approval gate — implemented before proceeding to Phase 3 per instruction.

## User Approval

Date: 2026-05-05
Approval message (verbatim): "approve but implement T-PQ-H fragility now"

## Open Risks

| ID | Risk | Mitigation |
|----|------|-----------|
| OR-PQ-01 | `@ConfigurationProperties @NotBlank` startup validation deferred (`SR-PQ-12-FU`) | In-function blank-domain guard is fail-closed |
| OR-PQ-02 | T-PQ-H structural gate bounded to `IframeEmbedPolicy.java` only | SR-PQ-03 one-shot audit confirms no other interpolation sites at time of fix |

## References

- `docs/decisions/2026-05-05-planning-iframe-embed-pattern-quote.md` — Phase 1 planning
- `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java` (fix site)
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyTest.java` (T-PQ-A..G)
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyRegexInterpolationGateTest.java` (T-PQ-H)
- CWE-1287 (Improper Validation of Specified Type of Input)
- CWE-625 (Permissive Regular Expression)
- CWE-117 (Log Injection — mitigated by static `reason=` message)
- OWASP A04:2021 (Insecure Design)
- OWASP A03:2021 (Injection)
- ASVS 5.0.0 V5.3.6 (escape metacharacters before regex use)
- `glacier-structured-logging-logback` skill (AUDIT logger convention)
