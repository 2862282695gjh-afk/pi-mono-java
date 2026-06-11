"""Alarm statistics. Synced with device-inspection-re/scripts/alarm_stats.py — update both copies together."""
from __future__ import annotations

import argparse
import json
import sys
from typing import Optional

_SCRIPT_DIR = __import__("pathlib").Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import assignees_by_id, configure_stdio_utf8  # noqa: E402
from db_store import compute_alarm_stats_from_db  # noqa: E402


def _assignee_labels(by_assignee: dict) -> dict:
    contacts = assignees_by_id()
    out = {}
    for aid, count in by_assignee.items():
        label = contacts.get(aid, {}).get("name", aid)
        out[f"{label} ({aid})"] = count
    return out


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio_utf8()
    parser = argparse.ArgumentParser(
        description="巡检告警统计（按楼栋/设备类型/负责人）",
        epilog="示例: python alarm_stats.py --json"
    )
    parser.add_argument("--run-id", default="latest", help="巡检 run_id 或 latest（默认）")
    parser.add_argument("--json", action="store_true", help="JSON 格式输出")
    args = parser.parse_args(argv)

    stats = compute_alarm_stats_from_db(run_id=args.run_id)
    stats["byAssigneeNamed"] = _assignee_labels(stats.get("byAssignee") or {})

    if args.json:
        print(json.dumps(stats, ensure_ascii=False, indent=2))
    else:
        scope_label = stats.get("scopeLabel", "全部设备")
        print(f"runId: {stats.get('runId')}")
        print(
            f"巡检范围 {scope_label}：共 {stats.get('inspectedDeviceCount', 0)} 台，"
            f"故障 {stats.get('faultDeviceCount', 0)} 台，"
            f"正常 {stats.get('healthyDeviceCount', 0)} 台"
        )
        print(f"total alerts: {stats['totalAlertCount']}")
        print("byBuilding:", stats.get("byBuilding"))
        print("byDeviceType:", stats.get("byDeviceType"))
        print("byLevel:", stats.get("byLevel"))
        print("byRule:", stats.get("byRule"))
        print("byAssigneeNamed:", stats.get("byAssigneeNamed"))
        print("faultDevices:", stats.get("faultDevices"))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
