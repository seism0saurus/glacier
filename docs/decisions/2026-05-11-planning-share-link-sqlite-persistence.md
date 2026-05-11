# Decision Record: P3-05 Share-link SQLite Persistence — Phase 1 Planning

Date: 2026-05-11
Phase: Planning
Agents: ddd-tdd-architect (R1 + R2), secure-feature-planner (R1 + R2)
Status: Accepted

## Summary

`InMemoryShareLinkRepository` stores share links in a JVM-heap `ConcurrentHashMap`; every active
link is lost on server restart. P3-05 adds an opt-in SQLite-backed adapter that survives restarts.
The in-memory adapter remains the default; operators set `glacier.share.db.path` to activate
durability. The adapter stores tokens and creator IPs as hashed/HMAC values — no raw bearer
credentials at rest.

## Items in scope

| Item | Description |
|------|-------------|
| `SqliteShareLinkRepository` | New `@ConditionalOnProperty("glacier.share.db.path")` implementation of `ShareLinkRepository` |
| `ShareLink.fromPersistence(...)` | New public static factory on the aggregate for DB reconstitution |
| `ShareLinkSummary` | New application-layer read-model projection (replaces full aggregate return from list-view) |
| `ShareLinkRepository#listSummaryBySharer` | New port method returning `List<ShareLinkSummary>` |
| `SharePersistenceProperties` | `@ConfigurationProperties("glacier.share.db")` bean with `path`, `ip-hmac-key`, `maxPageCount` |
| `@SafeFilesystemPath` | New `ConstraintValidator` rejecting path traversal on `glacier.share.db.path` |
| `ShareLinkRowMapper` | JDBC `RowMapper<ShareLink>` in `share.infrastructure` |
| Web-layer ripple | `ShareLinkListEntry` drops `readonlyUrl`; `ShareLinkController#listBySharer` adapts |
| `pom.xml` | New `spring-boot-starter-jdbc` + `org.xerial:sqlite-jdbc` dependencies |
| SHA-256 supply-chain pin | `dependency-checksums/sqlite-jdbc-<ver>.sha256` + both CI workflows |
| Operator docs | `docs/ADMIN.md` update + new `MIGRATION.md` |

## Key Decisions

### ADR-SQLITE-01: Conditional wiring via `@ConditionalOnProperty`

**Decision**: `SqliteShareLinkRepository` is active when `glacier.share.db.path` is set.
`InMemoryShareLinkRepository` is annotated `@ConditionalOnMissingBean(ShareLinkRepository.class)`
and remains the default.

**Rationale**: Backwards-compatible; testable via `ApplicationContextRunner` without a full Spring
context; matches Glacier's explicit-wiring discipline; no `@Primary` ambiguity.

**Alternatives considered**: `@Primary` (silent compile-time pin; rejected), profile `sqlite`
(coarse; conflicts with e2e profile usage), factory bean (indirection without benefit).

**Source**: ddd-tdd-architect R1, secure-feature-planner R1 (accepted).

---

### ADR-SQLITE-02: Schema DDL via `CREATE TABLE IF NOT EXISTS` in `@PostConstruct`

**Decision**: `SqliteShareLinkRepository` creates its own schema in `@PostConstruct init()` using
idempotent `CREATE TABLE IF NOT EXISTS share_links (...)` + `CREATE INDEX IF NOT EXISTS ...`. No
Flyway/Liquibase; no `schema.sql`. `PRAGMA synchronous = FULL` applied globally.

**Rationale**: One table, no migrations needed yet (YAGNI); loosest coupling to any future
additional datasource; `IF NOT EXISTS` is safe on every boot. `synchronous=FULL` is required for
revocation durability (see ADR-SQLITE-05) and has negligible overhead (< 100 writes/day typical).

**Alternatives considered**: Flyway (too heavy for one table), `spring.sql.init.*` (global coupling),
`synchronous=NORMAL` (permits revocation loss on power failure — rejected, see CONFLICT-3 below).

**Consequences**: Future schema changes require introducing a migration tool. Documented in
`MIGRATION.md` as known future work.

**Source**: ddd-tdd-architect R1 (adapted by security in R1, `FULL` accepted in R2).

**Proposed table schema**:

