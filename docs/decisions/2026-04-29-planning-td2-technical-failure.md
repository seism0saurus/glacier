# Decision Record: TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing — Planning

Date: 2026-04-29
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

`StompCallback.processTechnicalEvent` logs three peer-controlled strings verbatim at INFO level: `failure.getError().getMessage()` (line 532), `closing.toString()` which embeds `closing.reason` (line 527), and `closed.toString()` which embeds `closed.reason` (line 529). All three are CWE-117 violations under TD1-INV-1. Fix replaces them with bounded, JVM-controlled tokens: exception simple class name (Failure) and numeric close code (Closing/Closed). The `reason` field (RFC 6455 §5.5.1, up to 123 peer-controlled UTF-8 bytes) is dropped entirely.

## Key Decisions

### ADR-TD2-01: Apply TD1-INV-1 fix pattern to TechnicalEvent.Failure
**Decision**: Replace `failure.getError().getMessage()` with `failure.getError().getClass().getSimpleName()`. New log format: `"got a Failure event. Restarting subscription. exception=<SimpleClassName>"`. No `Throwable` SLF4J argument. Production fix is unconditional.
**Rationale**: OkHttp's `Throwable.getMessage()` embeds remote host/port, TCP/TLS error text, and peer-controlled GOAWAY reason strings. Mirrors ADR-TD1-01 (JsonProcessingException). `getSimpleName()` is JVM-controlled, not peer-influenced.
**Alternatives considered**: Bounded `getMessage()` prefix (retains attacker bytes); demote to DEBUG with Throwable arg (Logback still renders throwable proxy); Logback converter (too coarse).
**Source**: ddd-tdd-architect Round 1.

### ADR-TD2-02: TD1-INV-1 confirmed without extension — TD-2 is the closing fix
**Decision**: TD1-INV-1 as written covers `TechnicalEvent.Failure` (exception-handling path via event object, not catch block). No rewording required. TD-2 is the third application.
**Rationale**: "Exception-handling paths" already encompasses event-wrapped Throwables.
**Source**: ddd-tdd-architect Round 1; secure-feature-planner Round 1.

### ADR-TD2-03: Bundle Closing + Closed into TD-2; defer Open + default to TD-4
**Decision**: TD-2 fixes Failure (line 532), Closing (line 527), and Closed (line 529). `Open` (line 525) and `default` (line 536) deferred to TD-4.
**Rationale**: `Closing.reason` and `Closed.reason` are peer-controlled UTF-8 strings under RFC 6455 §5.5.1, same CWE-117 class as Failure. Higher base-rate than Failure (24h connection rotation fires Closing/Closed regularly). Five lines from the original fix target, identical fix shape. `Open` uses TLS-validated handshake (lower exposure); `default` is hypothetical.
**Alternatives considered**: Defer all four to TD-4 (rejected — leaves higher-base-rate exposures open); bundle all five (rejected — Open/default are structurally different, dilute scope).
**Source**: Resolved CONFLICT-TD2-01 — secure-feature-planner Round 1 raised, ddd-tdd-architect Round 2 accepted.

### ADR-TD2-04: No production allowlist gate; test-only class-literal parameter source
**Decision**: No production `Set<Class<?>>` or `Set<String>` gate. Production fix is unconditional `getSimpleName()` logging. Test parameter source uses `List<Class<? extends Throwable>>` class literals (`EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS`, 15 entries).
**Rationale**: `getSimpleName()` is already JVM-controlled — peer cannot influence which class name is logged. A production gate adds branch complexity with no marginal security benefit. Class-literal test source eliminates typo risk.
**Alternatives considered**: Production `Set<Class<?>>` gate (rejected — defends against non-threat); production `Set<String>` gate (rejected — spoof-vulnerable and still defends against non-threat).
**Source**: Resolved CONFLICT-TD2-02 — secure-feature-planner raised, architect clarified no gate exists, security confirmed that satisfies threat model.

