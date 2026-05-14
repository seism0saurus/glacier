# Decision Record: P3-05 Share-Link SQLite Persistence — Phase 2 Implementation

Date: 2026-05-13
Phase: Implementation
Agents: devops-infra-engineer (Lane 1), secure-tdd-implementer (Lane 2), tdd-ddd-implementer (Lane 3 + fix cycle)
Status: Accepted

## Summary

P3-05 implementation is complete. Four sequential lanes delivered the full SQLite share-link
persistence adapter: DevOps dependency/CI work, security scaffold, DDD/TDD domain + adapter,
operator documentation. A Phase 2 Round 2 cross-review identified two blocking issues
(PRAGMA delivery via HikariCP, SR-SQLITE-03 CRUD exception scrubbing) which were fixed before
the approval gate. Build: **1672 unit + 377 integration, 0 failures, Jacoco ≥ 45%/35% met**.

## Files Created / Modified

### Lane 1 — DevOps (commit `953521e`)

| File | Change |
|------|--------|
| `pom.xml` | `spring-boot-starter-jdbc` (BOM) + `org.xerial:sqlite-jdbc:3.49.1.0` (pinned) |
| `dependency-checksums/sqlite-jdbc-3.49.1.0.sha256` | SHA-256 checksum for supply-chain gate |
| `dependency-checksums/README.md` | Upgrade procedure + format documentation |
| `dependency-checksums/.gitignore` | Prevents accidental JAR commits |
| `.github/workflows/verify.yml` | SHA-256 gate step before `mvnw verify` |
| `.github/workflows/pull-request.yml` | SHA-256 gate step before `mvnw build` |

### Lane 2 — Security (commit `edbbb34`)

| File | Change |
|------|--------|
| `share/infrastructure/SafeFilesystemPath.java` | New annotation (CWE-22; CWE-626; OWASP A03:2021) |
| `share/infrastructure/SafeFilesystemPathValidator.java` | Path-traversal, shell-metacharacter, null-byte, Unicode-direction rejection |
| `share/infrastructure/SharePersistenceProperties.java` | `@ConfigurationProperties("glacier.share.db")`, `@Validated`, `@ConditionalOnProperty` |
| `share/infrastructure/SqliteDataSourceConfig.java` | HikariCP pool=2, connectionTimeout=2000, leakDetectionThreshold=10000 |
| `GlacierApplication.java` | Exclude DataSource/JDBC auto-configurations (opt-in only) |
| `share/infrastructure/InMemoryShareLinkRepository.java` | Conditional wiring fix: `@ConditionalOnProperty(havingValue="NEVER_MATCHES", matchIfMissing=true)` |

New test classes (Lane 2): `SharePersistencePropertiesPathTraversalTest` (25+ inputs),
`SharePersistencePropertiesTest`, `ShareLinkPersistenceArchitectureTest`,
`SqliteSchemaIdempotencyTest`, `ShareLinkPersistencePropertiesValidationIT`,
`SqliteShareLinkRepositoryPragmaIT`, `ShareLinkRepositoryAuditEmissionIT`,
`SqliteShareLinkRepositoryFilePermissionsIT`, `SqliteShareLinkRepositoryConnectionPoolIT`,
`SqliteShareLinkRepositoryLoggingTest`.

### Lane 3 — DDD-TDD (commit `47424d2`)

| File | Change |
|------|--------|
| `share/domain/ShareLinkSummary.java` | New read-model projection (no raw token; SR-SQLITE-20) |
| `share/domain/ShareLink.java` | `fromPersistence()` factory (ADR-SQLITE-08) |
| `share/domain/ShareLinkRepository.java` | `listSummaryBySharer()`; `findAllBySharer()` deprecated |
| `share/application/ShareLinkService.java` | `listSummaryBySharer()`; `listBySharer()` deprecated |
| `share/application/ShareLinkServiceImpl.java` | Adapts to `listSummaryBySharer` |
| `share/infrastructure/InMemoryShareLinkRepository.java` | Implements `listSummaryBySharer`; `sha256Hex` helper |
| `share/infrastructure/SqliteShareLinkRepository.java` | Full CRUD + revocation + HMAC IP + sweep + disk-full guard + POSIX permissions |
| `share/web/ShareLinkListEntry.java` | Drops `readonlyUrl`; gains `idHash8 + status` |
| `share/web/ShareLinkController.java` | Uses `listSummaryBySharer`; no raw token in responses |
| `ShareLinkPersistenceArchitectureTest.java` | All 4 ArchUnit rules active |

