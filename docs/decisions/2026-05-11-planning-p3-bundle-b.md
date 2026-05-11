# Decision Record: P3 Backlog Bundle B — Phase 1 Planning

Date: 2026-05-11
Phase: Planning
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: **Approved**

## Summary

P3 Bundle B closes the two items deferred from Bundle A:

1. **Lane A — TD-P3A-DOMAIN-FIX**: `DomainSafetyValidator.java:98` concatenates the raw
   `trimmed` domain value into a Jakarta Validation constraint-violation template string.
   The fix replaces the concatenation with a **static literal** — the raw value is removed
   from the template entirely (ADR-P3B-4).

2. **Lane B — GlacierCookieProperties migration**: Seven `@Value("${glacier.cookie.secure:true}")`
   injection sites are migrated atomically to a new `@ConfigurationProperties` bean named
   `GlacierCookieProperties` with `prefix = "glacier.cookie"` and a `@NotNull Boolean secure`
   field (ADR-P3B-1, ADR-P3B-2, ADR-P3B-3).

## Key Decisions

### ADR-P3B-1: GlacierCookieProperties bean

**Decision**: Introduce `GlacierCookieProperties` as:
```java
@Component
@ConfigurationProperties(prefix = "glacier.cookie")
@Validated
public class GlacierCookieProperties {
    @NotNull(message = "glacier.cookie.secure must be set explicitly — see ADR-P3A-8")
    private Boolean secure;
    // getter + setter
}
```
**Rationale**:
- Field-name principle: the bean owns ONE field (`secure`) which maps to `glacier.cookie.secure`.
  The name follows the field, not a conceptual category.
- Avoids the SR-9/D-14 verbal trap: `GlacierDevmodeProperties` was rejected because it invites
  comments/JavaDocs containing `"glacier.devmode"` in `webservice/` packages, tripping
  `CodebaseConstraintTest.glacierDevmode_notReferencedOutsideMastodon()`.
- Prefix `glacier.cookie` is field-key-disjoint from `GlacierCoreProperties(prefix="glacier")`
  (which owns only `glacier.domain`). ADR-P3B-5 enforces this structurally.
- Future cookie-related fields (`sameSite`, `maxAge`, `domain`) cohere naturally under this bean.

**Alternatives considered**:
- `GlacierDevmodeProperties` — rejected: D-14 verbal trap; misleads future readers into adding
  a `devmode` field.
- Shared prefix `glacier` with `GlacierCoreProperties` — rejected: two-bean-one-prefix creates
  silent field placement confusion (T-P3B-09).
- Keep `@Value` — rejected: primitive `boolean` silent-defaults to `false` on missing property
  (CWE-1188, OWASP A02:2021); `@NotNull` fails fast.

**Source**: secure-feature-planner Round 1, ddd-tdd-architect Round 2 (naming + prefix finalized
with agreement from both agents)

### ADR-P3B-2: Atomic migration — one commit, no phased rollout

**Decision**: All seven `@Value("${glacier.cookie.secure:true}")` consumers switch to
`GlacierCookieProperties` in a SINGLE commit. The old ratchet test
(`CookieSecureSingleSourceOfTruthStructureTest`) is deleted and the new bidirectional test
(`CookieSecureBeanWiringStructureTest`) is added in the same commit.

**Seven consumer sites**:
1. `de.seism0saurus.glacier.InformationController:97`
2. `de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration:65`
3. `de.seism0saurus.glacier.CsrfTokenCookieFactory:37`
4. `de.seism0saurus.glacier.share.web.ShareViewerCookieFactory:42`
5. `de.seism0saurus.glacier.share.web.ShareCsrfGuard:54`
6. `de.seism0saurus.glacier.share.web.ImageProxyHmacSecretValidator:48`
7. `de.seism0saurus.glacier.StartupSanityChecker:59`

**Rationale**: Any window where some consumers see `@Value` and others see the bean creates a
silent-divergence security misconfiguration (T-P3B-04, OWASP A05:2021). Atomic migration is
the only safe approach.

**Source**: ddd-tdd-architect Round 1, confirmed secure-feature-planner Round 1 (T-P3B-04)

### ADR-P3B-3: `Boolean` (boxed) + `@NotNull`; `Boolean.TRUE.equals(...)` consumer idiom

