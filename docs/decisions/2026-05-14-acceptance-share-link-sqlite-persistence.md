# Decision Record: P3-05 Share-Link SQLite Persistence — Phase 3 Acceptance

Date: 2026-05-14
Phase: Acceptance
Agents: security-auditor (Round 1 + Round 2 + re-verification), acceptance-test-auditor (Round 1)
Status: **ACCEPTED — PASSED**

## Summary

P3-05 Share-Link SQLite Persistence is fully accepted. All 17 acceptance criteria
verified PASS. Phase 3 audit raised 3 critical findings and 4 high/medium findings;
all 7 were fixed before sign-off. Security-auditor independently confirmed CRIT-2/F-1
(TOCTOU 0600 window) and CRIT-3/F-2 (JDBC URL injection) as CONFIRMED-FIXED via a
dedicated re-verification pass. Final build: **1 708 unit + 379 IT + 591 Karma — 0
failures; Jacoco ≥ 45%/35% met; BUILD SUCCESS**.

## Acceptance verdict

**PASSED** — User approval received 2026-05-14.

## Evidence

| Dimension | Result |
|---|---|
| Surefire (backend unit) | **1 708 / 1 708 PASS** |
| Failsafe IT (backend integration) | **379 / 379 PASS — BUILD SUCCESS** |
| Karma (Angular unit) | **591 / 591 PASS** |
| Jacoco coverage | **All checks met** (≥ 45 % instruction / ≥ 35 % branch, bundle-wide) |
| All 17 ACs | **PASS** (see table below) |
| Security audit Round 1 | 7 findings raised (3 Critical, 2 High, 3 Medium) |
| Security audit Round 2 | All findings confirmed; CRIT-2/F-1 and CRIT-3/F-2 re-verified CONFIRMED-FIXED |
| FIX REQUEST cycles | 4 cycles (Phase 2 R2 ×2, Phase 3 ×3 agents) — all resolved |

## Verified Acceptance Criteria

| AC | Description | Status | Key evidence |
|---|---|---|---|
| AC-R05 | Share links survive JVM restart (R-05 durability requirement) | PASS | `SqliteShareLinkRepositoryRestartIT` (2 tests); write → close → reopen → assert |
| AC-WIRING | `@ConditionalOnProperty("glacier.share.db.path")` — correct mutual exclusion | PASS | `ShareLinkRepositoryWiringTest` — both branches via `ApplicationContextRunner` |
| AC-TOKEN-AT-REST | Raw token never written to DB; SHA-256(token) stored | PASS | `SqliteShareLinkRepositoryTokenAtRestTest` — raw-DB-bytes inspection |
| AC-IP-AT-REST | Raw IP never in DB; HMAC-SHA256(ip, key) stored | PASS | `SqliteShareLinkRepositoryIpAtRestTest` — raw bytes + deterministic-HMAC test |
| AC-SHA256-GATE | sqlite-jdbc SHA-256 supply-chain gate in both CI workflows | PASS | `dependency-checksums/sqlite-jdbc-3.49.1.0.sha256`; both `verify.yml` + `pull-request.yml` |
| AC-POSIX-0600 | DB file created/opened with POSIX 0600 before HikariCP opens it | PASS | `SqliteDbFileInitializer` BFP; `SqliteShareLinkRepositoryFilePermissionsIT` full lifecycle |
| AC-PRAGMA | `synchronous=FULL`, `foreign_keys=ON` delivered via JDBC URL params to every pool connection | PASS | `SqliteDataSourceConfigPragmaIT` — HikariCP-pooled connection queries confirm values |
| AC-SQL-INJECTION | SQL injection through all write paths rejected | PASS | `SqliteShareLinkRepositorySqlInjectionTest` (20 parameterised cases) |
| AC-LOG-SCRUB | JDBC exceptions from all 8 CRUD methods scrubbed via `LogScrubber.forErrorMessage()` | PASS | `SqliteShareLinkRepositoryLoggingTest` — DROP-TABLE sabotage test |
| AC-AUDIT-WARN | `AUDIT.warn` emitted on sweep JDBC failures; scheduler survives | PASS | `SqliteShareLinkRepositoryDegradedSweepIT` — Mockito stub; assertion load-bearing |
| AC-ARCHUNIT-08 | `ShareLink.fromPersistence()` callers fenced to `share.infrastructure.*` | PASS | `ShareLinkPersistenceArchitectureTest` rule 5 + self-pinning sentinel |
| AC-INETADDR | InetAddress round-trip rejects non-IP `creatorIp` at save time | PASS | `ShareLinkServiceImplCreatorIpValidationTest` (26 cases: IPv4, IPv6, null, malformed) |
| AC-SHOWN-ONCE | SR-SQLITE-21 "shown once" warning in share-dialog creation-success UX | PASS | `share-dialog.component.spec.ts` (4 specs); `messages.de.json` + `messages.en.json` |
| AC-PATH-INJECT | `?`/`#`/URL-encoded variants rejected by `@SafeFilesystemPath` | PASS | `SharePersistencePropertiesPathTraversalTest` (6 injection regression cases) |
| AC-DOCS | `docs/ADMIN.md` + `MIGRATION.md` cover all 9 ADRs operator-visible consequences | PASS | All env vars, POSIX perms, WAL backup, HMAC rotation, URL retrievability documented |
| AC-ADR-INDEX | `docs/decisions/README.md` indexes all P3-05 decision documents | PASS | `AdrIndexCompletenessSentinelTest` — BUILD green |
| AC-BUILD | `./mvnw verify` — BUILD SUCCESS; all threshold checks met | PASS | 1 708 unit + 379 IT + 591 Karma; Jacoco ≥ 45 %/35 % |

