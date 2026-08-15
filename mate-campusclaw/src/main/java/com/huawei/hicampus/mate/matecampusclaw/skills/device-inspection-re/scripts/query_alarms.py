"""Query alarms from shared DB. Synced with device-inspection-re/scripts/query_alarms.py — update both copies together."""
from __future__ import annotations

import argparse
import json
import sys
from typing import Optional

_SCRIPT_DIR = __import__("pathlib").Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import configure_stdio_utf8  # noqa: E402
from db_store import list_inspection_runs, query_alarms, resolve_run_id  # noqa: E402


def _http_get_json(path: str, *, query: dict | None = None) -> dict:
    campus_scripts = _SCRIPT_DIR.parent.parent / "campus-device-ops" / "scripts"
    key = str(campus_scripts)
    if key not in sys.path:
        sys.path.insert(0, key)
    import _common as campus_common

    return campus_common.http_get_json(path, query=query)


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(
        description="查询数据库中的故障告警",
        epilog="示例: python query_alarms.py --device-id VAV_003 --json"
    )
    p.add_argument("--run-id", default="latest", help="巡检 run_id 或 latest（默认）")
    p.add_argument("--building", default=None, help="按楼栋筛选")
    p.add_argument("--device-type", default=None, help="按设备类型筛选")
    p.add_argument("--device-id", default=None, help="按设备 ID 筛选")
    p.add_argument("--rule-id", default=None, help="按规则 ID 筛选")
    p.add_argument("--list-runs", action="store_true", help="列出最近的巡检记录")
    p.add_argument("--http", action="store_true", help="通过 mock API 查询（默认直接读库）")
    p.add_argument("--json", action="store_true", help="JSON 格式输出")
    args = p.parse_args(argv)

    if args.list_runs:
        runs = list_inspection_runs()
        if args.json:
            print(json.dumps({"version": 1, "runs": runs}, ensure_ascii=False, indent=2))
        else:
            for r in runs:
                print(
                    f"{r['run_id']}\t{r['created_at']}\t"
                    f"kind={r.get('rules_kind', '')}\tdevices={r['fault_device_count']}\t"
                    f"alerts={r['total_alert_count']}"
                )
        return 0

    if args.http:
        query = {"runId": args.run_id}
        if args.building:
            query["building"] = args.building
        if args.device_type:
            query["deviceType"] = args.device_type
        if args.device_id:
            query["deviceId"] = args.device_id
        if args.rule_id:
            query["ruleId"] = args.rule_id
        result = _http_get_json("/alarms", query=query)
    else:
        alarms = query_alarms(
            run_id=args.run_id,
            building=args.building,
            device_id=args.device_id,
            rule_id=args.rule_id,
            device_type=args.device_type,
        )
        result = {
            "version": 1,
            "runId": resolve_run_id(args.run_id),
            "total": len(alarms),
            "alarms": alarms,
        }

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("deviceId\tbuilding\tdeviceType\truleName\tassignee")
        for a in result.get("alarms") or []:
            print(
                f"{a.get('deviceId', '')}\t{a.get('building', '')}\t{a.get('deviceType', '')}\t"
                f"{a.get('ruleName', '')}\t{a.get('assigneeName', '')}"
            )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
