# Architecture Decision Records — Index

This directory contains planning, implementation, and acceptance decision records for Glacier.
Each feature goes through three phases: Planning (P1) → Implementation (P2) → Acceptance (P3).

When writing a new ADR, add a row here in the acceptance commit.

---

## Reliability / Concurrency

### WebSocket HTTP Fallback (`ws-fallback`)
| File | Summary |
|---|---|
| [2026-04-21-planning-ws-fallback.md](2026-04-21-planning-ws-fallback.md) | P1: Design HTTP fallback polling path for when WebSocket/STOMP is blocked |
| [2026-04-21-implementation-ws-fallback.md](2026-04-21-implementation-ws-fallback.md) | P2: Implement `FallbackController`, `MessageCacheImpl`, `FallbackRateLimiter`, and frontend fallback mode |
| [2026-04-22-acceptance-ws-fallback.md](2026-04-22-acceptance-ws-fallback.md) | P3: PASSED — fallback polling, killswitch, insecure-transport modes accepted |

### Rate-Limiter IT Isolation + SessionId Log Hygiene (`dirtiescontext-sessionid-hygiene`)
| File | Summary |
|---|---|
| [2026-05-04-planning-dirtiescontext-sessionid-hygiene.md](2026-05-04-planning-dirtiescontext-sessionid-hygiene.md) | P1: Plan `@DirtiesContext` isolation for rate-limiter ITs and scrub session-ID from logs |
| [2026-05-04-implementation-dirtiescontext-sessionid-hygiene.md](2026-05-04-implementation-dirtiescontext-sessionid-hygiene.md) | P2: Apply `@DirtiesContext`, scrub session-ID via `LogScrubber.sessionId()` |
| [2026-05-05-acceptance-dirtiescontext-sessionid-hygiene.md](2026-05-05-acceptance-dirtiescontext-sessionid-hygiene.md) | P3: PASSED — IT isolation and log hygiene accepted |

### CSRF Cookie Single Emission Fix (`csrf-cookie-single-emission`)
| File | Summary |
|---|---|
| [2026-05-06-planning-csrf-cookie-single-emission.md](2026-05-06-planning-csrf-cookie-single-emission.md) | P1: Plan fix for CSRF cookie being emitted on every request instead of only on first visit |
| [2026-05-06-implementation-csrf-cookie-single-emission.md](2026-05-06-implementation-csrf-cookie-single-emission.md) | P2: Implement idempotent cookie issuance guarded by presence check |
| [2026-05-06-acceptance-csrf-cookie-single-emission.md](2026-05-06-acceptance-csrf-cookie-single-emission.md) | P3: PASSED — single-emission behaviour accepted |

---

## Security

### External Architecture/Security Review — gpt-5.2-pro (`gpt5-architecture-review`)
| File | Summary |
|---|---|
| [2026-06-22-acceptance-gpt5-architecture-review.md](2026-06-22-acceptance-gpt5-architecture-review.md) | Review+Remediation: F1/F3/F4/F11/NF1/F7/F5 fixed (TDD, verify green); F2/F12 refuted; **F13 (no server-side viewer kick on revoke) accepted as a known residual** |

### Pentest Findings F-1 + F-2 (`pentest-findings`)
| File | Summary |
|---|---|
| [2026-04-28-planning-pentest-findings.md](2026-04-28-planning-pentest-findings.md) | P1: Plan remediation of pentest findings F-1 (redirect SSRF) and F-2 (log injection) |
| [2026-04-28-implementation-pentest-findings.md](2026-04-28-implementation-pentest-findings.md) | P2: Disable redirect-following in `RestTemplate`; add `LogScrubber` CWE-117 guards |
| [2026-04-28-acceptance-pentest-findings.md](2026-04-28-acceptance-pentest-findings.md) | P3: PASSED — SSRF redirect block and log-injection mitigations accepted |

### F-6 D-13/SR-8 Raw Logging Cleanup (`f6-log-scrubbing`)
| File | Summary |
|---|---|
| [2026-04-28-planning-f6-log-scrubbing.md](2026-04-28-planning-f6-log-scrubbing.md) | P1: Plan removal of raw cookie, wallId, and access-token values from log output |
| [2026-04-28-implementation-f6-log-scrubbing.md](2026-04-28-implementation-f6-log-scrubbing.md) | P2: Replace raw log arguments with `LogScrubber` hashed/scrubbed variants throughout |
| [2026-04-28-acceptance-f6-log-scrubbing.md](2026-04-28-acceptance-f6-log-scrubbing.md) | P3: PASSED — sensitive-data scrubbing accepted |

### TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing (`td2-technical-failure`)
| File | Summary |
|---|---|
| [2026-04-29-planning-td2-technical-failure.md](2026-04-29-planning-td2-technical-failure.md) | P1: Plan scrubbing of streaming `TechnicalEvent` failure/closing/closed log messages |
| [2026-04-29-implementation-td2-technical-failure.md](2026-04-29-implementation-td2-technical-failure.md) | P2: Scrub `TechnicalEvent` log fields; add corresponding tests |
| [2026-04-29-acceptance-td2-technical-failure.md](2026-04-29-acceptance-td2-technical-failure.md) | P3: PASSED — technical-event log scrubbing accepted |

### TD-4 xFrameOptions Log Hygiene (`td4-xframeoptions`)
| File | Summary |
|---|---|
| [2026-04-30-planning-td4-xframeoptions.md](2026-04-30-planning-td4-xframeoptions.md) | P1: Plan CWE-117 guard for raw `X-Frame-Options` header values in log output |
| [2026-04-30-implementation-td4-xframeoptions.md](2026-04-30-implementation-td4-xframeoptions.md) | P2: Apply `LogScrubber.xfoSummary()` to all `X-Frame-Options` log sites |
| [2026-04-30-acceptance-td4-xframeoptions.md](2026-04-30-acceptance-td4-xframeoptions.md) | P3: PASSED — XFO log hygiene accepted |

### TD-5 CWE-117 Cleanup (`td5-cwe117-cleanup`)
| File | Summary |
|---|---|
| [2026-04-30-planning-td5-cwe117-cleanup.md](2026-04-30-planning-td5-cwe117-cleanup.md) | P1: Plan sweep of remaining CWE-117 log-injection sites not covered by earlier TDs |
| [2026-04-30-implementation-td5-cwe117-cleanup.md](2026-04-30-implementation-td5-cwe117-cleanup.md) | P2: Scrub remaining raw log arguments; add `LogScrubber` helpers as needed |
| [2026-04-30-acceptance-td5-cwe117-cleanup.md](2026-04-30-acceptance-td5-cwe117-cleanup.md) | P3: PASSED — CWE-117 sweep accepted |

### OWASP Coverage Matrix Completion (`owasp-matrix-completion`)
| File | Summary |
|---|---|
| [2026-04-30-planning-owasp-matrix-completion.md](2026-04-30-planning-owasp-matrix-completion.md) | P1: Plan gap-fill to reach full OWASP Top 10 2021 coverage across unit and integration tests |
| [2026-04-30-implementation-owasp-matrix-completion.md](2026-04-30-implementation-owasp-matrix-completion.md) | P2: Add missing OWASP-tagged tests (A01–A10) and structural CI guards |
| [2026-04-30-acceptance-owasp-matrix-completion.md](2026-04-30-acceptance-owasp-matrix-completion.md) | P3: PASSED — full OWASP coverage matrix accepted |

### OWASP Standards Integration + Missing Tests (`owasp-standards-integration`)
| File | Summary |
|---|---|
| [2026-05-01-planning-owasp-standards-integration.md](2026-05-01-planning-owasp-standards-integration.md) | P1: Plan integration of OWASP ASVS/NIST SP 800-53 references into existing tests |
| [2026-05-04-implementation-owasp-standards-integration.md](2026-05-04-implementation-owasp-standards-integration.md) | P2: Annotate tests with OWASP/NIST/CWE references; fill remaining coverage gaps |
| [2026-05-04-acceptance-owasp-standards-integration.md](2026-05-04-acceptance-owasp-standards-integration.md) | P3: PASSED — standards-integration and gap-fill accepted |

