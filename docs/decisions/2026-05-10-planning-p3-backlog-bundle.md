# Decision Record: P3 Backlog Bundle A — Phase 1 Planning

Date: 2026-05-10
Phase: Planning
Agents: ddd-tdd-architect (R1 + R2), secure-feature-planner (R1 + R2)
Status: Accepted

## Summary

Six deferred P3 items from the 2026-05-08 Comprehensive Quality Review are bundled into a single
pipeline run. Five items are documentation/CI/value-object plumbing with minimal behavioral impact;
one item (P3-10) introduces Spring `@ConfigurationProperties` validation for previously raw `@Value`
injections. All changes are pure refactors — runtime behavior is byte-identical; the change is a
better failure shape (startup `BindException` instead of silent NPE) and stronger types at seams.

## Items in scope

| Item | Description |
|------|-------------|
| P3-10 | Two new `@Validated @ConfigurationProperties` beans (`MastodonProperties`, `GlacierOperatorProperties`) replace raw `@Value` in `MastodonConfiguration`, `InformationController`, `SubscriptionManagerImpl` |
| P3-01 | `MastodonShortHandle` immutable record + `MastodonHandleFactory @Bean` + `EventTypeMapping` static helper |
| P3-07 | `.github/workflows/codeql.yml` line 75: `{{ hashFiles(...) }}` → `${{ hashFiles(...) }}` (missing `$`) |
| P3-08 | Javadoc `ressources` → `resources` typo fix in 11 Java source files |
| P3-09 | User-facing operational-modes section added to `README.md` |
| P3-03 | `docs/decisions/README.md` ADR index (hand-curated + sentinel test) |
| P3-04 | `README.md` versioning-policy paragraph |

## Items explicitly deferred (not in this bundle)

| Item | Reason |
|------|--------|
| `GlacierDevmodeProperties` (originally P3-10C) | `glacier.cookie.secure` must be migrated atomically across all 5+ consumers; partial wiring is forbidden per ADR-P3A-1 / SR-P3A-05. Deferred to Bundle B. |
| GdprComponent body i18n (I18N-F-04) | Requires ux-ui-designer + frontend-designer; significant scope. Separate pipeline. |
| P3-05 Share-link SQLite persistence | Significant feature. Separate pipeline. |
| P3-06 Cookie-read rate-limit | UUID-issuance already rate-limited (120/min); read path has no side effects. Low priority. |
| Sec-02/P1-09 Bigbone SHA256 pin | BLOCKED — upstream bigbone has not cut a stable release. |

## Key Decisions

### ADR-P3A-1: Two `@ConfigurationProperties` beans by ownership/lifecycle

**Decision**: Create `MastodonProperties` (prefix `mastodon`) and `GlacierOperatorProperties` (prefix
`glacier.operator`). `GlacierDevmodeProperties` is deferred (see above). Both follow the
`@Component + @ConfigurationProperties + @Validated` pattern established by `GlacierCoreProperties`.

**Rationale**: Different change cadences — Mastodon connection settings vs. operator legal-notice
fields. One bean per ownership domain; no coupling between unrelated concerns.

**Alternatives considered**: One large `GlacierProperties` bean (rejected — forces unrelated
test classes to mock unrelated fields); adding fields to `GlacierCoreProperties` (rejected — violates
SRP of a class named *Core*).

**Source**: ddd-tdd-architect R1 + R2.

### ADR-P3A-2: `MastodonShortHandle` via factory @Bean, not Spring Converter

**Decision**: `MastodonProperties#handle` binds as `String`. A `@Component MastodonHandleFactory`
creates the `MastodonShortHandle` record bean. Downstream consumers inject the record directly.

**Rationale**: Custom `Converter<String, MastodonShortHandle>` is a non-standard pathway; a factory
`@Component` is idiomatic and matches existing `ShareImageProxyUrlBuilder` precedent.

**Source**: ddd-tdd-architect R1, ADR-P3A-2.

