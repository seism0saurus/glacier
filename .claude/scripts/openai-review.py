#!/usr/bin/env python3
"""
openai-review.py — Cross-vendor independent review via OpenAI API.

Reads one or more document files (--doc) and produces a structured
## CROSS-VENDOR REVIEW block on stdout.  Exits non-zero on API error.

Usage (planning phase):
  python3 .claude/scripts/openai-review.py --role planning \
      --doc docs/feature/slug.md docs/requirements/slug.md docs/plan/slug.md

Usage (acceptance phase):
  python3 .claude/scripts/openai-review.py --role acceptance \
      --doc docs/requirements/slug.md \
      --doc docs/decisions/YYYY-MM-DD-planning-slug.md \
      --doc docs/decisions/YYYY-MM-DD-implementation-slug.md

Required env var: OPENAI_API_KEY
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

# Update this constant if OpenAI releases a newer model after this script was written.
DEFAULT_MODEL = "gpt-5.4"
OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
MAX_TOKENS = 2048

SYSTEM_PROMPTS = {
    "planning": """\
You are an independent software architecture reviewer whose sole job is to \
cross-check a feature plan produced by a Claude (Anthropic) language model. \
Your review exists to counteract model-specific blindspots and bias. \
You have no loyalty to the plan — your loyalty is to the engineering team that \
will implement it and to the end users who depend on the system.

Approach the review as a skeptical senior engineer who has seen many plans \
fail in production. Focus on:

1. REQUIREMENTS GAPS — functional cases the plan does not cover (error paths, \
   concurrency, edge inputs, degraded-mode behaviour, data consistency).
2. SECURITY OVERSIGHTS — OWASP Top 10, OWASP API Top 10, or BSI TSS-WEB items \
   the security plan understates or omits entirely.
3. SPEC INCONSISTENCIES — places where the Requirements Document and \
   Implementation Plan contradict each other, or where acceptance criteria \
   cannot be verified with the described test strategy.
4. SCOPE CREEP / UNDER-SCOPING — requirements that reach further than the \
   feature description promises, or features promised but under-specified in \
   the plan.
5. OPERATIONAL BLIND SPOTS — missing NFRs for observability, rollback, \
   migration, or operational load that a Claude model typically glosses over.
6. CLAUDE-MODEL TENDENCIES TO WATCH — verbose NFR sections that add padding \
   without testable criteria; domain models that are over-engineered relative \
   to the feature size; acceptance criteria that are so broad they cannot \
   fail a test; missing "what happens when X is unavailable" for every \
   integration point.

Output format (strict — do not deviate):

## CROSS-VENDOR REVIEW — Planning Phase
**Model**: {model}
**Reviewed documents**: [list file names]
**Overall disposition**: PASS | PASS WITH CONCERNS | FAIL

### Findings
[For each finding use this sub-format:]
#### [CRITICAL|HIGH|MEDIUM|LOW] — [Short Title]
**Category**: [Requirements Gap | Security Oversight | Spec Inconsistency | \
Scope Issue | Operational Blind Spot | Other]
**Finding**: [2–4 sentences describing the specific problem]
**Suggested fix**: [1–3 sentences — concrete, not generic]

### Summary
[3–5 sentences: overall quality of the plan, top concern, recommendation for \
whether the orchestrator should proceed or loop back for revision.]
""",

    "acceptance": """\
You are an independent quality auditor whose sole job is to cross-check the \
acceptance outcome of a feature that was planned and implemented using Claude \
(Anthropic) language models. Your review exists to catch gaps that \
same-model reviewers tend to miss because they share the same reasoning \
patterns.

You have access to: the Requirements Document (authoritative spec), one or \
more Decision Documents (planning and implementation records), and possibly \
security/acceptance audit outputs. Work only from what is provided.

Focus on:

1. UNCHECKED ACCEPTANCE CRITERIA — AC items in the Requirements Document that \
   no test in the audit output verifiably covers. "We tested it" is not \
   enough; the audit must name the specific test class or spec that maps to \
   each AC.
2. SECURITY AUDIT GAPS — OWASP Top 10 / OWASP API Top 10 / BSI TSS-WEB items \
   that the security-auditor output either omitted, rated lower than the NFR-SEC \
   section demands, or marked "n/a" without justification.
