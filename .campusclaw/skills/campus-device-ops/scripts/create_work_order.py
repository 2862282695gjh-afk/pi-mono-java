"""Create work orders. Synced with device-inspection-re/scripts/create_work_order.py — update both copies together."""
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
    for a in alerts:
        ra = str(a.get("reason_analysis", "") or "").strip()
        if not ra or ra == "—":
            continue
        for line in ra.split("\n"):
            line = line.strip()
            if not line:
                continue
            causes.append(
                {
                    "cause": line.lstrip("0123456789.").strip(),
                    "likelihood": "medium",
                    "evidence": f"rule_id={a.get('rule_id', '')}; from rules_re 原因分析",
                }
            )
    if not causes:
        causes.append(
            {
                "cause": "Pending field inspection — no 原因分析 in rules_re for matched rule(s)",
                "likelihood": "unknown",
                "evidence": "rules_re.json empty 原因分析",
            }
        )
    return {
        "summary": "Auto skeleton from rules_re 原因分析; Agent should refine with inspection context.",
        "possibleCauses": causes[:5],
    }


def _default_disposal(alerts: List[Dict[str, Any]]) -> Dict[str, Any]:
    steps: List[str] = []
    for a in alerts:
        ea = str(a.get("expert_advice", "") or "").strip()
        if ea and ea != "—":
            for line in ea.split("\n"):
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
        "expertAdviceRef": "rules_re.json 专家处理建议",
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
    device_alerts = [a for a in (alerts_by_device.get(device_id) or []) if isinstance(a, dict)]

    if rule_ids:
        rid_set = {r.strip() for r in rule_ids if r.strip()}
        device_alerts = [a for a in device_alerts if str(a.get("rule_id", "")).strip() in rid_set]

    if not device_alerts:
        raise ValueError(f"no matching alerts for device {device_id!r} and rule ids {rule_ids!r}")

    aid = assignee_id or str(device.get("assigneeId", "")).strip() or "unassigned"
    assignee = assignees_by_id().get(aid, {})

    wo_id = _next_work_order_id()
    now = datetime.now(tz=timezone.utc).isoformat()

    wo: Dict[str, Any] = {
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
                "ruleId": a.get("rule_id", ""),
                "ruleName": a.get("rule_name", ""),
                "reasonAnalysis": a.get("reason_analysis", "") or "—",
                "expertAdvice": a.get("expert_advice", "") or "—",
            }
            for a in device_alerts
        ],
        "problemAnalysis": problem_analysis or _default_problem_analysis(device_alerts),
        "disposalSuggestions": disposal_suggestions or _default_disposal(device_alerts),
        "priority": "high" if any("fault" in str(a.get("rule_name", "")).lower() for a in device_alerts) else "medium",
    }
    return wo


def main(argv: Optional[List[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Create maintenance work order JSON (mock persistence)")
    p.add_argument("--device-id", required=True)
    p.add_argument("--rule-ids", default="", help="Comma-separated rule ids (default: all alerts on device)")
    p.add_argument("--assignee", default=None, help="Override assigneeId")
    p.add_argument("--run-id", default="latest", help="Inspection run_id in database")
    p.add_argument("--output", default=None, help="Output path (default: work_orders/WO-*.json)")
    p.add_argument("--analysis-json", default=None, help="LLM-filled problemAnalysis JSON file")
    p.add_argument("--disposal-json", default=None, help="LLM-filled disposalSuggestions JSON file")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    doc = load_inspection_doc(run_id=args.run_id)
    rule_ids = [x.strip() for x in args.rule_ids.split(",") if x.strip()]

    pa = json.loads(Path(args.analysis_json).read_text(encoding="utf-8")) if args.analysis_json else None
    ds = json.loads(Path(args.disposal_json).read_text(encoding="utf-8")) if args.disposal_json else None

    wo = create_work_order(
        device_id=args.device_id.strip(),
        rule_ids=rule_ids,
        inspection_doc=doc,
        assignee_id=args.assignee,
        problem_analysis=pa,
        disposal_suggestions=ds,
    )

    out = Path(args.output).expanduser().resolve() if args.output else work_orders_dir() / f"{wo['id']}.json"
    write_json(out, wo)

    if args.json:
        print(json.dumps(wo, ensure_ascii=False, indent=2))
    else:
        print(f"created work order: {wo['id']} -> {out}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