```sql
CREATE TABLE IF NOT EXISTS share_links (
    id              TEXT    NOT NULL PRIMARY KEY,   -- SHA-256(token) hex, NOT raw token
    sharer_wall_id  TEXT    NOT NULL,
    creator_ip_hmac TEXT,                           -- HMAC-SHA256(ip, key) hex; nullable
    created_at      INTEGER NOT NULL,               -- epoch millis UTC
    expires_at      INTEGER NOT NULL,               -- epoch millis UTC
    revoked_at      INTEGER                         -- nullable; epoch millis UTC
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_share_links_sharer_active
    ON share_links (sharer_wall_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_share_links_creator_ip_active
    ON share_links (creator_ip_hmac, expires_at)
    WHERE revoked_at IS NULL AND creator_ip_hmac IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_share_links_expires_at
    ON share_links (expires_at)
    WHERE revoked_at IS NULL;
```

SQLite PRAGMA settings (applied at connection open):
```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
```

---

### ADR-SQLITE-03: "First revocation wins" via atomic conditional `UPDATE`

**Decision**: `markRevoked(id, when)` runs:
```sql
UPDATE share_links SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL
```
The `WHERE revoked_at IS NULL` predicate substitutes for the in-memory aggregate's `synchronized
revoke()`. SQLite serialises writes; the conditional update is atomic. Zero rows affected = already
revoked (no-op, matching in-memory idempotency).

**Rationale**: Single-statement compare-and-swap; no SELECT-then-UPDATE race; mirrors in-memory
semantic without coupling the adapter to aggregate mutation.

**Source**: ddd-tdd-architect R1, secure-feature-planner R1 (accepted).

---

### ADR-SQLITE-04: Adapter-local token hashing; no `ShareLinkIdHash` domain type

**Decision**: The SQLite adapter computes `SHA-256(id.value())` internally via a private static
`sha256Hex(String)` method at both `save` and `findById` time. The raw token is NEVER written to
the database. `ShareLinkRepository.findById(ShareLinkId)` contract is **unchanged**. The domain
types `ShareLinkId` and `ShareLink` are unchanged (except for the `fromPersistence` factory).

The security planner originally proposed splitting `ShareLinkId` from a `ShareLinkIdHash` domain
type; the architect adapted this to adapter-local hashing which preserves the same security
invariant with strictly less domain disruption.

**ArchUnit enforcement**: `sha256Hex(String)` is a private static method. An ArchUnit rule in the
existing architecture test suite asserts no class outside `share.infrastructure` may call it
(enforced by package + visibility rules).

**Rationale**: The adapter approach achieves the at-rest protection without 16+ call-site changes
across all `ShareLinkId` consumers; domain types stay stable; no compiler-level distinction
introduced for what is an infrastructure-layer concern.

**Source**: ddd-tdd-architect R2 (adaptation), secure-feature-planner R2 (confirmed — SR-19 added).

---

### ADR-SQLITE-05: `findAllBySharer` replaced by `listSummaryBySharer` returning a projection

**Decision**: `ShareLinkRepository` gains a new method:
```java
List<ShareLinkSummary> listSummaryBySharer(String sharerWallId, Instant now);
```
`ShareLinkSummary` is a new immutable record in `share.application` containing:
`hash8`, `createdAt`, `expiresAt`, `revokedAt`, `status(now)`, `sharerWallId`. It does NOT carry
a `ShareLinkId` or any raw token field.

`ShareLinkListEntry` (the API response DTO) drops the `readonlyUrl` field. The share URL is
returned exactly once in the creation-success response and is never retrievable afterwards (Stripe /
GitHub / AWS bearer-token UX). The creation-success view gains a copy-to-clipboard affordance and
a German "shown once" warning (i18n catalog key required per SR-21).

**Rationale**: The SQLite adapter stores only `SHA-256(token)` in the `id` column; without the
raw token there is no way to reconstruct the URL for a list-view. The projection approach is
strictly less invasive than introducing a new domain type. Option B (AES-GCM ciphertext blob) was
explicitly rejected as reintroducing the at-rest bearer-credential risk.

**Source**: ddd-tdd-architect R2, secure-feature-planner R2 (confirmed — SR-20 + SR-21 added).

---

### ADR-SQLITE-06: Creator IP stored as HMAC-SHA256, adapter-local

**Decision**: `creatorIp` is stored as `HMAC-SHA256(ip, key)` hex in the `creator_ip_hmac` column.
The key is sourced from `glacier.share.db.ip-hmac-key` (`@NotBlank`, `@Size(min=44)` for 256-bit
base64 key). The HMAC transform is adapter-local; the aggregate carries the raw IP in memory.
Reconstituted aggregates carry `creatorIp=null` (already nullable per `ShareLink` JavaDoc).
`countActiveForIp(ip, now)` queries with `creator_ip_hmac = HMAC(ip, key)`.

**Key-rotation trade-off**: Rotating `glacier.share.db.ip-hmac-key` invalidates existing HMAC
values; `countActiveForIp` returns 0 for all pre-rotation IPs. Accepted because the IP cap is a
soft anti-DoS measure, not a hard security invariant.

**Source**: secure-feature-planner R1 (SR-SQLITE-04), ddd-tdd-architect R2 (accepted).

---

### ADR-SQLITE-07: Per-adapter `@Scheduled` sweep; no shared scheduler

**Decision**: `SqliteShareLinkRepository` owns its own `@Scheduled scheduledSweep()`, like
`InMemoryShareLinkRepository`. Only one adapter is active at a time (conditional wiring), so only
one sweep fires.

**Source**: ddd-tdd-architect R1 (accepted, YAGNI).

---

### ADR-SQLITE-08: `ShareLink.fromPersistence(...)` public static factory

**Decision**: Single public static factory added to `ShareLink` for DB reconstitution. Accepts
explicit `revokedAt` parameter (null-safe). JavaDoc explicitly marks it "persistence reconstitution
ONLY — not a domain-creation path". ArchUnit rule fences callers to `share.infrastructure.*`.

**Source**: ddd-tdd-architect R1 (adapted from package-private to public due to package separation).

---

### ADR-SQLITE-09: HikariCP connection pool size = 2

**Decision**: The SQLite datasource uses HikariCP with `maximumPoolSize=2`,
`connectionTimeout=2000`, `leakDetectionThreshold=10000`. SQLite WAL allows concurrent reads plus
one writer; pool size 4 would cause lock contention spikes.

**Source**: secure-feature-planner R1 (SR-SQLITE-13), ddd-tdd-architect R2 (adapted 4→2).

---

## Security Requirements (security_final — 24 SRs)

### CRITICAL
| SR | Requirement |
|----|-------------|
| SR-SQLITE-01 | SHA-256(token) stored as DB primary key — raw token never in any JDBC parameter or log |
| SR-SQLITE-02 | ALL SQL via `PreparedStatement` parameter binding — zero string concatenation |
| SR-SQLITE-03 | `LogScrubber.forErrorMessage(value)` wraps ALL JDBC exceptions before re-throw |
| SR-SQLITE-19 | Exactly one `sha256Hex(ShareLinkId)` call site in codebase (ArchUnit-enforced) |

### HIGH
| SR | Requirement |
|----|-------------|
| SR-SQLITE-04 | `HMAC-SHA256(creatorIp, hmacKey)` stored — raw IP never in DB |
| SR-SQLITE-05 | `synchronous=FULL` globally on the SQLite connection |
| SR-SQLITE-06 | Startup AUDIT log line naming the active repository impl + `hash8(path.toString())` |
| SR-SQLITE-07 | DB file created/opened with POSIX permissions `0600`; fail-closed on POSIX; degrade-with-warn on non-POSIX |
| SR-SQLITE-08 | `@SafeFilesystemPath` validator on `glacier.share.db.path` — reject `..`, `~`, metacharacters, null bytes |
| SR-SQLITE-09 | `PRAGMA max_page_count` from `glacier.share.db.maxPageCount`; disk-full → `CapacityExceededException` |
| SR-SQLITE-12 | `org.xerial:sqlite-jdbc` SHA-256 checksum committed; `sha256sum -c` in BOTH CI workflows |
| SR-SQLITE-14 | AUDIT.warn on sweep/revoke JDBC failures; scheduler thread must survive |
| SR-SQLITE-20 | `ShareLinkSummary` ArchUnit rule: no `ShareLinkId` field, no String named `id\|token\|secret\|url` |
| SR-SQLITE-22 | `glacier.share.db.ip-hmac-key`: `@Size(min=44)`; FORBIDDEN_LOG_FIELDS + BindHandler redaction; reject placeholder strings |

### MEDIUM
| SR | Requirement |
|----|-------------|
| SR-SQLITE-10 | ArchUnit rule: no `java.sql.Statement` (non-prepared) from `share.infrastructure.*` |
| SR-SQLITE-11 | `InetAddress` round-trip validation on `creatorIp` at save time |
| SR-SQLITE-13 | HikariCP: `maximumPoolSize=2`, `connectionTimeout=2000` |
| SR-SQLITE-15 | `MIGRATION.md`: WAL sidecars must be included in backups |
| SR-SQLITE-16 | `MIGRATION.md` + `docs/ADMIN.md`: DB file access classification = same as JVM secrets |
| SR-SQLITE-17 | `@Validated` on `SharePersistenceProperties`; startup `BindException` for any invalid field |
| SR-SQLITE-21 | Frontend creation-success UX: copy-to-clipboard, German "shown once" warning, i18n catalog key |
| SR-SQLITE-23 | ArchUnit: `ShareLink#creatorIp()` callable only from `ShareLinkServiceImpl.create` and SQLite HMAC-write path |