## Production Code Changes

| File | Line | Before | After |
|------|------|--------|-------|
| `mastodon/StompCallback.java` | 527 | `logEvent("got a Closing event: %s".formatted(closing))` | `logEvent("got a Closing event — code=%d".formatted(closing.getCode()))` |
| `mastodon/StompCallback.java` | 529 | `logEvent("got a Closed event: %s".formatted(closed))` | `logEvent("got a Closed event — code=%d".formatted(closed.getCode()))` |
| `mastodon/StompCallback.java` | 532 | `logEvent("got a Failure event. Restarting subscription. The error is: %s".formatted(failure.getError().getMessage()))` | `logEvent("got a Failure event. Restarting subscription. exception=%s".formatted(failure.getError().getClass().getSimpleName()))` |

## Security Requirements

| SR | Requirement | Test |
|----|-------------|------|
| SR-TD2-01 | No log line from Failure/Closing/Closed branches contains any peer-controlled byte | T-B1 (all 7 variants) |
| SR-TD2-02 | Failure log at INFO via `logEvent`; structured `exception=<SimpleName>` | T-A1, T-A3 |
| SR-TD2-03 | Legacy `"The error is: %s"` format fully removed | T-A3, grep gate |
| SR-TD2-04 | Assertions check `getArgumentArray()` AND `getThrowableProxy()` | T-A4, T-B1 |
| SR-TD2-05 | TD1-INV-1 confirmed (not extended) for TD-2 scope | This document, ADR-TD2-02 |
| SR-TD2-06 | Mode-discipline neutrality — live/fallback/killswitch/insecure no control-flow change | Inspection + acceptance doc sentence |
| SR-TD2-07 | Existing F-6 + TD-1 canaries not regressed | Full `StompCallbackTest` suite |
| SR-TD2-08 | `getClass().getSimpleName()` drawn from bounded `EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS` test parameter | T-A2, T-B1 |
| SR-TD2-09 | `getThrowableProxy() == null` strict — no Throwable SLF4J arg | T-A4, T-B1 |
| SR-TD2-10 | CRLF injection via OkHttp/close-reason string produces no spoofed log line | T-B1[CRLF] |
| SR-TD2-11 | ANSI escape sequence does not reach log sink | T-B1[ANSI] |
| SR-TD2-12 | MDC clean + AUDIT zero events after any Failure/Closing/Closed event | T-A5 |
| SR-TD2-13 | Restart calls (`terminateSubscription` + `subscribeToHashtag`) still fire after fix | T-A6 |
| SR-TD2-14 | Closing/Closed log `code=<int>` only; `reason` field fully absent from all log channels | T-A4, T-A5, T-B1 |
| SR-TD2-15 | Sibling TD-1 + TD-3-pending canaries not regressed by the diff | Full suite |

## Test Plan

### Lane A — `tdd-ddd-implementer` (production fix + shape tests)

| ID | Given / When / Then |
|----|---------------------|
| T-A1 | Failure + `IOException("connection reset by peer")` → log contains `exception=IOException`; does NOT contain `connection reset by peer` |
| T-A2 | Failure + `SocketTimeoutException("Read timed out: malicious-host/1.2.3.4")` → log contains `exception=SocketTimeoutException`; hostname not in log |
| T-A3 | Failure + `EOFException(null)` → log contains `exception=EOFException`; no NPE, no `null` token; prefix `"got a Failure event. Restarting subscription. exception="` present |
| T-A4 | Failure with any Throwable → `getThrowableProxy() == null` for all captured events |
| T-A5 | Closing with `reason="<script>alert(1)</script>"`, code=1006 → log contains `code=1006`; does NOT contain `<script>`; Closed with `reason="\n injected"`, code=1011 → log contains `code=1011`; NOT `injected` |
| T-A6 | Failure + any Throwable → `terminateSubscription` + `subscribeToHashtag` both invoked once; **replaces existing `onEvent_EventTechnicalFailure` at StompCallbackTest.java:1134–1154** |