## Security Findings — Final Disposition

### Fixed before sign-off

| Finding | SR ref | Severity | Fix |
|---|---|---|---|
| CRIT-2/F-1 TOCTOU 0600 window before HikariCP | SR-SQLITE-07 | Critical | `SqliteDbFileInitializer` `BeanFactoryPostProcessor` creates file with `0600` via `PosixFilePermissions.asFileAttribute` before any bean is instantiated; confirmed by security-auditor re-verification |
| CRIT-3/F-2 JDBC URL `?`/`#` injection | SR-SQLITE-08 | Critical | `SafeFilesystemPathValidator` extended to reject `?`, `#`, `%3F`, `%3f`, `%23`; 6 regression cases; confirmed by security-auditor re-verification |
| F-4/WARN-1 ADR-SQLITE-08 ArchUnit fence missing | ADR-SQLITE-08 | High | 5th ArchUnit rule added to `ShareLinkPersistenceArchitectureTest`; self-pinning sentinel asserts `because("ADR-SQLITE-08: …")` |
| F-5/WARN-2 DegradedSweepIT vacuous AUDIT assert | SR-SQLITE-14 | High | `DegradedSweepIT` rewritten with Mockito `JdbcTemplate` stub; assertion on `auditAppender.list` is now load-bearing |
| WARN-3 `ShareLinkRepositoryWiringTest` absent | ADR-SQLITE-01 | Medium | `ShareLinkRepositoryWiringTest` created; `ApplicationContextRunner` verifies both conditional branches |
| WARN-5 / SR-SQLITE-11 `InetAddress` round-trip absent | SR-SQLITE-11 | Medium | `ShareLinkServiceImpl.validateCreatorIp()` added; `ShareLinkServiceImplCreatorIpValidationTest` (26 cases) |
| WARN-6 / SR-SQLITE-21 "shown once" warning absent | SR-SQLITE-21 | Medium | `role="alert"` paragraph added to share-dialog; i18n keys in `messages.de.json` + `messages.en.json`; 4 Karma specs |

### Deferred (accepted, do not block)

| Finding | Severity | Rationale |
|---|---|---|
| F-3 / SR-SQLITE-22 placeholder-string blocklist | Low | `@Size(min=44)` + `GlacierBindHandler` scrubbing satisfy core SR; denylist is advisory; tracked post-acceptance |
| F-7 / SR-SQLITE-19 wording | Informational | Security goal met (both `sha256Hex` impls are `private`); wording-only deviation; ACCEPTED |
| F-6 `ipHmacKey` not in `FORBIDDEN_LOG_FIELDS` | Informational | Production code never logs it explicitly; 1-line follow-up deferred |
| WARN-4 `SqliteJdbcChecksumGateTest` absent | Low | CI gate authoritative (Phase 2 R4 accepted risk); structural workflow-parsing test deferred |