### LOW
| SR | Requirement |
|----|-------------|
| SR-SQLITE-18 | `INSERT OR REPLACE` semantics for `save(link)` — mirrors in-memory `put` idempotency |
| SR-SQLITE-24 | HikariCP `leakDetectionThreshold=10000` with AUDIT logger surface |

---

## Test Plan Summary

### Unit tests (Surefire — `*Test.java`)
- `SqliteShareLinkRepositoryTest` — in-memory SQLite; mirrors `InMemoryShareLinkRepositoryTest` 1:1
- `SqliteShareLinkRepositoryRevocationRaceTest` — `WHERE revoked_at IS NULL` guard
- `ShareLinkRowMapperTest` — null-safe reconstitution
- `ShareLinkTest` (extended) — `fromPersistence` factory (3 scenarios)
- `SqliteSchemaIdempotencyTest` — double-init no-throw
- `SharePersistencePropertiesTest` — `@ConfigurationProperties` binding + validation
- `ShareLinkRepositoryWiringTest` — `ApplicationContextRunner`; both conditional branches
- `SqliteShareLinkRepositoryLoggingTest` — no raw token/IP in message OR argumentArray
- `SqliteShareLinkRepositoryTokenAtRestTest` — inspect raw DB bytes; assert no raw token
- `SqliteShareLinkRepositoryIpAtRestTest` — assert HMAC hex in `creator_ip_hmac` column
- `SqliteShareLinkRepositorySqlInjectionTest` — fuzz SQL-injection strings through all fields
- `SharePersistencePropertiesPathTraversalTest` — `@SafeFilesystemPath` parameterised validation
- `SqliteJdbcChecksumGateTest` — checksum file exists + content matches jar
- `ShareLinkServiceImplCreatorIpValidationTest` — `InetAddress` round-trip at save time
- ArchUnit rules (4 new rules): `sha256Hex` single site, `ShareLinkSummary` no-token, `creatorIp` call-site, no `Statement` in adapter package

