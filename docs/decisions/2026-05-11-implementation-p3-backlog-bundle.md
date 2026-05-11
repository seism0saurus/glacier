# Decision Record: P3 Backlog Bundle A — Phase 2 Implementation

Date: 2026-05-11
Phase: Implementation
Agents: tdd-ddd-implementer (R1 + R2), secure-tdd-implementer (R1 + R2), devops-infra-engineer (R1 + R2)
Status: Accepted

## Summary

All seven P3 Backlog Bundle A items are implemented across three lanes. The integration fix
(MastodonHandleFactory migration from `@Value` to `MastodonProperties`) was completed in Round 2.
Final test counts: **1513 unit / 336 IT, BUILD SUCCESS**, Jacoco ≥ 45 %/35 % met. No unresolved
conflicts.

## Lane Partition

| Lane | Agent | Files / Concerns |
|------|-------|-----------------|
| Domain/Application | `tdd-ddd-implementer` | `MastodonShortHandle.java`, `MastodonHandleFactory.java`, `EventTypeMapping.java`, `StompEventType` visibility, `StompCallback` wiring, `SubscriptionManagerImpl` wiring, `InformationController` handle field, `LogScrubber.forErrorMessage()`, unit tests, ArchUnit |
| Security / Config | `secure-tdd-implementer` | `MastodonProperties.java`, `GlacierOperatorProperties.java`, `GlacierBindHandler.java`, `@SafeOperatorString`, `MastodonInstanceValidator.java`, key migration, `MastodonConfiguration.java`, `InformationController` operator fields, binding-failure ITs |
| CI / Docs / Housekeeping | `devops-infra-engineer` | `codeql.yml` fix, Javadoc typo (6 files), `README.md`, `docs/decisions/README.md`, sentinel tests, `CLAUDE.md`, `docs/ADMIN.md` |

## Key Decisions

### MastodonHandleFactory migrated to MastodonProperties in Round 2

**Decision**: `MastodonHandleFactory` constructor parameter changed from `@Value("${mastodon.handle}")`
to `MastodonProperties mastodonProperties`; `mastodonShortHandle()` calls
`mastodonProperties.getHandle()` then `MastodonShortHandle.parse()`.

**Rationale**: The factory's own Javadoc documented this migration as the intended Round 2
integration step (ADR-P3A-2). The Round 1 placeholder used `@Value` because `MastodonProperties`
did not yet exist in the tdd-ddd lane's worktree.

**Consequence**: Double-validation applies — `@Pattern` rejects structural violations at binding
time; `parse()` enforces domain invariants (Unicode directional overrides, BOM, null-byte) that the
regex cannot express. This is defence-in-depth per SR-P3A-06.

**Source**: tdd-ddd-implementer R2.

### MastodonHandleFactoryIT simplified to hasFailed()

**Decision**: `MastodonHandleFactoryIT.invalidHandle_failsStartup()` uses `assertThat(context).hasFailed()`
rather than asserting `IllegalStateException` specifically.

**Rationale**: With `MastodonProperties` loaded in the `ApplicationContextRunner`, the `@Pattern`
binding constraint fires before the factory runs. Pinning to `IllegalStateException` would couple
the test to which validation layer fires first — a brittle over-specification.

**Source**: tdd-ddd-implementer R2, accepted by secure-tdd-implementer R2.

### @Import extensions in @WebMvcTest slice tests

**Decision**: `OwaspMatrixCookieAttributesLockstepTest` and `CookieEmissionIT` both use
`@Import({MastodonProperties.class, MastodonHandleFactory.class})` for real beans and
`@MockitoBean GlacierOperatorProperties` for the operator properties.

**Rationale**: `@WebMvcTest` slices exclude `@ConfigurationProperties` beans. `MastodonProperties`
and `MastodonHandleFactory` carry production wiring contracts and must be real. Operator properties
are not under test in cookie tests; mocking avoids providing all 8 operator env-vars in the test
context.

**Source**: tdd-ddd-implementer R2, accepted by secure-tdd-implementer R2.

### Boolean https + @NotNull (ADR-P3A-8 confirmed)