## Fix Cycles

| Cycle | Agent | Trigger | Outcome |
|---|---|---|---|
| Phase 2 R2 fix 1 | tdd-ddd-implementer | `connectionInitSql` multi-statement drops PRAGMAs 2-4 | JDBC URL params; `SqliteDataSourceConfigPragmaIT` |
| Phase 2 R2 fix 2 | tdd-ddd-implementer | SR-SQLITE-03 CRUD exceptions not scrubbed | 8 CRUD methods wrapped with `LogScrubber.forErrorMessage()` |
| Phase 3 fix cycle 1 | tdd-ddd-implementer | CRIT-1 (ADR index), F-4 (ArchUnit), F-5 (AUDIT assert), WARN-3 (WiringTest) | All 4 resolved; 1 676 unit + 377 IT → 1 676 unit + 377 IT |
| Phase 3 fix cycle 2 | secure-tdd-implementer | CRIT-2 (TOCTOU), CRIT-3 (path inject), WARN-5 (InetAddress) | All 3 resolved; 1 708 unit + 379 IT |
| Phase 3 fix cycle 3 | frontend-designer | WARN-6 (SR-SQLITE-21 i18n warning) | Resolved; 591 Karma +4 |
| Security re-verification | security-auditor | Post-fix confirmation of CRIT-2 + CRIT-3 | Both CONFIRMED-FIXED |

## Open Follow-Up Items

| Item | Severity | Owner |
|---|---|---|
| SR-SQLITE-22 placeholder-string blocklist for `ipHmacKey` | Low | secure-tdd-implementer |
| Add `ipHmacKey` to `LogScrubber.FORBIDDEN_LOG_FIELDS` | Informational | secure-tdd-implementer |
| `SqliteJdbcChecksumGateTest` structural CI-gate assertion | Low | devops-infra-engineer |
| `ShareViewStompRelay` migrate away from deprecated `findAllBySharer` | Medium | tdd-ddd-implementer (before SQLite enabled in production) |
| Parent-directory POSIX mode not corrected when pre-existing | Low | secure-tdd-implementer |
| Process: tighten Phase 2 SR evidence table (file paths per claimed-MET SR) | Medium | Pipeline / `.claude/commands/feature.md` |

## User Approval

Date: 2026-05-14
Approval message (verbatim): "approve"

## Final Sign-Off

P3-05 Share-Link SQLite Persistence is hereby **CLOSED — PASSED**.

The feature delivers durable, restart-proof share-link storage as an opt-in adapter.
Operators set `glacier.share.db.path` to activate it; the in-memory adapter remains
the default. Three defence-in-depth security principles are applied: token and IP
pseudonymisation at rest (SHA-256 / HMAC-SHA256), fail-closed POSIX file permissions
enforced before the connection pool opens, and full ArchUnit fencing of the
reconstitution factory.

Seven Phase 3 findings — two critical, two high, three medium — were all resolved
before acceptance. The two hardest findings (TOCTOU permission window and JDBC URL
parameter injection) required production-code fixes that were independently verified
by the security-auditor. Four deferred items remain open at Low/Informational severity,
all tracked above.

## References

- Planning: `docs/decisions/2026-05-11-planning-share-link-sqlite-persistence.md`
- Implementation: `docs/decisions/2026-05-13-implementation-share-link-sqlite-persistence.md`
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — A02, A03, A04, A05, A09
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — API4, API8
- [sqlite3_prepare_v2 documentation](https://www.sqlite.org/c3ref/prepare.html)
- [HikariCP — fail-fast connection validation](https://github.com/brettwooldridge/HikariCP)
- [CWE-367: TOCTOU Race Condition](https://cwe.mitre.org/data/definitions/367.html)
- [CWE-20: Improper Input Validation](https://cwe.mitre.org/data/definitions/20.html)
- GDPR Art. 25 — Data Protection by Design (motivation for IP pseudonymisation)