### Integration tests (Failsafe — `*IT.java`)
- `SqliteShareLinkRepositoryRestartIT` — **the R-05 acceptance test**: write → close → reopen → assert
- `SqliteShareLinkRepositoryConcurrencyIT` — 32 virtual threads, one ID, race
- `SqliteShareLinkRepositorySweepIT` — mixed rows; sweep; assert counts
- `ShareLinkServiceImplSqliteIT` — full Spring context with `glacier.share.db.path=:memory:`
- `ShareLinkPersistencePropertiesValidationIT` — blank path → `BindException`
- `SqliteShareLinkRepositoryFilePermissionsIT` — POSIX `0600` enforced on new DB file
- `SqliteShareLinkRepositoryPragmaIT` — `PRAGMA synchronous` = FULL (= 2)
- `SqliteShareLinkRepositoryDiskFullIT` — `max_page_count=1` → `CapacityExceededException`
- `ShareLinkRepositoryAuditEmissionIT` — startup AUDIT line with `hash8(path)`
- `SqliteShareLinkRepositoryDegradedSweepIT` — read-only DB mid-test; sweep logs WARN, does not kill scheduler
- `SqliteShareLinkRepositoryConnectionPoolIT` — HikariCP `maximumPoolSize=2`; 3rd concurrent write blocks

---

## Phase 2 Lane Partition