### ADR-P3A-3: Operator property keys migrate to nested `glacier.operator.*` form

**Decision**: `glacier.operatorName` → `glacier.operator.name`, `glacier.operatorMail` →
`glacier.operator.mail`, etc. (all 8 fields). Env-var names (`MY_NAME`, `MY_MAIL`, …) are UNCHANGED.

**Rationale**: `@ConfigurationProperties(prefix="glacier")` would collide with `GlacierCoreProperties`.
Spring relaxed binding does not split camelCase into nested form without explicit configuration.

**Consequences**: Operators setting `-Dglacier.operatorName=...` JVM properties must rename. `MY_*`
env vars (the documented interface) are unaffected. `docs/ADMIN.md` gets a one-sentence note.

**Source**: ddd-tdd-architect R1, ADR-P3A-3.

### ADR-P3A-4: `EventTypeMapping` in `de.seism0saurus.glacier.eventtype` package

**Decision**: New neutral package `de.seism0saurus.glacier.eventtype` houses `EventTypeMapping`.
The three existing event-type vocabularies stay separate (ADR-F6-INFO-2-A honoured). `StompEventType`
gains `public` visibility so the helper can reference it.

**Rationale**: Putting the helper in any one of the three existing packages biases it toward that
vocabulary. A neutral package signals "single authority for all three."

**Source**: ddd-tdd-architect R1, ADR-P3A-4.

### ADR-P3A-5: ADR index is hand-curated + `AdrIndexCompletenessSentinelTest`

**Decision**: `docs/decisions/README.md` is a manually-authored Markdown table grouped by topic.
An `AdrIndexCompletenessSentinelTest` walks `docs/decisions/` and asserts every ADR file is
referenced in the index. Without the sentinel, the index goes stale within two features.

**Source**: secure-feature-planner R1, ddd-tdd-architect R2 (accepted), SR-P3A-22.

### ADR-P3A-6: 10 independent commits for independent revertibility

**Decision**: Each bundle item ships in at least one separate commit. Infrastructure (validators,
BindHandler, SafeOperatorString) is front-loaded in commit 3 so that P3-10A and P3-10B have all
helpers available before any consumer is wired.

**Source**: ddd-tdd-architect R1, ADR-P3A-6.

### ADR-P3A-7: `GlacierBindHandler` scrubs framework-level `BindException.getRejectedValue()`

**Decision**: A `@Bean GlacierBindHandler` implements `org.springframework.boot.context.properties.bind.BindHandler`.
Its `onFailure(...)` intercepts and replaces `rejectedValue` with `LogScrubber.forErrorMessage(value)`
before the exception propagates. This is the ONLY way to prevent Spring's framework-level
`BindException.toString()` from echoing raw sensitive values (accessToken, handle) to stderr/SIEM.

**Rationale**: Even perfectly sanitised `ConstraintViolation` messages cannot prevent the
framework-level echo. This is a new scrubbing layer not present in any existing Glacier code.

**Source**: secure-feature-planner R1 (T-P3A-01, CRITICAL), ddd-tdd-architect R2 (accepted), ADR-P3A-7.

### ADR-P3A-8: Boxed `Boolean + @NotNull` for boolean `@ConfigurationProperties` fields

**Decision**: `mastodon.https` (and all future boolean `@ConfigurationProperties` fields) use
`Boolean` (boxed) with `@NotNull`, NOT `boolean` (primitive).

**Rationale**: Primitive `boolean` in `@ConfigurationProperties` silently defaults to `false` when
the env-var is absent. `@Value("${mastodon.https}")` (the original) throws on absent key; the
`@ConfigurationProperties` equivalent is `Boolean + @NotNull`. A silent `false` for `mastodon.https`
is a silent HTTP downgrade (OWASP A02:2021; ASVS V9.1.1 L1).