New test classes (Lane 3): `SqliteShareLinkRepositoryTest` (17), `TokenAtRest` (2), `IpAtRest` (3),
`SqlInjection` (20), `RevocationRace` (1), `ConcurrencyIT` (1), `RestartIT` (2), `SweepIT` (2),
`DiskFullIT` (1), `DegradedSweepIT` (1), `ShareLinkServiceImplSqliteIT` (3), `ShareLinkSummaryTest` (5),
`ShareLinkFromPersistenceTest` (3). Plus updates to `ShareLinkControllerIT`, `ShareLinkServiceImplTest`, etc.

### Lane 4 — Docs + Fix cycle (commit `0ba9eae`)

| File | Change |
|------|--------|
| `docs/ADMIN.md` | New section: env vars, POSIX permissions, WAL backup, HMAC key rotation, one-time URL UX |
| `docs/MIGRATION.md` | New file: schema, WAL sidecars, IP HMAC rotation, URL retrievability, upgrade procedure |
| `share/infrastructure/SqliteDataSourceConfig.java` | PRAGMA fix: replaced `connectionInitSql` with JDBC URL parameters (see Fix 1 below) |
| `share/infrastructure/SqliteShareLinkRepository.java` | SR-SQLITE-03 fix: all 8 CRUD methods wrapped with `LogScrubber.forErrorMessage()` (Fix 2) |
| `share/infrastructure/SqliteDataSourceConfigPragmaIT.java` | New IT: 2 tests verifying PRAGMA via HikariCP-pooled connection |
| `share/infrastructure/SqliteShareLinkRepositoryLoggingTest.java` | +1 test: CRUD exception message scrubbing |

## Key Decisions

### FIX-1: PRAGMA delivery via JDBC URL parameters (not `connectionInitSql`)

**Decision**: `SqliteDataSourceConfig` constructs the JDBC URL as:
```
jdbc:sqlite:<path>?journal_mode=WAL&synchronous=FULL&foreign_keys=ON&busy_timeout=<n>
```
The `connectionInitSql` multi-statement approach was removed.

**Rationale**: `org.xerial:sqlite-jdbc`'s `Statement.execute()` delegates to `sqlite3_prepare_v2()`,
which compiles only the **first** statement in a semicolon-separated string. PRAGMAs 2-4
(`synchronous=FULL`, `foreign_keys=ON`, `busy_timeout`) were silently dropped on every pool
connection. JDBC URL parameters are applied atomically by the driver before any SQL executes,
guaranteeing all four settings on every HikariCP-vended connection.

**Discovery**: Phase 2 Round 2 cross-review (devops-infra-engineer). Original Lane 2 code was
correct in intent but used a driver-incompatible delivery mechanism.

**Source**: devops-infra-engineer Round 2 (CONFLICT confirmed), user approved fix option A/B.

---

### FIX-2: CRUD exception scrubbing to satisfy SR-SQLITE-03

**Decision**: All 8 public CRUD methods in `SqliteShareLinkRepository` now wrap
`DataAccessException` in `try/catch` and re-throw as:
```java
new UncategorizedDataAccessException(LogScrubber.forErrorMessage(e.getMessage()), e) {}
```

**Rationale**: SR-SQLITE-03 requires `LogScrubber.forErrorMessage()` to wrap ALL JDBC exceptions
before re-throw. The initial implementation only covered `scheduledSweep()` and
`enforceFilePermissions()`. The CRUD methods propagated raw `DataAccessException`s which could
surface JDBC error messages containing SQL fragments.

**Source**: secure-tdd-implementer Round 2 (CONFLICT), user approved fix option A.

---

### ADR-IMPL-01: `ShareLinkSummary` placed in `share.domain` (not `share.application`)

