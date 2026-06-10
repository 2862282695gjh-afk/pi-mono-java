"""Create work orders. Synced with campus-device-ops/scripts/create_work_order.py — update both copies together."""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import (  # noqa: E402
    assignees_by_id,
    configure_stdio_utf8,
    registry_by_device_id,
    work_orders_dir,
    write_json,
)
from db_store import load_inspection_doc  # noqa: E402


def _next_work_order_id() -> str:
    wo_dir = work_orders_dir()
    wo_dir.mkdir(parents=True, exist_ok=True)
    day = datetime.now(tz=timezone.utc).strftime("%Y%m%d")
    prefix = f"WO-{day}-"
    max_seq = 0
    for path in wo_dir.glob(f"{prefix}*.json"):
        suffix = path.stem[len(prefix) :]
        if suffix.isdigit():
            max_seq = max(max_seq, int(suffix))
    return f"{prefix}{max_seq + 1:03d}"


def _default_problem_analysis(alerts: List[Dict[str, Any]]) -> Dict[str, Any]:
    causes: List[Dict[str, Any]] = []
    for alert in alerts:
        reason = str(alert.get("reason_analysis", "") or "").strip()
        if not reason or reason == "—":
            continue
        for line in reason.split("\n"):
            line = line.strip()
            if not line:
                continue
            causes.append(
                {
                    "cause": line.lstrip("0123456789.").strip(),
                    "likelihood": "medium",
                    "evidence": f"rule_id={alert.get('rule_id', '')}; from rules_re",
                }
            )
    if not causes:
        causes.append(
            {
                "cause": "Pending field inspection — no reason analysis in rules_re",
                "likelihood": "unknown",
                "evidence": "rules_re.json empty reason analysis",
            }
        )
    return {
        "summary": "Auto skeleton from rules_re reason analysis; refine with inspection context.",
        "possibleCauses": causes[:5],
    }


def _default_disposal(alerts: List[Dict[str, Any]]) -> Dict[str, Any]:
    steps: List[str] = []
    for alert in alerts:
        advice = str(alert.get("expert_advice", "") or "").strip()
        if advice and advice != "—":
            for line in advice.split("\n"):
                line = line.strip()
                if line:
                    steps.append(line)
    if not steps:
        steps = [
            "Verify device local panel and BMS point readings",
            "Isolate fault if safety-critical",
            "Schedule on-site inspection",
        ]
    return {
        "steps": steps[:10],
        "expertAdviceRef": "rules_re.json expert advice",
        "note": "Agent may expand steps; must cite rule_id and original advice.",
    }


def create_work_order(
    *,
    device_id: str,
    rule_ids: List[str],
    inspection_doc: Dict[str, Any],
    assignee_id: Optional[str] = None,
    problem_analysis: Optional[Dict[str, Any]] = None,
    disposal_suggestions: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    inspection = inspection_doc.get("inspection") or inspection_doc
    reg = registry_by_device_id()
    device = reg.get(device_id, {})
    alerts_by_device = inspection.get("alerts_by_device") or {}
    device_alerts = [row for row in (alerts_by_device.get(device_id) or []) if isinstance(row, dict)]

    if rule_ids:
        wanted = {item.strip() for item in rule_ids if item.strip()}
        device_alerts = [row for row in device_alerts if str(row.get("rule_id", "")).strip() in wanted]

    if not device_alerts:
        raise ValueError(f"no matching alerts for device {device_id!r} and rule ids {rule_ids!r}")

    aid = assignee_id or str(device.get("assigneeId", "")).strip() or "unassigned"
    assignee = assignees_by_id().get(aid, {})
    wo_id = _next_work_order_id()
    now = datetime.now(tz=timezone.utc).isoformat()

    return {
        "version": 1,
        "id": wo_id,
        "status": "open",
        "createdAt": now,
        "runId": inspection_doc.get("runId"),
        "deviceId": device_id,
        "deviceName": device.get("name", ""),
        "building": device.get("building", ""),
        "deviceType": device.get("deviceType", ""),
        "component": device.get("component", ""),
        "assigneeId": aid,
        "assigneeName": assignee.get("name", aid),
        "alarms": [
            {
                "ruleId": row.get("rule_id", ""),
                "ruleName": row.get("rule_name", ""),
                "reasonAnalysis": row.get("reason_analysis", "") or "—",
                "expertAdvice": row.get("expert_advice", "") or "—",
            }
            for row in device_alerts
        ],
        "problemAnalysis": problem_analysis or _default_problem_analysis(device_alerts),
        "disposalSuggestions": disposal_suggestions or _default_disposal(device_alerts),
        "priority": "high"
        if any("fault" in str(row.get("rule_name", "")).lower() for row in device_alerts)
        else "medium",
    }


def main(argv: Optional[List[str]] = None) -> int:
    configure_stdio_utf8()
    parser = argparse.ArgumentParser(description="Create maintenance work order from inspection run")
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--rule-ids", default="", help="Comma-separated rule ids")
    parser.add_argument("--assignee", default=None)
    parser.add_argument("--run-id", default="latest")
    parser.add_argument("--output", default=None)
    parser.add_argument("--analysis-json", default=None)
    parser.add_argument("--disposal-json", default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    doc = load_inspection_doc(run_id=args.run_id)
    rule_ids = [part.strip() for part in args.rule_ids.split(",") if part.strip()]
    problem_analysis = (
        json.loads(Path(args.analysis_json).read_text(encoding="utf-8")) if args.analysis_json else None
    )
    disposal_suggestions = (
        json.loads(Path(args.disposal_json).read_text(encoding="utf-8")) if args.disposal_json else None
    )

    work_order = create_work_order(
        device_id=args.device_id.strip(),
        rule_ids=rule_ids,
        inspection_doc=doc,
        assignee_id=args.assignee,
        problem_analysis=problem_analysis,
        disposal_suggestions=disposal_suggestions,
    )

    output = (
        Path(args.output).expanduser().resolve()
        if args.output
        else work_orders_dir() / f"{work_order['id']}.json"
    )
    write_json(output, work_order)

    if args.json:
        print(json.dumps(work_order, ensure_ascii=False, indent=2))
    else:
        print(f"created work order: {work_order['id']} -> {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
