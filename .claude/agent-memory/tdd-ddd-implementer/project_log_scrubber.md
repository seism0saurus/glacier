---
name: LogScrubber canonical hash contract
description: LogScrubber.hash8 is the single canonical SHA-256-first-8-hex implementation; sentinel values and known pin
type: project
---

## LogScrubber.hash8 — canonical contract (FIX C, ws-fallback)

`LogScrubber.hash8(input)`:
- `null` input → returns `"null"` (string, not null reference)
- blank/whitespace input → returns `"blank"`
- non-blank input → SHA-256 of the string, first 8 hex characters (lowercase)

**Known pin:** `LogScrubber.hash8("my-wall-id")` = `"70c8ebbb"`
(verified: `echo -n "my-wall-id" | sha256sum` = `70c8ebbb0e92e899...`)

**Why:** Four independent implementations existed (LogScrubber, FallbackController, CookieBasedFallbackAuthGuard, MessageCacheImpl). FIX C collapsed them all to `LogScrubber.hash8`. Any new log call that needs a wallId/principal hash must use `LogScrubber.hash8` — never inline SHA-256.

**How to apply:** When adding a new log statement involving `wallId`, `principal`, or any PII-adjacent identifier, always call `LogScrubber.hash8(value)` rather than implementing truncation inline. The `AUDIT` logger also uses this — see glacier-structured-logging-logback skill.