| # | Lane | Agent | Commits / Concerns |
|---|------|-------|--------------------|
| 1 | DevOps | `devops-infra-engineer` | `pom.xml` deps, `dependency-checksums/sqlite-jdbc-<ver>.sha256`, both CI workflow gates |
| 2 | Security | `secure-tdd-implementer` | `@SafeFilesystemPath`, `SharePersistenceProperties`, schema with hashed columns, POSIX permissions, HikariCP config, ArchUnit rules (4), log hygiene tests, HMAC key validation |
| 3 | DDD-TDD | `tdd-ddd-implementer` | `ShareLink.fromPersistence`, `ShareLinkSummary`, port method, `ShareLinkRowMapper`, `SqliteShareLinkRepository` CRUD + revocation + count + sweep + wiring, all unit + integration tests |
| 4 | Docs | any | `docs/ADMIN.md`, `MIGRATION.md`, `docs/decisions/README.md` row |

Sequential per project convention (`feedback_pipeline_sequential_impl` memory).

---

## Resolved Conflicts

### Conflict #1: Token at-rest — domain type split vs. adapter-local hashing
**ddd-tdd-architect**: Adapter-local `sha256Hex()` private method; domain unchanged.
**secure-feature-planner**: Originally proposed `ShareLinkIdHash` domain type; accepted the adaptation
with SR-19 (single call-site ArchUnit rule) as the structural enforcement substitute.
**Resolution (2026-05-11)**: Adapter-local hashing adopted. No domain type split.

### Conflict #2: `findAllBySharer` return type
**ddd-tdd-architect**: Must return a projection because no raw token is available for reconstitution.
**secure-feature-planner**: Confirmed. SR-20 adds ArchUnit rule on `ShareLinkSummary`.
**Resolution (2026-05-11)**: `listSummaryBySharer(String, Instant)` → `List<ShareLinkSummary>`; `readonlyUrl` dropped from management list.

### Conflict #3: `synchronous=NORMAL` vs. `FULL`
**ddd-tdd-architect**: Initially `NORMAL`; adapted to `FULL` globally in R2 on security's recommendation.
**secure-feature-planner**: `FULL` required for revocation durability guarantee.
**Resolution (2026-05-11)**: `synchronous=FULL` globally.

### Conflict #4: `INSERT OR REPLACE` semantics
**secure-feature-planner**: Simple `INSERT` would diverge from in-memory `put`-replaces contract.
**ddd-tdd-architect**: Accepted.
**Resolution (2026-05-11)**: `INSERT OR REPLACE` used; JavaDoc states create-only-but-idempotent.

### Conflict #5: HikariCP pool size 4 vs. 2
**secure-feature-planner**: Proposed 4.
**ddd-tdd-architect**: SQLite WAL = one writer; 4 causes contention; adapted to 2.
**Resolution (2026-05-11)**: `maximumPoolSize=2`.

---

## User Approval

Date: 2026-05-11
Approval message (verbatim): "approve"

---

## Open Risks

| # | Risk | Mitigation |
|---|------|------------|
| R1 | SQLite corruption on NFS shares | `MIGRATION.md` documents "local filesystem only"; startup `FileStore.type()` check deferred to P4 |
| R2 | No migration tool for future schema changes | Documented in `MIGRATION.md`; Flyway/Liquibase deferred until second column addition |
| R3 | HMAC key rotation invalidates IP cap state | Accepted — IP cap is soft anti-DoS, not hard security invariant; documented in `MIGRATION.md` |
| R4 | UX change: URL no longer retrievable from management list | SR-21 adds "shown once" warning + copy-to-clipboard at creation; operator guide updated |
| R5 | `ShareLink.fromPersistence` misuse from non-infrastructure packages | ArchUnit rule (ADR-SQLITE-08) enforces `share.infrastructure.*` caller restriction |

---

## References

- [Quality Review Planning — R-05](2026-05-07-planning-quality-review.md) — original problem statement
- [P3 Backlog Bundle A Planning](2026-05-10-planning-p3-backlog-bundle.md) — explicit deferral to separate pipeline
- [Share-link QR Planning](2026-04-22-planning-share-link-qr.md) — ADR-SHARE-01 (original restart-loss trade-off)
- [Share-link QR Acceptance](2026-04-24-acceptance-share-link-qr.md) — original acceptance
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — A02 (Cryptographic Failures), A03 (Injection), A05 (Security Misconfiguration)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — API4 (Unrestricted Resource Consumption)
- GDPR Art. 25 (Data Protection by Design) — motivation for IP pseudonymisation at rest
