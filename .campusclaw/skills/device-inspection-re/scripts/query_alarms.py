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

configure_stdio_utf8()


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Query device-inspection-re alarms from database")
    p.add_argument("--run-id", default="latest")
    p.add_argument("--device-id", default=None)
    p.add_argument("--rule-id", default=None)
    p.add_argument("--device-type", default=None)
    p.add_argument("--list-runs", action="store_true")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    if args.list_runs:
        runs = list_inspection_runs()
        if args.json:
            print(json.dumps({"version": 1, "runs": runs}, ensure_ascii=False, indent=2))
        else:
            for r in runs:
                print(
                    f"{r['run_id']}\t{r['created_at']}\t"
                    f"kind={r['rules_kind']}\tdevices={r['fault_device_count']}\t"
                    f"alerts={r['total_alert_count']}"
                )
        return 0

    alarms = query_alarms(
        run_id=args.run_id,
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
        print("deviceId\tdeviceName\tbuilding\tdeviceType\tcomponent\truleId\truleName\treasonAnalysis")
        for a in alarms:
            ra = str(a.get("reasonAnalysis", "")).replace("\n", " ")[:40]
            print(
                f"{a.get('deviceId', '')}\t{a.get('deviceName', '')}\t{a.get('building', '')}\t"
                f"{a.get('deviceType', '')}\t{a.get('component', '')}\t{a.get('ruleId', '')}\t"
                f"{a.get('ruleName', '')}\t{ra}"
            )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