**Decision**: `MastodonProperties.https` is `private Boolean https` with `@NotNull`. Missing
`mastodon.https` causes startup failure (not silent `false`). `MastodonConfiguration` uses
`Boolean.TRUE.equals(props.getHttps())` for safe unboxing.

**Source**: secure-tdd-implementer R1, consistent with ADR-P3A-8.

### GlacierBindHandler scrubs BindException (ADR-P3A-7 confirmed)

**Decision**: `GlacierBindHandler` implements Spring Boot's `BindHandler` via a
`ConfigurationPropertiesBindHandlerAdvisor @Bean`. `onFailure(...)` wraps `getRejectedValue()` in
`LogScrubber.forErrorMessage()` before re-throwing. `BindFailureValueScrubbingIT` proves
`mastodon.accessToken` does not appear in the exception chain when a different field fails.

**Source**: secure-tdd-implementer R1.

### DomainSafetyValidator.java:98 not fixed (ADR-P3A-10 confirmed)

**Decision**: Raw-value concatenation in `DomainSafetyValidator.java:98` error message tracked as
TD-P3A-DOMAIN-FIX; not fixed in this bundle. `MastodonInstanceValidator` written independently
for the `mastodon.instance` field and does NOT delegate to `DomainSafetyValidator`.

**Source**: secure-tdd-implementer R1, per ADR-P3A-10.

### Sentinel ratchet tests

Three sentinel tests added:
- `OperatorPropertyKeyMigrationSentinelTest` — walks `src/` and fails if any camelCase `glacier.operator[A-Z]` key remains
- `RessourcesTypoSentinelTest` — walks `src/main/java/` and fails if `ressources` typo reappears
- `AdrIndexCompletenessSentinelTest` — walks `docs/decisions/` and fails if any `.md` file is absent from `docs/decisions/README.md`

**Source**: devops-infra-engineer R1.

## New Production Files

| File | Lane | Description |
|------|------|-------------|
| `de.seism0saurus.glacier.mastodon.MastodonShortHandle` | domain | Immutable record `(full, localPart, server)`; `toString()` never exposes `full` or `server` |
| `de.seism0saurus.glacier.mastodon.MastodonHandleFactory` | domain | `@Component`; produces `MastodonShortHandle` bean from `MastodonProperties.getHandle()` |
| `de.seism0saurus.glacier.eventtype.EventTypeMapping` | domain | Static anti-corruption helper mapping all three event-type vocabularies |
| `de.seism0saurus.glacier.MastodonProperties` | security | `@Validated @ConfigurationProperties(prefix="mastodon")` |
| `de.seism0saurus.glacier.GlacierOperatorProperties` | security | `@Validated @ConfigurationProperties(prefix="glacier.operator")` |
| `de.seism0saurus.glacier.GlacierBindHandler` | security | `BindHandler` that scrubs `BindException` via `LogScrubber.forErrorMessage()` |
| `de.seism0saurus.glacier.SafeOperatorString` + `SafeOperatorStringValidator` | security | Composite annotation; rejects CRLF, null byte, tab, RTL override, ZWSP, BOM, U+2028, U+2029 |
| `de.seism0saurus.glacier.MastodonInstanceValidator` + `@MastodonInstanceValid` | security | Custom validator for `mastodon.instance`; independent of `DomainSafetyValidator` |
| `docs/decisions/README.md` | devops | Hand-curated ADR index (80 files, 6 topic groups) |

## Modified Production Files

| File | Change |
|------|--------|
| `MastodonConfiguration.java` | 8 `@Value` params → single `MastodonProperties props` |
| `InformationController.java` | 8 operator `@Value` params → `GlacierOperatorProperties`; mastodon handle → `MastodonShortHandle` |
| `StompCallback.java` | `String handle` → `MastodonShortHandle shortHandle`; removed `getShortHandle()` helper |
| `SubscriptionManagerImpl.java` | `MastodonShortHandle shortHandle` constructor param |
| `StompEventType.java` | Visibility changed to `public` |
| `LogScrubber.java` | Added `forErrorMessage(String)` method |
| `application.properties` | 8 operator keys renamed; `mastodon.handle` default fixed |
| `src/test/resources/application.properties` | Same key renames |
| `.github/workflows/codeql.yml` | `{{ hashFiles(...) }}` → `${{ hashFiles(...) }}` |
| `README.md` | Added `## Operational modes` + `## Versioning and releases` sections |
| `CLAUDE.md` | ADR index maintenance note; ressources-typo caveat updated |
| `docs/ADMIN.md` | `glacier.operator.*` key migration note |
| 6 Java source files | Javadoc `ressources` → `resources` typo fix |

