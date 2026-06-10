from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Dict, List, Optional

_SCRIPT_DIR = __import__("pathlib").Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import assignees_by_id, configure_stdio_utf8, enrich_alarm_row  # noqa: E402
from db_store import (  # noqa: E402
    build_device_alarm_summary,
    compute_alarm_stats_from_db,
    load_inspection_doc,
    query_alarms,
)


def build_inspection_report(*, run_id: Optional[str] = None) -> Dict[str, Any]:
    doc = load_inspection_doc(run_id=run_id)
    rid = str(doc.get("runId", ""))
    stats = compute_alarm_stats_from_db(run_id=rid)
    device_summary = build_device_alarm_summary(run_id=rid)
    alarms = [enrich_alarm_row(row) for row in query_alarms(run_id=rid)]
    by_assignee_named = {}
    contacts = assignees_by_id()
    for aid, count in (stats.get("byAssignee") or {}).items():
        label = contacts.get(aid, {}).get("name", aid)
        by_assignee_named[f"{label} ({aid})"] = count

    return {
        "version": 1,
        "runId": rid,
        "createdAt": doc.get("createdAt"),
        "endTs": (doc.get("inspection") or {}).get("end_ts"),
        "summary": {
            "registryDeviceCount": stats.get("registryDeviceCount"),
            "alarmDeviceCount": stats.get("alarmDeviceCount"),
            "totalAlertCount": stats.get("totalAlertCount"),
            "byBuilding": stats.get("byBuilding"),
            "byLevel": stats.get("byLevel"),
            "byAssigneeNamed": by_assignee_named,
        },
        "deviceSummaryTable": device_summary,
        "alarmDetailTable": alarms,
        "displayInstructions": (
            "Present only fault devices. Include summary, device summary table, and full rule_name detail table. "
            "Use formal deviceId values. Empty reasonAnalysis/expertAdvice show as dash. No disclaimer text."
        ),
    }


def _markdown_report(report: Dict[str, Any]) -> str:
    summary = report.get("summary") or {}
    lines: List[str] = [
        "# Inspection report",
        "",
        f"- runId: {report.get('runId', '')}",
        f"- fault devices: {summary.get('alarmDeviceCount', 0)}",
        f"- total alerts: {summary.get('totalAlertCount', 0)}",
        f"- by building: {summary.get('byBuilding', {})}",
        "",
        "## Device summary",
        "",
        "| deviceId | deviceName | building | alertCount | ruleNames | assignee |",
        "| --- | --- | --- | ---: | --- | --- |",
    ]
    for row in report.get("deviceSummaryTable") or []:
        rule_names = "；".join(row.get("ruleNames") or [])
        lines.append(
            f"| {row.get('deviceId', '')} | {row.get('deviceName', '')} | {row.get('building', '')} | "
            f"{row.get('alertCount', 0)} | {rule_names} | {row.get('assigneeName', '')} |"
        )

    lines.extend(
        [
            "",
            "## Alarm details",
            "",
            "| deviceId | building | ruleName | reasonAnalysis | expertAdvice |",
            "| --- | --- | --- | --- | --- |",
        ]
    )
    for row in report.get("alarmDetailTable") or []:
        reason = str(row.get("reasonAnalysis", "—")).replace("\n", " ")
        advice = str(row.get("expertAdvice", "—")).replace("\n", " ")
        lines.append(
            f"| {row.get('deviceId', '')} | {row.get('building', '')} | {row.get('ruleName', '')} | "
            f"{reason} | {advice} |"
        )
    return "\n".join(lines)


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio_utf8()
    parser = argparse.ArgumentParser(description="Format inspection report for user display")
    parser.add_argument("--run-id", default="latest")
    parser.add_argument("--markdown", action="store_true", help="Print markdown tables for Agent display")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_inspection_report(run_id=args.run_id)

    if args.markdown and not args.json:
        print(_markdown_report(report))
    elif args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(_markdown_report(report))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