**Decision**: The `secure` field is `@NotNull Boolean` (boxed). Consumers unbox once via
`Boolean.TRUE.equals(props.getSecure())` — null-safe in test mocking paths, production
protected by `@NotNull` enforced at startup.

**Primitive `boolean` is explicitly forbidden.**

**Rationale**: Primitive `boolean` silently defaults to `false` on missing `glacier.cookie.secure`
property in some Spring Boot binding paths. False = HTTP transport, no Secure cookie flag =
session hijack possible on plain-HTTP path. `Boolean + @NotNull` makes missing property a
startup failure (CWE-1188, ASVS V14.1.1).

`Boolean.TRUE.equals(props.getSecure())` is the idiomatic null-safe unboxing. Forbidding
`.booleanValue()` unboxing (enforced by structural test B4a) prevents NPE in test contexts
where the bean is mocked and `getSecure()` returns null.

**Source**: ddd-tdd-architect Round 1 (convention from ADR-P3A-8), secure-feature-planner
Round 1 (SR-P3B-03), secure-tdd-implementer Round 2 (B4a structural test)

### ADR-P3B-4: Constraint-violation templates must be static literal-only

**Decision**: All `ConstraintValidator` implementations that call
`context.buildConstraintViolationWithTemplate(...)` must pass **only string literals** (or
literal-to-literal concatenation). Raw value concatenation is forbidden. `LogScrubber` calls
inside a template argument are also forbidden.

The `DomainSafetyValidator.java:96-100` fix removes the concatenation entirely:

```java
// BEFORE (line 98 — raw value concatenated):
context.buildConstraintViolationWithTemplate(
        "glacier.domain must not be a loopback or any-address literal "
        + "(CORS/CSP misconfiguration risk): '" + trimmed + "' is prohibited "
        + "— Sec-12, OWASP A05:2021")
        .addConstraintViolation();

// AFTER (static literal only):
context.buildConstraintViolationWithTemplate(
        "glacier.domain must not be a loopback or any-address literal "
        + "(CORS/CSP misconfiguration risk) — Sec-12, OWASP A05:2021")
        .addConstraintViolation();
```

**Framing correction** (security-planner Round 1 → Round 2 architect agreement): The fix is
**defense-in-depth**, not a live-CVE patch. `GlacierBindHandler.ScrubbingBindHandler.onFailure`
BRANCH 1 catches `BindValidationException` and throws `ScrubbedBindException(staticMsg, null)`,
dropping the entire violation chain including all `defaultMessage` strings. So today's live path
through `@ConfigurationProperties` binding does NOT leak the line-98 template string. However:
- Programmatic `validator.validate(bean)` calls bypass the handler.
- REST `@Valid` body binding surfaces `ConstraintViolation.getMessage()` via `MethodArgumentNotValidException`.
- Future AOP method validation, JMX, AOT introspection are not filtered.
The validator must be safe on its own regardless of call site.

**Why static-literal is preferred over `LogScrubber.forErrorMessage(trimmed)`**: The static form
closes the Jakarta EL injection class entirely (EL template evaluation never has access to the
raw value), is trivially auditable by grep, and is enforced structurally by test A4. The scrubbed
form would still carry a value-derived token; the static form carries no value-derived data at all.

**Structural enforcement**: test A4 (`ConstraintViolationMessageStaticOnlyStructureTest`) scans
all `*Validator.java` in `src/main/java`, fails if any `buildConstraintViolationWithTemplate(`
argument contains identifier concatenation.

**Source**: ddd-tdd-architect Round 2 (tightened from planner Round 1 SR-P3B-01)

### ADR-P3B-5: Field-key disjointness across `@ConfigurationProperties` beans

**Decision**: Two `@ConfigurationProperties` beans may have parent/child prefix relationships
(`glacier` and `glacier.cookie`), but their **owned key sets** — computed as
`{prefix + "." + kebab-case(fieldName) : field in bean}` — must be globally disjoint.

Enforced by mandatory structural test B5 (`GlacierConfigurationPropertiesFieldDisjointnessTest`).

**Note on existing beans**: `ShareLinkLifetimePolicy` and `ShareLinkCapPolicy` both use
`prefix = "glacier.share"`. B5 computes fully-qualified key sets to catch real collisions while
allowing legitimate composition (they own different fields within the `glacier.share.*` namespace).