### IframeEmbedPolicy Pattern.quote() Regex Bypass (`iframe-embed-pattern-quote`)
| File | Summary |
|---|---|
| [2026-05-05-planning-iframe-embed-pattern-quote.md](2026-05-05-planning-iframe-embed-pattern-quote.md) | P1: Plan fix for missing `Pattern.quote()` in `IframeEmbedPolicy` domain-match regex |
| [2026-05-05-implementation-iframe-embed-pattern-quote.md](2026-05-05-implementation-iframe-embed-pattern-quote.md) | P2: Apply `Pattern.quote()` to domain literal; add regex-bypass fuzz tests |
| [2026-05-05-acceptance-iframe-embed-pattern-quote.md](2026-05-05-acceptance-iframe-embed-pattern-quote.md) | P3: PASSED — regex-bypass fix accepted |

### CORS Configuration Audit
| File | Summary |
|---|---|
| [2026-05-07-cors-audit.md](2026-05-07-cors-audit.md) | Point-in-time audit of CORS allowlist — no misconfiguration found (Sec-17/P2-16) |

---

## CI/CD

### Locale.ROOT Correctness + npm audit CI Gate (`locale-root-npm-audit`)
| File | Summary |
|---|---|
| [2026-05-05-planning-locale-root-npm-audit.md](2026-05-05-planning-locale-root-npm-audit.md) | P1: Plan `Locale.ROOT` fix for case-folding correctness and add `npm audit` CI gate |
| [2026-05-05-implementation-locale-root-npm-audit.md](2026-05-05-implementation-locale-root-npm-audit.md) | P2: Replace `toLowerCase()` with `toLowerCase(Locale.ROOT)`; add `security.yml` npm audit job |
| [2026-05-05-acceptance-locale-root-npm-audit.md](2026-05-05-acceptance-locale-root-npm-audit.md) | P3: PASSED — locale fix and npm-audit gate accepted |

---

## Frontend / UX / a11y / i18n

### Structural A11y Fixes — Shell & Share Dialog Batch 1 (`a11y-structural-batch-1`)
| File | Summary |
|---|---|
| [2026-06-16-a11y-ux-audit.md](2026-06-16-a11y-ux-audit.md) | Audit: WCAG 2.2 AA + Nielsen a11y/UX audit across all 24 Angular components |
| [2026-06-16-a11y-structural-batch-1.md](2026-06-16-a11y-structural-batch-1.md) | P2: SHELL-01/02 `<main>` landmark + skip link; SHELL-07 live-region double-announce; SHELL-06 `role="document"` + Material aria-expanded; SHELL-09 hover contrast; TOOT-04 `aria-haspopup="dialog"`; TOOT-05 copy-button min-size; TOOT-07 shown-once `aria-describedby` |

### Share Link with QR Code (`share-link-qr`)
| File | Summary |
|---|---|
| [2026-04-22-planning-share-link-qr.md](2026-04-22-planning-share-link-qr.md) | P1: Design read-only share view with QR code, accessible toot rendering, and 7-day TTL |
| [2026-04-23-implementation-share-link-qr.md](2026-04-23-implementation-share-link-qr.md) | P2: Implement share service, QR dialog, `share.{domain}` routing, and accessible feed |
| [2026-04-23-resume-handoff-share-link-qr.md](2026-04-23-resume-handoff-share-link-qr.md) | Mid-session handoff note for Share Link pipeline pause |
| [2026-04-24-acceptance-share-link-qr.md](2026-04-24-acceptance-share-link-qr.md) | P3: PASSED — share link, QR code, and accessible share view accepted |

### Hashtag Prune on Removal (`hashtag-prune`)
| File | Summary |
|---|---|
| [2026-04-24-planning-hashtag-prune.md](2026-04-24-planning-hashtag-prune.md) | P1: Plan server-side subscription teardown and cache pruning when a hashtag is removed |
| [2026-04-24-implementation-hashtag-prune.md](2026-04-24-implementation-hashtag-prune.md) | P2: Implement `terminateSubscription` and `pruneHashtag` on STOMP termination message |
| [2026-04-24-acceptance-hashtag-prune.md](2026-04-24-acceptance-hashtag-prune.md) | P3: PASSED — hashtag prune and cache cleanup accepted |

---

## Refactoring

### SubscriptionService Split (`subscription-service-split`)
| File | Summary |
|---|---|
| [2026-05-08-planning-subscription-service-split.md](2026-05-08-planning-subscription-service-split.md) | P1: Plan split of monolithic `SubscriptionService` into `StompSubscriptionService`, `SubscriptionStoreService`, and `SubscriptionStompClient` |
| [2026-05-10-implementation-subscription-service-split.md](2026-05-10-implementation-subscription-service-split.md) | P2: Extract three focused services; migrate all callers; apply ESLint DAG layer rules |
| [2026-05-10-acceptance-subscription-service-split.md](2026-05-10-acceptance-subscription-service-split.md) | P3: PASSED — service split and ESLint enforcement accepted |

