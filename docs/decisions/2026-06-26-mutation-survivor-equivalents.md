# Decision Record: PITest Mutation-Survivor Census & Equivalents (SR-FUZZ-13)

Date: 2026-06-26
Phase: Follow-up (closes deferred item SR-FUZZ-13 from `2026-05-01-acceptance-fuzz-mutation-testing.md`)
Status: Accepted (documentation + targeted gap-closure)

## Summary

SR-FUZZ-13 was deferred at the 2026-05-01 fuzz/mutation acceptance with the action *"document
mutation-survivor equivalents after first PITest CI run on main"*, and the ADR predicted
*"~2–4 equivalent survivors in `LogScrubber` boundary arithmetic and `FallbackRateLimiter`
comparisons."*

The first full PITest run (`./mvnw -P mutation`, 2026-06-26) tells a different and more useful
story: **241 surviving mutants**, of which the overwhelming majority are **killable coverage
gaps, not equivalent mutants**. True equivalents are a small subset. This record documents the
census, classifies the survivors, closes the highest-value security gap (the SSRF private-IP
classifier), and registers the rest as a prioritized, tracked backlog rather than mislabelling
gaps as "equivalent".

Mislabelling a killable gap as "equivalent" is the dangerous error — it permanently excuses a
real test hole — so the classification below is deliberately conservative: a survivor is only
called *equivalent* when no test could observe the mutation.

## First-run census (PITest, 2026-06-26, profile `mutation`, 14 ADR-FUZZ-04 target classes)

```
Generated 1172 mutations · KILLED 850 · TIMED_OUT 6 · SURVIVED 241 · NO_COVERAGE 75
Line coverage (mutated classes) 93% · Test strength 78% · Killed (incl. timeouts) 73%
```

The 70% PIT threshold (pom `mutation` profile) is **met** — the build is green; this is about
the residual 22%.

Survivors by class (first run):

| Class | Survivors | Dominant theme |
|---|---:|---|
| `DefaultSafeUrlValidator` | 54 | host obfuscation log-format, bidi/control scan boundaries, validate() re-guards |
| `IpAddressClassifier` | 50 | **SSRF**: hex-colon IPv4-mapped decode + CGNAT boundaries (uncovered) |
| `ShareRateLimiter` | 23 | `evictStaleBuckets` map/`removeIf` calls + 600s TTL boundary |
| `SubscribeRateLimitInterceptor` | 19 | eviction calls + log-scrub call removal + extractIp |
| `LogScrubber` | 17 | `maskIp`/`urlHostHash`/`forErrorMessage` output not asserted |
| `IframeEmbedPolicy` | 16 | XFO equalsIgnoreCase short-circuits, blank-domain re-guard |
| `ShareImageProxyUrlBuilder` | 16 | HMAC sign/verify constants (validity secs, base64 padding) |
| `ShareSecurityHeadersFilter` | 13 | `setHeader` calls + isSharePath conditionals |
| `FallbackRateLimiter` | 10 | eviction + bucket-count return |
| `HandshakeRateLimitInterceptor` | 9 | eviction + extractIp |
| `ShareViewerId` | 8 | `<init>` validation re-guards, `hashCode`/`equals` |
| `ShareLinkId` | 5 | `<init>` validation re-guards, `hashCode` |
| `SecureRandomTokenGenerator` | 1 | token byte-length constant |

## Classification

### Tier A — Equivalent / behaviourally-unobservable (cannot be killed)

These survive because the mutation produces no observable behaviour change on any reachable path:

- **Fail-secure-masked string normalization** — `NakedReceiverMutator` on `trim()`/`toLowerCase()`
  in `IpAddressClassifier.isPrivate`/`isBlockedInetAddress` (L83/L85/L90/L141) and
  `DefaultSafeUrlValidator` where, when the mutation changes the parse, the address becomes
  *unresolvable* and the method still returns the fail-secure verdict (blocked). The before/after
  outcome is identical, so no assertion can distinguish them. (A subset *was* killable where
  normalization flips an allow/deny outcome — those are closed below, e.g. the uppercase-prefix
  case.)
- **Defensive double-guards** — second `isBlank()`/length re-checks in value-object constructors
  (`ShareLinkId`/`ShareViewerId` `<init>`) and `IframeEmbedPolicy` after the caller has already
  guaranteed the precondition: the mutated branch is unreachable with the inputs the type system
  and callers allow.
- **Hash composition** — `removed call to Objects::hash` / `PrimitiveReturnsMutator` on
  `hashCode()`: the hash contract permits any distribution, so a different (even constant) hash is
  contract-equivalent; `equals()` carries the identity semantics and *is* covered/killed.

