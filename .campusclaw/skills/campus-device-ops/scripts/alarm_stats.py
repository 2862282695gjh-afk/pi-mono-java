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
    parser = argparse.ArgumentParser(description="Alarm statistics from inspection run")
    parser.add_argument("--run-id", default="latest")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    stats = compute_alarm_stats_from_db(run_id=args.run_id)
    stats["byAssigneeNamed"] = _assignee_labels(stats.get("byAssignee") or {})

    if args.json:
        print(json.dumps(stats, ensure_ascii=False, indent=2))
    else:
        print(f"runId: {stats.get('runId')}")
        print(f"alarm devices: {stats['alarmDeviceCount']} / registry: {stats['registryDeviceCount']}")
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