**Source**: secure-feature-planner Round 1 (T-P3B-09), ddd-tdd-architect Round 2 (reshaped
planner's optional B5 into mandatory structural test)

### ADR-P3B-6: Unicode-control coverage deferred to separate ticket

**Decision**: Bundle B does NOT extend `DomainSafetyValidator.isValid` to cover U+202E
(RTL override), U+200B (zero-width space), U+FEFF (BOM), U+2028 (LS), U+2029 (PS).
A separate ticket `TD-P3B-DOMAIN-UNICODE` owns that work.

Lane A commit message includes a forward-reference:
`Note: TD-P3B-DOMAIN-UNICODE will add codepoint-class checks for U+202E/U+200B/U+FEFF/
U+2028/U+2029 — these are out of scope for Bundle B, which targets the log-leak primitive.`

**Rationale**: Scope separation between "log-leak invariant" (Bundle B) and "input-acceptance
invariant" (separate). The fix needed for Unicode is a distinct regex/codepoint-class extension
with its own test matrix; mixing it into Lane A inflates Bundle B and blurs the invariant.

**Source**: secure-feature-planner Round 1 (T-P3B-02), ddd-tdd-architect Round 2 (CONFLICT
SR-P3B-09 resolution — see below)

## Security Requirements

| SR | Description | Addressed by | Criticality |
|---|---|---|---|
| SR-P3B-01 | DomainSafetyValidator.java:98 static-literal fix | Lane A, ADR-P3B-4 | CRITICAL (defense-in-depth) |
| SR-P3B-01a | A4 zero-helper-allowlist: only string-literal args to `buildConstraintViolationWithTemplate` | A4 structural test | HIGH |
| SR-P3B-02 | ConstraintViolationMessageStaticOnlyStructureTest scanning all *Validator.java | A4 | HIGH |
| SR-P3B-02a | A1c: 10 KB input proves static message length < 300 chars (regression canary) | A1c test | LOW |
| SR-P3B-03 | Boolean + @NotNull — primitive boolean forbidden | ADR-P3B-3, GlacierCookieProperties | CRITICAL |
| SR-P3B-04 | Consumer null-safety via Boolean.TRUE.equals idiom | ADR-P3B-3 + consumer pattern | MEDIUM |
| SR-P3B-04a | B4a structural test forbids `.booleanValue()` unboxing | B4a | MEDIUM |
| SR-P3B-05 | CookieSecureBeanWiringStructureTest bidirectional (positive + negative) | B4 | CRITICAL |
| SR-P3B-05a | B4 also asserts zero "glacier.cookie.secure" string literals outside bean | B4 | HIGH |
| SR-P3B-06 | Both GlacierCoreProperties + GlacierCookieProperties carry JavaDoc naming owned/not-owned keys | B5 (disjointness) + JavaDoc | MEDIUM |
| SR-P3B-06a | application*.yml sets glacier.cookie.secure explicitly; breaking change documented | Phase 2 audit + commit | MEDIUM |
| SR-P3B-07 | BindFailureValueScrubbingIT extended: missing/empty/non-boolean glacier.cookie.secure | B2 (Lane B) | HIGH |
| SR-P3B-08 | A1 assertions: getMessage(), getMessageTemplate() (regression), bve.toString(), FieldError.toString() | A1 | HIGH |
| SR-P3B-09 | Lane A canary inputs: prohibited literals + 10 KB boundedness (Unicode inputs deferred) | A1, A1c | MEDIUM |
| SR-P3B-10 | Phase 2 commit message names all 3 validators with buildConstraintViolationWithTemplate | A4 + commit | LOW |
| SR-P3B-11 | Phase 3 git-grep ACs: 0 @Value hits, 8 GlacierCookieProperties hits, 0 glacier.devmode in webservice/util | Phase 3 | HIGH |
| SR-P3B-12 | StartupSanityChecker mixed-state JavaDoc marker | B6 | LOW |

## Test Plan

### Lane A — Domain validator static-message hardening

| Test | Type | Description |
|---|---|---|
| **A1** `DomainSafetyValidatorStaticMessageTest` | Unit | 6 prohibited-literal inputs; asserts `getMessage()` = static literal (no raw value); asserts `bve.toString()` + `FieldError.toString()` don't contain raw value; one `getMessageTemplate()` regression assertion with comment |
| **A1b** `DomainSafetyValidatorProgrammaticLeakTest` | Unit | `validator.validate(bean)` directly — bypasses GlacierBindHandler; asserts no constraint violation message contains raw value |
| **A1c** `DomainSafetyValidatorLargeInputBoundednessTest` | Unit | 10 KB input; asserts static message length < 300 chars (regression canary that fix stayed static) |
| **A4** `ConstraintViolationMessageStaticOnlyStructureTest` | Unit (structural) | Scans `src/main/java/**/*Validator.java`; fails if any `buildConstraintViolationWithTemplate(` argument contains identifier concatenation |

### Lane B — GlacierCookieProperties atomic migration

| Test | Type | Description |
|---|---|---|
| **B1** `GlacierCookiePropertiesBindIT` | Integration | Boots Spring slice with `glacier.cookie.secure=true/false`; asserts binding correct |
| **B2** `BindFailureValueScrubbingIT @Nested CookieSecureBooleanBindFailures` | Integration | Three cases: missing property / empty / `=yes` (non-boolean); asserts ScrubbedBindException with no raw value |
| **B3** `GlacierCookiePropertiesNotNullValidationTest` | Unit | `Validator.validate(bean)` with `secure=null` produces `@NotNull` violation |
| **B4** `CookieSecureBeanWiringStructureTest` | Unit (structural) | Bidirectional: all 7 consumers reference `GlacierCookieProperties` AND zero `@Value("${glacier.cookie.secure"` remain |
| **B4a** `CookieSecureConsumerUnboxingStructureTest` | Unit (structural) | Asserts none of the 7 consumer files contains `.getSecure().booleanValue()` unboxing |
| **B5** `GlacierConfigurationPropertiesFieldDisjointnessTest` | Unit (structural) | Computes owned key sets for all `@ConfigurationProperties` beans; asserts disjointness |
| **B6** `StartupSanityChecker` JavaDoc | Documentation | Class-level comment: "Mixed-state constructor — glacier.cookie.secure uses GlacierCookieProperties (ADR-P3B-1); glacier.devmode and mastodon.https remain on @Value (SR-9/D-14 fence)" |

## Implementation Lane Partition

**Lane A (secure-tdd-implementer) — 3 commits:**
- Commit A1: `DomainSafetyValidatorStaticMessageTest` (RED), `DomainSafetyValidatorProgrammaticLeakTest` (RED), `DomainSafetyValidatorLargeInputBoundednessTest` (RED), `ConstraintViolationMessageStaticOnlyStructureTest` (RED)
- Commit A2: Fix `DomainSafetyValidator.java:96-100` — remove `+ "'" + trimmed + "' is prohibited "`. All 4 test classes turn GREEN.
- Commit A3: `DomainSafetyValidator.java` JavaDoc update (ADR-P3B-4 note + forward-reference to `TD-P3B-DOMAIN-UNICODE`)

Files owned by Lane A:
- `src/main/java/de/seism0saurus/glacier/DomainSafetyValidator.java` (fix only)
- `src/test/java/de/seism0saurus/glacier/DomainSafetyValidatorStaticMessageTest.java` (new)
- `src/test/java/de/seism0saurus/glacier/DomainSafetyValidatorProgrammaticLeakTest.java` (new)
- `src/test/java/de/seism0saurus/glacier/DomainSafetyValidatorLargeInputBoundednessTest.java` (new)
- `src/test/java/de/seism0saurus/glacier/ConstraintViolationMessageStaticOnlyStructureTest.java` (new)

**Lane B (tdd-ddd-implementer) — 1 atomic commit + supporting commits:**
- Commit B-RED: Add `GlacierCookiePropertiesBindIT` (RED), `GlacierCookiePropertiesNotNullValidationTest` (RED), `CookieSecureBeanWiringStructureTest` (RED), `CookieSecureConsumerUnboxingStructureTest` (RED), `GlacierConfigurationPropertiesFieldDisjointnessTest` (RED)
- Commit B-GREEN (atomic): Create `GlacierCookieProperties.java`; delete `CookieSecureSingleSourceOfTruthStructureTest.java`; migrate all 7 consumer files; extend `BindFailureValueScrubbingIT`; add `StartupSanityChecker` mixed-state comment (B6). All RED tests turn GREEN.

Files owned by Lane B:
- `src/main/java/de/seism0saurus/glacier/GlacierCookieProperties.java` (new)
- `src/main/java/de/seism0saurus/glacier/InformationController.java` (wiring change)
- `src/main/java/de/seism0saurus/glacier/webservice/messaging/WebSocketConfiguration.java` (wiring change)
- `src/main/java/de/seism0saurus/glacier/CsrfTokenCookieFactory.java` (wiring change)
- `src/main/java/de/seism0saurus/glacier/share/web/ShareViewerCookieFactory.java` (wiring change)
- `src/main/java/de/seism0saurus/glacier/share/web/ShareCsrfGuard.java` (wiring change)
- `src/main/java/de/seism0saurus/glacier/share/web/ImageProxyHmacSecretValidator.java` (wiring change)
- `src/main/java/de/seism0saurus/glacier/StartupSanityChecker.java` (wiring change + B6 JavaDoc)
- `src/test/java/de/seism0saurus/glacier/GlacierCookiePropertiesBindIT.java` (new)
- `src/test/java/de/seism0saurus/glacier/GlacierCookiePropertiesNotNullValidationTest.java` (new)
- `src/test/java/de/seism0saurus/glacier/BindFailureValueScrubbingIT.java` (extend)
- `src/test/java/de/seism0saurus/glacier/CookieSecureBeanWiringStructureTest.java` (new — replaces CookieSecureSingleSourceOfTruthStructureTest)
- `src/test/java/de/seism0saurus/glacier/CookieSecureSingleSourceOfTruthStructureTest.java` (DELETE)
- `src/test/java/de/seism0saurus/glacier/CookieSecureConsumerUnboxingStructureTest.java` (new)
- `src/test/java/de/seism0saurus/glacier/GlacierConfigurationPropertiesFieldDisjointnessTest.java` (new)

## Resolved Conflicts

### Conflict: SR-P3B-09 Unicode canary inputs in Lane A

**ddd-tdd-architect (Round 2) position**:
U+202E (`localhost‮`) and U+FEFF (`﻿localhost`) inputs do not reach line 98 because
`PROHIBITED_LITERALS.contains(lower)` requires exact match — the Unicode suffix/prefix
prevents the match. Including them in Lane A's test would be misleading (always green,
provides no signal about the code path under fix, makes T-P3B-02 look covered when it isn't).

**secure-feature-planner (Round 1) position**:
Include these inputs as canary cases for Lane A's scrubbing behavior.

**Resolution (2026-05-11)**: Accept architect's position. Only the 10 KB boundedness case
stays in A1c. U+202E/U+FEFF inputs deferred to `TD-P3B-DOMAIN-UNICODE`. Lane A commit
message carries forward-reference note.

## Open Risks (accepted, do not block)

| Risk | Severity | Rationale |
|---|---|---|
| **TD-P3B-DOMAIN-UNICODE**: U+202E/U+200B/U+FEFF/U+2028/U+2029 not rejected by `DomainSafetyValidator` | Low | Operator-controlled config; low threat surface; tracked as a separate deferred ticket |
| **StartupSanityChecker mixed-state constructor** | Low | Unavoidable — `glacier.devmode` protected by SR-9/D-14 fence; documented via B6 |
| **`glacier.cookie.secure` now @NotNull — breaking change** | Medium | Deployments omitting this key will fail to start after Bundle B. Phase 2 must audit and update all test application properties. Fail-fast is the correct security posture. |

## User Approval

Date: 2026-05-11
Approval message (verbatim): "approve"

## References

- Bundle A acceptance: `docs/decisions/2026-05-11-acceptance-p3-backlog-bundle.md`
- Bundle A implementation: `docs/decisions/2026-05-11-implementation-p3-backlog-bundle.md`
- Quality Review acceptance: `docs/decisions/2026-05-08-acceptance-quality-review.md`
- ADR index: `docs/decisions/README.md`
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [CWE-1188: Insecure Default Initialization](https://cwe.mitre.org/data/definitions/1188.html)
- [OWASP A02:2021 Cryptographic Failures](https://owasp.org/Top10/A02_2021-Cryptographic_Failures/)
- [OWASP A05:2021 Security Misconfiguration](https://owasp.org/Top10/A05_2021-Security_Misconfiguration/)
- [ASVS V7.3.1 (L1)](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json)
- [ASVS V14.1.1 (L1)](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json)
- [Proactive C5: Secure Defaults](https://top10proactive.owasp.org/)