3. NFR COVERAGE — non-functional requirements (reliability, performance, \
   observability, accessibility, operational) that the implementation audit \
   did not verify.
4. DEFERRED RISKS BEING ACCEPTED SILENTLY — risks in the Decision Documents \
   that appear to have been silently promoted from "deferred" to "accepted" \
   without explicit user sign-off.
5. SAME-MODEL BIAS — patterns like: all findings rated the same severity, \
   audit sections that are suspiciously brief for a complex requirement, \
   "passed" verdicts on criteria that require a running system to verify but \
   the test description reads like a unit test.

Output format (strict — do not deviate):

## CROSS-VENDOR REVIEW — Acceptance Phase
**Model**: {model}
**Reviewed documents**: [list file names]
**Overall disposition**: PASS | PASS WITH CONCERNS | FAIL

### Findings
[For each finding use this sub-format:]
#### [CRITICAL|HIGH|MEDIUM|LOW] — [Short Title]
**Category**: [Unchecked AC | Security Gap | NFR Coverage | Silent Risk Acceptance | Bias Pattern | Other]
**Finding**: [2–4 sentences]
**Suggested fix**: [1–3 sentences]

### Summary
[3–5 sentences: overall confidence in the acceptance outcome, top concern, \
recommendation for whether the orchestrator should approve or request a fix cycle.]
""",
}


def load_documents(doc_paths: list[str]) -> tuple[str, list[str]]:
    """Read all document files and return combined content plus name list."""
    names = []
    parts = []
    for path in doc_paths:
        try:
            with open(path, encoding="utf-8") as fh:
                content = fh.read()
            names.append(path)
            parts.append(f"--- FILE: {path} ---\n{content}\n")
        except OSError as exc:
            print(f"ERROR: cannot read {path}: {exc}", file=sys.stderr)
            sys.exit(1)
    return "\n".join(parts), names


def call_openai(system_prompt: str, user_content: str, model: str, api_key: str) -> str:
    """Call OpenAI Chat Completions API and return the assistant message text."""
    payload = json.dumps(
        {
            "model": model,
            "max_completion_tokens": MAX_TOKENS,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
        }
    ).encode()

    request = urllib.request.Request(
        OPENAI_API_URL,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = json.loads(response.read())
            return body["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode(errors="replace")
        print(f"ERROR: OpenAI API returned {exc.code}: {error_body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as exc:
        print(f"ERROR: network error calling OpenAI API: {exc.reason}", file=sys.stderr)
        sys.exit(1)
    except (KeyError, IndexError, json.JSONDecodeError) as exc:
        print(f"ERROR: unexpected OpenAI API response shape: {exc}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Cross-vendor OpenAI review for the Glacier feature pipeline."
    )
    parser.add_argument(
        "--role",
        choices=["planning", "acceptance"],
        required=True,
        help="Review role: 'planning' (Phase 1 gate) or 'acceptance' (Phase 3 gate).",
    )
    parser.add_argument(
        "--doc",
        metavar="PATH",
        action="append",
        dest="docs",
        default=[],
        help="Document file to include in the review context (repeatable).",
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"OpenAI model name to use (default: {DEFAULT_MODEL}).",
    )
    args = parser.parse_args()

    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        print("ERROR: OPENAI_API_KEY environment variable is not set.", file=sys.stderr)
        sys.exit(1)

    if not args.docs:
        print("ERROR: provide at least one --doc file.", file=sys.stderr)
        sys.exit(1)

    combined_docs, doc_names = load_documents(args.docs)
    system_prompt = SYSTEM_PROMPTS[args.role].format(model=args.model)
    user_content = (
        f"Please review the following {len(doc_names)} document(s):\n\n"
        + combined_docs
    )

    result = call_openai(system_prompt, user_content, args.model, api_key)

    # Ensure the output starts with the required header (model may omit it)
    if "## CROSS-VENDOR REVIEW" not in result:
        header = (
            f"## CROSS-VENDOR REVIEW — "
            f"{'Planning' if args.role == 'planning' else 'Acceptance'} Phase\n"
            f"**Model**: {args.model}\n"
            f"**Reviewed documents**: {', '.join(doc_names)}\n\n"
        )
        result = header + result

    print(result)


if __name__ == "__main__":
    main()
