#!/usr/bin/env python3
"""Diff a fresh ZAP traditional-json report against the committed baseline.

Exits 0  -> no new alerts above the medium-risk threshold.
Exits 1  -> new high/critical alerts not present in the baseline.
Exits 2  -> baseline format error.

Usage:
    diff-zap-baseline.py <report.json> <baseline.json> [--threshold MEDIUM]

The report.json must be ZAP's "traditional-json" output.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

RISK_RANK = {"Informational": 0, "Low": 1, "Medium": 2, "High": 3, "Critical": 4}


def alert_key(alert: dict) -> tuple[str, str, str]:
    """Identity tuple used to match alerts across runs."""
    return (
        str(alert.get("pluginid") or alert.get("alertRef") or alert.get("alert", "")),
        str(alert.get("uri") or alert.get("url", "")),
        str(alert.get("param", "")),
    )


def load_baseline(path: Path) -> set[tuple[str, str, str]]:
    raw = json.loads(path.read_text())
    if not isinstance(raw.get("alerts"), list):
        print(f"baseline {path} missing 'alerts' list", file=sys.stderr)
        sys.exit(2)
    return {(a["pluginid"], a["uri"], a.get("param", "")) for a in raw["alerts"]}


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("report")
    p.add_argument("baseline")
    p.add_argument("--threshold", default="Medium",
                   choices=list(RISK_RANK), help="minimum risk level that triggers a CI fail")
    args = p.parse_args(argv)

    threshold = RISK_RANK[args.threshold]
    baseline = load_baseline(Path(args.baseline))
    report = json.loads(Path(args.report).read_text())

    new_findings: list[dict] = []
    sites = report.get("site", [])
    for site in sites:
        for alert in site.get("alerts", []):
            risk = alert.get("riskdesc", "").split(" ", 1)[0]
            if RISK_RANK.get(risk, 0) < threshold:
                continue
            for instance in alert.get("instances", [{"uri": "", "param": ""}]):
                merged = {
                    "pluginid": alert.get("pluginid"),
                    "name": alert.get("name"),
                    "risk": risk,
                    "uri": instance.get("uri", ""),
                    "param": instance.get("param", ""),
                }
                if alert_key(merged) not in baseline:
                    new_findings.append(merged)

    if not new_findings:
        print(f"OK: 0 new {args.threshold}+ alerts (baseline allows "
              f"{len(baseline)} known findings)")
        return 0

    print(f"FAIL: {len(new_findings)} new {args.threshold}+ alert(s):")
    for f in new_findings:
        print(f"  - [{f['risk']}] {f['pluginid']} {f['name']!r}")
        print(f"      uri={f['uri']} param={f['param']}")
    print()
    print("If a finding is a real issue: open a failing test first, then fix.")
    print("If it is a false positive: add to baseline with justification + "
          "re-review date.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
