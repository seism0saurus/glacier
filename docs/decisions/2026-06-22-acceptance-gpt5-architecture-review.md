# Decision Record: External Architecture/Security Review (gpt-5.2-pro)

Date: 2026-06-22
Phase: Review + Remediation
Reviewer: OpenAI `gpt-5.2-pro` (two rounds: core modules, then assumption-closing follow-up with the
previously-unseen classes), triaged against the actual code by Claude before any change.
Status: Findings triaged; confirmed items remediated (TDD, `./mvnw verify` green); **F13 accepted as a
known residual (won't-fix) — rationale below.**

## Summary

An external architecture + application-security review of Glacier's core modules (CLAUDE.md context plus
`StompCallback`, `SubscriptionManagerImpl`, `SubscriptionListener`, `WebSocketConfiguration`,
`PrincipalHandler`, the fallback controller/rate-limiter/auth-guard, `MessageCacheImpl`/`PerTagRing`, the
share-link service/domain, and — in round 2 — `DefaultSafeUrlValidator`, `EmbedRestTemplateConfiguration`,
`IframeEmbedPolicy`, the topic-auth + rate-limit interceptors, and the share-view relay/auth). Every finding
was verified against the real code before acting; two were refuted, the rest were fixed or accepted.

## Outcome by finding

| ID | Finding | Verdict (code-checked) | Resolution |
|----|---------|------------------------|------------|
| F1 | Raw `wallId` logged via STOMP `destination` in `MessageCacheImpl` | Confirmed (high) | Fixed — scrubbed structured log; canary test added. Commit `0fe5f48` |
| F3 | Disconnect-timer race could terminate subscriptions after reconnect | Confirmed (medium) | Fixed — cancel prior timer + identity-safe remove. `0fe5f48` |
| F4 / NF2 | Embed HEAD validated then fetched raw host → DNS-rebinding TOCTOU | Partial (redirects already off) | Fixed — connect-time SSRF DNS resolver. `0fe5f48` |
| F11 | `hasRunningDisconnectTimer()` hardcoded `"user1"` | Confirmed (low) | Fixed — parameterized. `0fe5f48` |
| NF1 | Link-less `/share-view-ws` handshake held an idle session | Confirmed (medium) | Fixed properly at the handshake interceptor (not `determineUser`). Commit `e3b57b8` |
| F7 | `static` virtual-thread executors, no lifecycle shutdown | Confirmed (low) | Fixed — bean-owned executor + `@PreDestroy`. `e3b57b8` |
| F5 | Blocking 200 ms retry sleep on the Bigbone streaming thread | Confirmed (medium) | Fixed — single-threaded FIFO publish executor. Commit `b8d4e72` |
| F2 | `wallId` length-only validation → BOLA / topic injection | **Refuted** | `WallTopicAuthInterceptor` enforces exact `principal == destination wallId`; no cross-principal access |
| F12 | iframe embedding only header-gated | **Refuted** | `IframeEmbedPolicy.isEmbeddable` is fail-closed (blank-domain deny, unknown XFO deny, frame-ancestors precedence) |
| F13 | Revocation does not actively disconnect live viewers | Partial | **Accepted — won't-fix (rationale below)** |

(F6/F9/F10 — minor log-noise/robustness nits — noted, not actioned.)

## F13 — Accepted residual: revocation does not server-side-kick live viewers

### Decision
Share-link **revocation** prevents all *future* data access server-side but does **not** forcibly close
already-open viewer WebSocket sockets. This is **accepted as-is.**

### What revocation enforces (server-side, on `ShareLinkRevokedEvent` → `ShareViewStompRelay.onRevoke`)
1. **New handshakes blocked** — `ShareViewPrincipalHandler.determineUser` resolves the link; revoked →
   `null` → HTTP 403 (plus a TOCTOU re-resolve under lock in `registry.register`).
2. **New SUBSCRIBEs blocked** — `ShareViewTopicAuthInterceptor` re-resolves link-active state on every
   SUBSCRIBE frame.
3. **Event feed stops + client asked to leave** — `registry.unregister()` removes the link from the relay
   routing table (no further toots are fan-outed to its topics), and a `{type:"revoked"}` control frame on
   `/topic/share/{id}/control` drives the viewer client to navigate to `/share/:id/expired`
   (`ReadonlyWallComponent.triggerExpiry`, ~1 s SLA per ADR-SHARE-08).

### What is NOT done
No server-side `WebSocketSession` close / STOMP `ERROR` kick. Teardown of an already-open socket is
**client-cooperative** (relies on the browser honoring the control frame).

### Why this is acceptable (threat model)
A malicious viewer that ignores the control frame and stays connected after revoke can:
- receive new toots? **No** — `unregister()` stopped the relay feed to its topics;
- subscribe to anything new? **No** — the topic interceptor rejects every post-revoke SUBSCRIBE;
- re-handshake? **No** — 403 at the handshake;
- keep toots delivered *before* revoke? Yes — unavoidable; revocation cannot un-send already-delivered data.

The entire residual is therefore a **lingering idle socket** that receives nothing — a resource-cleanliness
concern, not a confidentiality one. Socket counts are already bounded by the handshake rate limiter, idle
timeouts, and (now) the NF1 link-required interceptor. The security boundary ("a revoked link grants no
further access") is fully met by gates 1–3.

### Revisit if
- a compliance/SLA requirement mandates hard disconnect within a bounded window; or
- metrics show lingering-socket pressure.

The cleanest minimal implementation, if needed later, is a STOMP `ERROR`-frame kick wired into `onRevoke`
after `pushRevocation` (less bookkeeping than holding `WebSocketSession` handles), with a short grace delay
so well-behaved clients still get the clean `{type:"revoked"}` → `/expired` UX first.

## Verification

All remediations followed TDD (RED→GREEN) and were verified with `./mvnw verify` (unit + integration +
Jacoco) after each batch; the final state passes with 386 integration tests and all coverage checks met.
The streaming→cache→STOMP→browser e2e runs in CI; the single-threaded FIFO publish executor (F5) preserves
per-destination delivery order.