**Test constant** (to add near existing `ACCEPTABLE_PARSE_EXCEPTIONS`):
```java
private static final List<Class<? extends Throwable>> EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS = List.of(
        java.io.IOException.class,
        java.io.EOFException.class,
        java.io.InterruptedIOException.class,
        java.net.SocketTimeoutException.class,
        java.net.ConnectException.class,
        java.net.SocketException.class,
        java.net.UnknownHostException.class,
        java.net.ProtocolException.class,
        java.nio.channels.ClosedChannelException.class,
        javax.net.ssl.SSLException.class,
        javax.net.ssl.SSLHandshakeException.class,
        javax.net.ssl.SSLPeerUnverifiedException.class,
        javax.net.ssl.SSLProtocolException.class,
        okhttp3.internal.http2.StreamResetException.class,
        okhttp3.internal.http2.ConnectionShutdownException.class
);
private static final String CANARY_FRAGMENT_TD2 =
        "__GLACIER_TD2_CANARY_" + UUID.randomUUID() + "__";
```

### Lane B — `secure-tdd-implementer` (injection canary)

| ID | Name | Variants |
|----|------|----------|
| T-B1 | `failureEvent_doesNotLeakAttackerControlledFragment` (`@ParameterizedTest`) | (a) ASCII `CANARY_FRAGMENT_TD2`, (b) host:port `"failed to connect to attacker.example.com:31337"`, (c) CRLF `"\r\nFAKE_LOG_LINE: spoofed"`, (d) ANSI `"[31mRED_INJECT[0m"`, (e) null-byte `" NULL_INJECT"`, (f) HTTP/2 GOAWAY `"http2 connection error: PROTOCOL_ERROR (code=1) host=victim.example"`, (g) JSON-breakout `"\"}\n\"injected\":\"value"` |

For each variant: `getFormattedMessage()` clean, `getArgumentArray()` clean (deep-string), `getThrowableProxy() == null`, MDC clean, AUDIT zero events.

### Lane order (sequential per feedback_pipeline_sequential_impl)

Lane A first (production fix + shape tests), then Lane B (injection canary against fixed code), then Round 2 joint review.

## Resolved Conflicts

### CONFLICT-TD2-01: Closing/Closed scope
**ddd-tdd-architect**: Defer Closing/Closed to TD-4 — structurally different from TD1-INV-1 (event toString vs exception message).
**secure-feature-planner**: Bundle into TD-2 — same CWE-117 class, higher base-rate, 5 lines away.
**Resolution (2026-04-29)**: "approve" — bundled into TD-2 per security team recommendation.

### CONFLICT-TD2-02: Allowlist type (production vs. test)
**ddd-tdd-architect**: No production gate; allowlist is test-only `List<Class<? extends Throwable>>`.
**secure-feature-planner**: If production gate exists, use `Set<Class<?>>`.
**Resolution (2026-04-29)**: "approve" — no production gate. Test-only class-literal parameter source confirmed correct by both agents.

## User Approval

Date: 2026-04-29
Approval message (verbatim): "approve"

## Open Risks

- `TechnicalEvent.Open` (line 525) and `default` (line 536) remain unresolved until TD-4. `Open` is a data object (no fields). `default` uses `event.getClass()` (type-safe). Low priority.
- `EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS` (15 entries) may not cover every OkHttp subclass on a future dep bump. Production fix is unconditional so this affects only test coverage, not security.

## References

- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (F-6-FU-2 origin)
- TD-1 planning: `docs/decisions/2026-04-28-planning-td1-json-parse-exception.md` (sibling reference)
- TD-1 acceptance: `docs/decisions/2026-04-28-acceptance-td1-json-parse-exception.md`
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
- RFC 6455 §5.5.1 (WebSocket Close frame reason field)
- NIST SP 800-53 SI-11: Error Handling