**Source**: secure-feature-planner R1 (CONFLICT #2), ddd-tdd-architect R2 (accepted), ADR-P3A-8.

### ADR-P3A-9: `@SafeOperatorString` composite annotation for all operator string fields

**Decision**: A new `@SafeOperatorString` composed annotation (backed by a `ConstraintValidator`)
rejects CRLF (`\r`, `\n`), null byte (`\0`), tab (`\t`), U+202E (RTL override), U+200B (ZWSP),
U+FEFF (BOM), U+2028 (line sep), U+2029 (paragraph sep). Applied to all 8 `GlacierOperatorProperties`
string fields (name, streetAndNumber, zipcode, city, country, phone, mail, website).

**Source**: secure-feature-planner R1 (SR-P3A-12), ddd-tdd-architect R2 (accepted), ADR-P3A-9.

### ADR-P3A-10: `DomainSafetyValidator.java:98` defect tracked as TD; separate validator for `mastodon.instance`

**Decision**: `DomainSafetyValidator.java:98` contains raw value concatenation in an error message
(`"...is prohibited: '" + trimmed + "'"` pattern). This is NOT fixed in this bundle. A separate
`MastodonInstanceValidator` is written for `mastodon.instance` validation; it does NOT delegate to
the existing `DomainSafetyValidator`. The defect is tracked as TD-P3A-DOMAIN-FIX for a follow-up PR.

**Source**: secure-feature-planner R1 (DomainSafetyValidator audit), ddd-tdd-architect R2 (accepted),
ADR-P3A-10.

## Security Requirements (summary)

Full list: SR-P3A-01..22 (see security_plan output).

Critical items:
- **SR-P3A-01**: ConstraintValidators MUST use `LogScrubber.forErrorMessage(rejectedValue)` (CRITICAL — T-P3A-01)
- **SR-P3A-03**: `mastodon.https` MUST preserve fail-on-missing via `Boolean + @NotNull`
- **SR-P3A-04**: Operator key migration MUST be atomic across production + test files
- **SR-P3A-05**: `glacier.cookie.secure` migration MUST be atomic; deferred to Bundle B
- **SR-P3A-06**: `MastodonShortHandle.parse()` rejection set MUST be strict superset of `getShortHandle`
- **SR-P3A-11**: `operatorWebsite` MUST reject `javascript:`, `data:`, `vbscript:`, `file:` URIs
- **SR-P3A-12**: All operator string fields MUST use `@SafeOperatorString`
- **SR-P3A-13**: `mastodon.handle` `@Pattern` MUST use `\A`/`\z` anchors, exclude controls

## Acceptance Criteria (Phase 3 gates)

24 ACs (AC-P3A-01..24). Key ACs:
- AC-P3A-01: All 5 `glacier.cookie.secure` consumers remain on `@Value` (GlacierDevmodeProperties deferred)
- AC-P3A-02: `mastodon.https` is `Boolean + @NotNull`; `MastodonPropertiesMissingHttpsIT` passes
- AC-P3A-03: `BindFailureValueScrubbingIT` proves `BindException` does not contain raw `mastodon.accessToken`
- AC-P3A-06: `MastodonShortHandleVsLegacyParityTest` proves strict superset
- AC-P3A-14: `OperatorPropertyKeyMigrationSentinelTest` passes (no camelCase operator key remains)
- AC-P3A-19: codeql.yml CI workflow log excerpt in acceptance doc (manual gate)

## Regex Specifications (from secure-feature-planner)

```
mastodon.handle:          \A@?[a-zA-Z0-9._-]{1,64}@[a-zA-Z0-9.-]{1,253}\z
mastodon.instance:        \A[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?(?::[1-9][0-9]{0,4})?\z
glacier.operator.website: \A(?:https?://)?[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?(?::[1-9][0-9]{0,4})?(?:/[!-~]{0,1024})?\z
glacier.operator.phone:   \A[+]?[0-9 ()/.-]{1,32}\z
glacier.operator.zipcode: \A[A-Za-z0-9 -]{1,16}\z
```

## Proposed Phase 2 Lane Partition

| Lane | Agent | Files / Concerns |
|------|-------|-----------------|
| Domain/Application | `tdd-ddd-implementer` | `MastodonShortHandle.java`, `MastodonHandleFactory.java`, `EventTypeMapping.java`, `StompEventType` visibility, `StompCallback` wiring, `SubscriptionManagerImpl` wiring, `InformationController` wiring (handle), unit tests, structural ArchUnit tests |
| Security / Config | `secure-tdd-implementer` | `MastodonProperties.java`, `GlacierOperatorProperties.java`, `GlacierBindHandler.java`, `@SafeOperatorString`, `MastodonInstanceValidator`, key migration, `application.properties`, binding-failure ITs, log-scrubbing ITs |
| CI / Docs / Housekeeping | `devops-infra-engineer` | `codeql.yml` fix, Javadoc typo (11 files), `README.md` (versioning + modes), `docs/decisions/README.md` ADR index, `AdrIndexCompletenessSentinelTest`, `RessourcesTypoSentinelTest`, `CLAUDE.md` update, `docs/ADMIN.md` key-migration note |

## Resolved Conflicts

### Conflict #1: Partial wiring of `glacier.cookie.secure`
**ddd-tdd-architect**: deferred in scope for simplicity.
**secure-feature-planner**: partial wiring is FORBIDDEN — two binding mechanisms simultaneously authoritative for one boolean is a security misconfiguration window.
**Resolution (2026-05-10)**: `GlacierDevmodeProperties` fully deferred to Bundle B. No partial-wiring intermediate state.

### Conflict #2: `mastodon.https` primitive boolean
**ddd-tdd-architect**: primitive `boolean` "preserves existing fail-on-missing".
**secure-feature-planner**: primitive `boolean` does NOT fail-on-missing under `@ConfigurationProperties`; silently defaults to `false` = HTTP downgrade.
**Resolution (2026-05-10)**: Use `Boolean` (boxed) + `@NotNull`.

### Conflict #3: codeql.yml verification
**ddd-tdd-architect**: "no test — CI run is canary."
**secure-feature-planner**: canary must be human-verified; CI log excerpt required in acceptance doc.
**Resolution (2026-05-10)**: Manual CI log excerpt required in Phase 3 acceptance doc (AC-P3A-19).

## User Approval

Date: 2026-05-10
Approval message (verbatim): "approve"

## Open Risks

1. **TD-P3A-DOMAIN-FIX**: `DomainSafetyValidator.java:98` raw-value concatenation in error message. Deferred, not fixed in this bundle. Mitigated by writing a separate `MastodonInstanceValidator`.
2. **Operator key migration exhaustiveness**: `glacier.operatorName` → `glacier.operator.name` must catch ALL `@TestPropertySource` overrides in the test tree. `OperatorPropertyKeyMigrationSentinelTest` is the safety net.
3. **Bigbone SHA256 pin** (Sec-02/P1-09): Pre-existing, blocked by upstream. Not in scope.

## References

- [Quality Review acceptance](2026-05-08-acceptance-quality-review.md) (source of P3 items)
- [SubscriptionService split acceptance](2026-05-10-acceptance-subscription-service-split.md) (state of codebase entering this bundle)
- ADR-F6-INFO-2-A: [F-6-INFO-2 event type constants — Acceptance](2026-04-30-acceptance-f6info2-event-type-constants.md) (three vocabularies stay separate)
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — [A02](https://owasp.org/Top10/A02_2021-Cryptographic_Failures/), [A03](https://owasp.org/Top10/A03_2021-Injection/), [A05](https://owasp.org/Top10/A05_2021-Security_Misconfiguration/), [A09](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — [API1](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/), [API4](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html), [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html), [CWE-79: Cross-site Scripting](https://cwe.mitre.org/data/definitions/79.html)
- [OWASP ASVS 5.0](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) V5.1.3 (L1), V7.3.1 (L1), V9.1.1 (L1)