### TD-3 normaliseEditedAt Log Hygiene (`td3-normaliseeditedat`)
| File | Summary |
|---|---|
| [2026-04-30-planning-td3-normaliseeditedat.md](2026-04-30-planning-td3-normaliseeditedat.md) | P1: Plan hygiene fix to stop logging raw `editedAt` timestamps |
| [2026-04-30-implementation-td3-normaliseeditedat.md](2026-04-30-implementation-td3-normaliseeditedat.md) | P2: Remove raw `editedAt` log arguments; use controlled summary format |
| [2026-04-30-acceptance-td3-normaliseeditedat.md](2026-04-30-acceptance-td3-normaliseeditedat.md) | P3: PASSED — `editedAt` log hygiene accepted |

### TD Backlog Bundle (F-5, F-6, F-7, F-9, OBS-1) (`td-backlog-bundle`)
| File | Summary |
|---|---|
| [2026-05-01-planning-td-backlog-bundle.md](2026-05-01-planning-td-backlog-bundle.md) | P1: Plan bundle of TD follow-ups: timeout config, virtualthread observability, embed HEAD metrics |
| [2026-05-01-implementation-td-backlog-bundle.md](2026-05-01-implementation-td-backlog-bundle.md) | P2: Implement configurable embed timeouts, virtual-thread Micrometer gauges, HEAD request metrics |
| [2026-05-01-acceptance-td-backlog-bundle.md](2026-05-01-acceptance-td-backlog-bundle.md) | P3: PASSED — TD backlog bundle accepted |

### TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle (`cleanup-bundle`)
| File | Summary |
|---|---|
| [2026-04-30-planning-cleanup-bundle.md](2026-04-30-planning-cleanup-bundle.md) | P1: Plan follow-up cleanup of TD-5 log sites and F-6-INFO-2 constant renaming |
| [2026-04-30-implementation-cleanup-bundle.md](2026-04-30-implementation-cleanup-bundle.md) | P2: Rename `StompEventType` constants; remove final raw log sites |
| [2026-04-30-acceptance-cleanup-bundle.md](2026-04-30-acceptance-cleanup-bundle.md) | P3: PASSED — cleanup bundle accepted |

---

## Documentation / Housekeeping

### New Testcases (`new-testcases`)
| File | Summary |
|---|---|
| [2026-04-27-planning-new-testcases.md](2026-04-27-planning-new-testcases.md) | P1: Plan additional unit and integration tests for uncovered code paths |
| [2026-04-27-implementation-new-testcases.md](2026-04-27-implementation-new-testcases.md) | P2: Add missing tests to reach Jacoco thresholds |
| [2026-04-27-acceptance-new-testcases.md](2026-04-27-acceptance-new-testcases.md) | P3: PASSED — new test cases accepted |

### TD-1 JsonProcessingException Logging Fix (`td1-json-parse-exception`)
| File | Summary |
|---|---|
| [2026-04-28-planning-td1-json-parse-exception.md](2026-04-28-planning-td1-json-parse-exception.md) | P1: Plan fix for `JsonProcessingException` message leaking raw JSON into log output |
| [2026-04-28-implementation-td1-json-parse-exception.md](2026-04-28-implementation-td1-json-parse-exception.md) | P2: Catch and scrub exception message before logging |
| [2026-04-28-acceptance-td1-json-parse-exception.md](2026-04-28-acceptance-td1-json-parse-exception.md) | P3: PASSED — exception log scrubbing accepted |

### F-6-INFO-1 KNOWN_STREAM_EVENTS Update (`f6info1-known-stream-events`)
| File | Summary |
|---|---|
| [2026-04-30-planning-f6info1-known-stream-events.md](2026-04-30-planning-f6info1-known-stream-events.md) | P1: Plan update to `KNOWN_STREAM_EVENTS` set to reflect current Bigbone event types |
| [2026-04-30-implementation-f6info1-known-stream-events.md](2026-04-30-implementation-f6info1-known-stream-events.md) | P2: Add missing event-type strings to the known-events constant |
| [2026-04-30-acceptance-f6info1-known-stream-events.md](2026-04-30-acceptance-f6info1-known-stream-events.md) | P3: PASSED — event-set update accepted |

