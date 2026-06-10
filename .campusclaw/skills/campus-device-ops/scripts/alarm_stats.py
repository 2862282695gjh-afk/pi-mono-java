from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Dict, Optional

_SCRIPT_DIR = __import__("pathlib").Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import configure_stdio_utf8  # noqa: E402
from db_store import compute_alarm_stats_from_db  # noqa: E402


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Alarm statistics from database (latest or specified run)")
    p.add_argument("--run-id", default="latest", help="Inspection run_id or latest")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    stats = compute_alarm_stats_from_db(run_id=args.run_id)

    if args.json:
        print(json.dumps(stats, ensure_ascii=False, indent=2))
    else:
        print(f"runId: {stats.get('runId')}")
        print(f"alarm devices: {stats['alarmDeviceCount']} / registry: {stats['registryDeviceCount']}")
        print(f"total alerts: {stats['totalAlertCount']}")
        print("by building:", stats.get("byBuilding", {}))
        print("by deviceType:", stats.get("byDeviceType", {}))
        print("by assignee:", stats.get("byAssignee", {}))
        print("by rule:", stats.get("byRule", {}))
        print("fault devices:", stats.get("faultDevices", []))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