**Decision**: `ShareLinkSummary` is in `de.seism0saurus.glacier.share.domain`.

**Rationale**: Both repository adapters (`InMemoryShareLinkRepository` and
`SqliteShareLinkRepository`) reside in `share.infrastructure` and must construct
`ShareLinkSummary`. Moving it to `share.application` would create an upward import from
`share.infrastructure` into `share.application` — an architectural violation. The domain
placement avoids this without introducing a shared DTO package.

**Source**: tdd-ddd-implementer Round 2 (accepted).

---

### ADR-IMPL-02: `InMemoryShareLinkRepository` conditional wiring pattern

**Decision**: `@ConditionalOnProperty(havingValue="NEVER_MATCHES", matchIfMissing=true)` used on
`InMemoryShareLinkRepository` (replacing `@ConditionalOnMissingBean`).

**Rationale**: `@ConditionalOnMissingBean(ShareLinkRepository.class)` caused a self-referential
`BeanCreationException` in Spring's context initialisation. The `NEVER_MATCHES` sentinel with
`matchIfMissing=true` achieves the same mutual-exclusion logic without the circular dependency.
Both adapters activate on exactly complementary conditions.

**Source**: secure-tdd-implementer Lane 2 (applied).

## Test Results

| Layer | Count | Failures | Status |
|-------|-------|----------|--------|
| Surefire (unit, `*Test.java`) | 1 672 | 0 | PASS |
| Failsafe (integration, `*IT.java`) | 377 | 0 | PASS |
| Jacoco instruction coverage | ≥ 45% | — | PASS |
| Jacoco branch coverage | ≥ 35% | — | PASS |

## Security Requirements Coverage

| Category | SRs | Status |
|----------|-----|--------|
| CRITICAL (SR-01, -02, -03, -19) | 4 | All MET |
| HIGH (SR-04..09, -12, -14, -20, -22) | 10 | All MET (SR-22 placeholder-string deferred as advisory) |
| MEDIUM (SR-10, -11, -13, -15..17, -21, -23) | 8 | All MET |
| LOW (SR-18, -24) | 2 | All MET |

## Resolved Conflicts

### Conflict #1 — HikariCP `connectionInitSql` drops PRAGMAs 2-4
**devops-infra-engineer**: Confirmed real driver defect — `sqlite3_prepare_v2()` only compiles the first statement.
**tdd-ddd-implementer**: Originally classified as "minor test gap."
**Resolution (2026-05-13)**: Fix 1 applied — JDBC URL parameters replace `connectionInitSql`.

### Conflict #2 — SR-SQLITE-03 CRUD exception scrubbing incomplete
**secure-tdd-implementer**: CRUD methods missing `LogScrubber.forErrorMessage()` wrapping.
**tdd-ddd-implementer**: Had missed this gap in Round 2.
**Resolution (2026-05-13)**: Fix 2 applied — all 8 CRUD methods wrapped.

## User Approval

Date: 2026-05-13
Approval message (verbatim): "approve"

## Open Risks (accepted, do not block)

| # | Risk | Mitigation |
|---|------|------------|
| R1 | `ShareLinkRepository` interface JavaDoc stale ("only impl is InMemory") | Will update in Phase 3 acceptance commit |
| R2 | SR-SQLITE-22 "reject placeholder strings" not implemented | `@Size(min=44)` + `GlacierBindHandler` satisfy core SR; blocklist advisory; tracked post-acceptance |
| R3 | `ShareViewStompRelay` calls deprecated `findAllBySharer` — relay silent in SQLite mode | Accepted per ADR-SQLITE-05; tracked as post-acceptance follow-up |
| R4 | `SqliteJdbcChecksumGateTest` unit test absent | CI gate (SHA-256 download + `sha256sum -c`) is the authoritative control |

## References

- Planning: `docs/decisions/2026-05-11-planning-share-link-sqlite-persistence.md`
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — A02, A03, A05
- [sqlite3_prepare_v2 documentation](https://www.sqlite.org/c3ref/prepare.html) — single-statement compilation
- [HikariCP connectionInitSql](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby) — single `Statement.execute()` call