### F-6-INFO-2 Event-Type Constants (`f6info2-event-type-constants`)
| File | Summary |
|---|---|
| [2026-04-30-planning-f6info2-event-type-constants.md](2026-04-30-planning-f6info2-event-type-constants.md) | P1: Plan introduction of typed `EventType` enum to replace magic strings |
| [2026-04-30-implementation-f6info2-event-type-constants.md](2026-04-30-implementation-f6info2-event-type-constants.md) | P2: Introduce `EventType` enum; migrate all string literals |
| [2026-04-30-acceptance-f6info2-event-type-constants.md](2026-04-30-acceptance-f6info2-event-type-constants.md) | P3: PASSED — event-type constants accepted |

### F-6-FU-3 T5b Canary (`f6fu3-t5b-canary`)
| File | Summary |
|---|---|
| [2026-04-29-planning-f6fu3-t5b-canary.md](2026-04-29-planning-f6fu3-t5b-canary.md) | P1: Plan canary test (T5b) to guard `StompCallback` AUDIT log path against regression |
| [2026-04-30-implementation-f6fu3-t5b-canary.md](2026-04-30-implementation-f6fu3-t5b-canary.md) | P2: Add T5b canary test to `StompCallbackTest` |
| [2026-04-30-acceptance-f6fu3-t5b-canary.md](2026-04-30-acceptance-f6fu3-t5b-canary.md) | P3: PASSED — T5b canary accepted |

### Fuzz & Mutation Testing (`fuzz-mutation-testing`)
| File | Summary |
|---|---|
| [2026-05-01-planning-fuzz-mutation-testing.md](2026-05-01-planning-fuzz-mutation-testing.md) | P1: Plan jqwik property-based fuzz tests and PIT mutation testing for critical paths |
| [2026-05-01-implementation-fuzz-mutation-testing.md](2026-05-01-implementation-fuzz-mutation-testing.md) | P2: Implement jqwik fuzz tests for `IframeEmbedPolicy`, `LogScrubber`, and `HashtagFormat` |
| [2026-05-01-acceptance-fuzz-mutation-testing.md](2026-05-01-acceptance-fuzz-mutation-testing.md) | P3: PASSED — fuzz and mutation testing accepted |

### jqwik Fuzz-Test Flake Fix (`fuzz-flake-fix`)
| File | Summary |
|---|---|
| [2026-05-04-planning-fuzz-flake-fix.md](2026-05-04-planning-fuzz-flake-fix.md) | P1: Plan fix for intermittent jqwik fuzz test failures (seed determinism) |
| [2026-05-04-implementation-fuzz-flake-fix.md](2026-05-04-implementation-fuzz-flake-fix.md) | P2: Pin jqwik seed; add `@Report` annotation for reproducibility |
| [2026-05-04-acceptance-fuzz-flake-fix.md](2026-05-04-acceptance-fuzz-flake-fix.md) | P3: PASSED — fuzz flake fix accepted |

### Comprehensive Quality Review (`quality-review`)
| File | Summary |
|---|---|
| [2026-05-07-planning-quality-review.md](2026-05-07-planning-quality-review.md) | P1: Plan 46-item quality review covering security, architecture, testing, and observability |
| [2026-05-08-acceptance-quality-review.md](2026-05-08-acceptance-quality-review.md) | P3: PASSED — quality review accepted; 3 items deferred to backlog |

### P3 Backlog Bundle A (`p3-backlog-bundle`)
| File | Summary |
|---|---|
| [2026-05-10-planning-p3-backlog-bundle.md](2026-05-10-planning-p3-backlog-bundle.md) | P1: Plan housekeeping bundle: CodeQL cache-key fix, Javadoc typos, README improvements, ADR index, ADMIN.md key-rename note |
| [2026-05-11-implementation-p3-backlog-bundle.md](2026-05-11-implementation-p3-backlog-bundle.md) | P2: Implement `MastodonShortHandle`, `MastodonHandleFactory`, `EventTypeMapping`, `MastodonProperties`; wire into `StompCallback`, `SubscriptionManagerImpl`, `InformationController`; `LogScrubber.forErrorMessage()`; ArchUnit exclusivity gate; CI/docs housekeeping |
| [2026-05-11-acceptance-p3-backlog-bundle.md](2026-05-11-acceptance-p3-backlog-bundle.md) | P3: PASSED — 24/24 AC; 4 security findings (SEC-P3A-01/02/04/05) resolved; 337 IT, 0 failures |