## New Test Files

| Test | Type | Purpose |
|------|------|---------|
| `MastodonShortHandleTest` | unit | ≥ 15 cases including CRLF, RTL override, underscore in server |
| `MastodonShortHandleVsLegacyParityTest` | unit | 4 parameterized: parse() rejects all legacy-rejected inputs |
| `EventTypeMappingTest` | unit | All 3 vocabulary branches, bidirectional consistency |
| `EventTypeMappingExhaustivenessTest` | unit | Reflection: every enum constant has a mapping |
| `EventTypeMappingExclusivityTest` | unit/ArchUnit | No class outside eventtype package imports both StompEventType AND EventType |
| `MastodonPropertiesTest` | unit | Valid/invalid patterns, boxed Boolean |
| `GlacierOperatorPropertiesTest` | unit | @SafeOperatorString, URI scheme rejection |
| `SafeOperatorStringValidatorTest` | unit | All 8 forbidden char/sequence cases |
| `OperatorPropertyKeyMigrationSentinelTest` | unit/sentinel | No camelCase operator key remains |
| `CookieSecureSingleSourceOfTruthStructureTest` | unit/ratchet | All `glacier.cookie.secure` consumers remain on `@Value` |
| `RessourcesTypoSentinelTest` | unit/sentinel | Typo cannot reappear in `src/main/java/` |
| `AdrIndexCompletenessSentinelTest` | unit/sentinel | ADR index covers every file in `docs/decisions/` |
| `MastodonHandleFactoryIT` | integration | ApplicationContextRunner; valid handle → bean; invalid handle → hasFailed() |
| `MastodonPropertiesIT` | integration | Valid mastodon.* properties load correctly |
| `MastodonPropertiesMissingHttpsIT` | integration | Missing mastodon.https → hasFailed() (not silent false) |
| `BindFailureValueScrubbingIT` | integration | accessToken absent from exception chain when another field fails |
| `GlacierOperatorPropertiesBindingFailureIT` | integration | Invalid operator value causes startup failure with scrubbed error |

## Resolved Conflicts

### Worktree typo-fix artifact
**devops-infra-engineer**: flagged that secure lane's worktree versions of 5 files still carry `ressources` typo.
**Resolution (2026-05-11)**: Main working tree already has both fixes applied (typo fix + property migration from previous pipeline session). Conflict was a worktree isolation artifact; no action required in integration.

## User Approval
Date: 2026-05-11
Approval message (verbatim): "approve"

## Open Risks
1. **TD-P3A-DOMAIN-FIX**: `DomainSafetyValidator.java:98` raw-value concatenation. Deferred. Mitigated by separate `MastodonInstanceValidator`.
2. **AC-P3A-19 manual gate**: `codeql.yml` fix requires CI log excerpt in Phase 3 acceptance document.
3. **GlacierDevmodeProperties deferred**: All `glacier.cookie.secure` consumers remain on `@Value` pending Bundle B atomic migration.
4. **AdrIndexCompletenessSentinelTest at merge**: The Phase 3 acceptance commit must add the implementation + acceptance ADR rows to `docs/decisions/README.md` in the same commit; the sentinel enforces this.

## References
- Planning: `docs/decisions/2026-05-10-planning-p3-backlog-bundle.md`
- Quality Review acceptance: `docs/decisions/2026-05-08-acceptance-quality-review.md`
- SubscriptionService split acceptance: `docs/decisions/2026-05-10-acceptance-subscription-service-split.md`
- ADR-F6-INFO-2-A: `docs/decisions/2026-04-30-acceptance-f6info2-event-type-constants.md`