### Tier B — Killable coverage gaps (tracked TD, prioritized by security impact)

Not equivalent — a test could kill these; coverage is simply missing.

| Priority | Area | Gap | Why it matters |
|---|---|---|---|
| **P1 — closed this run** | `IpAddressClassifier` hex-colon IPv4-mapped (`hexColonToIpv4`) + CGNAT boundaries | No test fed `0:0:0:0:0:ffff:7f00:0001`-style addresses or exact `100.64/100.127` boundaries | SSRF private-IP blocklist — a private IP disguised in hex-colon form was unverified |
| P2 | Rate-limiter `evictStaleBuckets` (Share/Fallback/Handshake/Subscribe) | `removeIf`/`entrySet`/`600→601` survive | Memory hygiene; the limit *decision* is covered, eviction timing is not |
| P2 | `LogScrubber.maskIp`/`urlHostHash`/`forErrorMessage` | exact masked output not asserted | D-13/SR-8 privacy masking is a security control where output shape matters |
| P3 | `ShareImageProxyUrlBuilder` constants (`604800` validity, base64 padding) + `verify` length guards | exact values/boundaries not asserted | HMAC token expiry / malformed-token rejection |
| P3 | `ShareSecurityHeadersFilter.applyShareSecurityHeaders` `setHeader` removals | no test asserts each header present | header presence is the control |
| P3 | `SecureRandomTokenGenerator` token byte-length (`32`) | exact length not asserted | token entropy |

## Action taken this run: SSRF classifier gap closed (P1)

`IpAddressClassifier` had **50 survivors** and *zero* coverage of the hex-colon IPv4-mapped
decode path (`hexColonToIpv4`) and CGNAT boundary arithmetic — both load-bearing for the SSRF
private-IP blocklist. Added behavioural tests to `IpAddressClassifierTest` (TDD, 13 new cases):

- CGNAT inclusive boundaries (`100.64.0.0`, `100.127.255.255`) and just-outside public hosts
  (`100.63.255.255`, `100.128.0.0`, `99.64.0.1`, `101.64.0.1`).
- Hex-colon IPv4-mapped private embeds (`…:ffff:7f00:0001` = 127.0.0.1, cloud-metadata, site-local,
  CGNAT, uppercase prefix) and public embeds (`…:ffff:0808:0808` = 8.8.8.8), dotted full-prefix
  forms, and malformed forms (non-hex group, wrong group count, no colon) that must fail secure.

Result: `IpAddressClassifier` survivors **50 → 41**. The security-relevant arithmetic
(`>>8`, `&0xFF`, radix-16 parse in `hexColonToIpv4`) and the CGNAT `>=64 && <=127` comparison are
now mutation-pinned. The residual 41 are predominantly the Tier-A fail-secure-masked
`trim()`/`toLowerCase()` naked-receivers and defensive re-guards (a mutated normalization still
yields the blocked verdict, so no assertion can observe it).

## Post-improvement re-run (full profile, 2026-06-26)

```
Generated 1172 mutations · KILLED 859 · TIMED_OUT 9 · SURVIVED 232 · NO_COVERAGE 72
Test strength 79% (was 78%) · Killed incl. timeouts 74% (was 73%) · 70% gate: MET
IpAddressClassifier survivors 50 → 41 (SSRF hex-colon + CGNAT branches pinned)
```

Net: total survivors 241 → 232; the SSRF-classifier P1 gap is closed. The remaining delta
between 232 and a hypothetical zero is the documented Tier-A equivalents plus the Tier-B P2/P3
backlog above — all above the enforced 70% threshold.

## Disposition

- SR-FUZZ-13 is **closed**: the equivalents are documented (Tier A) and the first-run survivor
  set is fully classified rather than hand-waved.
- The Tier-B gaps are **accepted as tracked TD**, P1 closed this run; P2/P3 remain (the 70%
  mutation gate is met, so they are quality-debt, not a build blocker). Closing them is a good
  follow-up for the rate-limiter and scrubber suites.
- No code behaviour changed; the only production-affecting work was additive tests.

## References
- `docs/decisions/2026-05-01-acceptance-fuzz-mutation-testing.md` — SR-FUZZ-13 deferral
- `.github/workflows/build-and-deploy.yml`, `.github/workflows/mutation.yml` — PITest CI jobs
- `pom.xml` profile `mutation` (PIT 70% threshold, 14 ADR-FUZZ-04 target classes)
- `src/test/java/de/seism0saurus/glacier/util/IpAddressClassifierTest.java` — P1 gap-closure tests
- OWASP A03:2021 (Injection / mutation testing rigor); OWASP SSRF Prevention Cheat Sheet