### P3 Backlog Bundle B (`p3-bundle-b`)
| File | Summary |
|---|---|
| [2026-05-11-planning-p3-bundle-b.md](2026-05-11-planning-p3-bundle-b.md) | P1: Plan TD-P3A-DOMAIN-FIX (static-literal fix for DomainSafetyValidator.java:98) + GlacierCookieProperties atomic migration of 7 glacier.cookie.secure @Value consumers |
| [2026-05-12-implementation-p3-bundle-b.md](2026-05-12-implementation-p3-bundle-b.md) | P2: Lane A static-message fix + A1/A1b/A1c/A4 tests; Lane B GlacierCookieProperties + all 7 consumer migrations + B1–B5/B4a tests; 345 IT, 0 failures |
| [2026-05-12-acceptance-p3-bundle-b.md](2026-05-12-acceptance-p3-bundle-b.md) | P3: PASSED — 14/14 AC; 3 medium findings (F-3/F-10/F-14) deferred; 345 IT, 0 failures |

### P3-05 Share-link SQLite Persistence (`share-link-sqlite-persistence`)
| File | Summary |
|---|---|
| [2026-05-11-planning-share-link-sqlite-persistence.md](2026-05-11-planning-share-link-sqlite-persistence.md) | P1: Plan opt-in SQLite adapter for share-link durability; adapter-local token hashing; ShareLinkSummary projection; 24 security requirements |
| [2026-05-13-implementation-share-link-sqlite-persistence.md](2026-05-13-implementation-share-link-sqlite-persistence.md) | P2: Implement SQLite persistence adapter; token + IP at-rest hashing; HikariCP pool; ArchUnit gates; scheduled sweep |
| [2026-05-14-acceptance-share-link-sqlite-persistence.md](2026-05-14-acceptance-share-link-sqlite-persistence.md) | P3: PASSED — 17/17 AC; 7 findings fixed (TOCTOU 0600, JDBC URL injection, ArchUnit fence, AUDIT assert, WiringTest, InetAddress, i18n warning); 1708 unit + 379 IT, 0 failures |

### ShareViewStompRelay Migration (`share-view-stomp-relay-migration`)
| File | Summary |
|---|---|
| [2026-05-15-planning-share-view-stomp-relay-migration.md](2026-05-15-planning-share-view-stomp-relay-migration.md) | P1: Plan event-driven registry (`ShareLinkActivityRegistry`) to replace deprecated `listBySharer()` call in `ShareViewStompRelay`; removes SQLite production blocker |
| [2026-05-15-implementation-share-view-stomp-relay-migration.md](2026-05-15-implementation-share-view-stomp-relay-migration.md) | P2: Registry + events + handshake reorder + Angular ReadonlyWallStompClient; 1742 unit + 383 IT + 616 Karma — 0 failures; BUILD SUCCESS |
| [2026-06-15-acceptance-share-view-stomp-relay-migration.md](2026-06-15-acceptance-share-view-stomp-relay-migration.md) | P3: PASSED — 2 blocking findings fixed (SEC-ACC-01 counter-leak refund on `register()` throw; SEC-ACC-02 exponential reconnect backoff); SR-RELAY 23/23, 7/7 ArchUnit; 1743 unit + 383 IT + 618 Karma, 0 failures; live-relay Playwright e2e to run in CI before merge |

### Accessibility & Usability Audit (`a11y-ux-audit`)
| File | Summary |
|---|---|
| [2026-06-16-a11y-ux-audit.md](2026-06-16-a11y-ux-audit.md) | Audit: full-app WCAG 2.2 AA + Nielsen review (4 parallel ux-ui-designer audits). 1 Critical (VIEW-01 FALLBACK revoke never detected), German-source i18n inversion (GDPR page + chrome render English), share-viewer live-semantics gaps, axe coverage holes. Remediation sequenced; Critical fixed first. |

---
