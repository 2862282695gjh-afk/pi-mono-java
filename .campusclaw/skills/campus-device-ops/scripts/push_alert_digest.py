from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import (  # noqa: E402
    assignees_by_id,
    configure_stdio_utf8,
    http_post_json,
    notifications_outbox_dir,
    registry_by_device_id,
    write_json,
)
from db_store import load_inspection_doc, resolve_run_id  # noqa: E402
from push_message import render_ai_message  # noqa: E402


def _priority_for_rule(rule_name: str) -> str:
    name = rule_name.lower()
    if any(k in name for k in ("故障", "fault", "inverter", "压缩机")):
        return "high"
    if any(k in name for k in ("超限", "overlimit", "over_range")):
        return "medium"
    return "low"


def build_digests(doc: Dict[str, Any]) -> List[Dict[str, Any]]:
    from datetime import datetime, timezone

    inspection = doc.get("inspection") or doc
    assignees = assignees_by_id()
    reg = registry_by_device_id()
    alerts_by_device = inspection.get("alerts_by_device") or {}
    run_id = str(doc.get("runId", "")).strip()

    by_assignee: Dict[str, List[Dict[str, Any]]] = {}
    for did, alerts in alerts_by_device.items():
        device = reg.get(str(did), {})
        aid = str(device.get("assigneeId", "unassigned")).strip() or "unassigned"
        for alert in alerts or []:
            if not isinstance(alert, dict):
                continue
            entry = {
                "deviceId": did,
                "deviceName": device.get("name", ""),
                "building": device.get("building", ""),
                "ruleId": alert.get("rule_id", ""),
                "ruleName": alert.get("rule_name", ""),
                "priority": _priority_for_rule(str(alert.get("rule_name", ""))),
                "reasonAnalysis": alert.get("reason_analysis", "") or "—",
                "expertAdvice": alert.get("expert_advice", "") or "—",
            }
            by_assignee.setdefault(aid, []).append(entry)

    now = datetime.now(tz=timezone.utc)
    digests: List[Dict[str, Any]] = []
    for aid, items in sorted(by_assignee.items()):
        contact = assignees.get(aid, {})
        digest: Dict[str, Any] = {
            "version": 1,
            "digestId": f"digest_{aid}_{int(now.timestamp())}",
            "createdAt": now.isoformat(),
            "runId": run_id,
            "assigneeId": aid,
            "assigneeName": contact.get("name", aid),
            "channels": contact.get("channels", ["file"]),
            "alarmCount": len(items),
            "items": items,
        }
        digest["aiMessage"] = render_ai_message(digest, run_id=run_id)
        digests.append(digest)
    return digests


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Build per-assignee alert digests and optional mock push")
    p.add_argument("--run-id", default="latest", help="Inspection run_id in database")
    p.add_argument("--output-dir", default=None, help="Outbox directory")
    p.add_argument("--write-ai-message", action="store_true", help="Include aiMessage (default when writing files)")
    p.add_argument("--push-http", action="store_true", help="POST each digest to mock /notifications/push")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    doc = load_inspection_doc(run_id=args.run_id)
    run_id = str(doc.get("runId") or resolve_run_id(args.run_id) or "")
    digests = build_digests(doc)
    out_dir = Path(args.output_dir).expanduser().resolve() if args.output_dir else notifications_outbox_dir()
    out_dir.mkdir(parents=True, exist_ok=True)

    written: List[str] = []
    push_results: List[Dict[str, Any]] = []
    for d in digests:
        if not args.write_ai_message and "aiMessage" in d:
            pass
        path = out_dir / f"{d['digestId']}.json"
        write_json(path, d)
        written.append(str(path))
        if args.push_http:
            body = {"runId": run_id, "assigneeId": d["assigneeId"], "aiMessage": d.get("aiMessage", "")}
            push_results.append(http_post_json("/notifications/push", body))

    result = {
        "version": 1,
        "runId": run_id,
        "digestCount": len(digests),
        "files": written,
        "digests": digests,
    }
    if push_results:
        result["pushResults"] = push_results

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"wrote {len(written)} digest(s) to {out_dir} (runId={run_id})")
        for d in digests:
            print(f"  {d['assigneeName']} ({d['assigneeId']}): {d['alarmCount']} alerts")
        for f in written:
            print(f"  {f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
