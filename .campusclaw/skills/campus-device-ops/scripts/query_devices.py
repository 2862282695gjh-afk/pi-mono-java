from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import (  # noqa: E402
    configure_stdio_utf8,
    http_get_json,
    load_device_registry,
    load_json,
)
from db_store import alarm_device_ids, resolve_run_id  # noqa: E402


def _filter_devices(
    devices: List[Dict[str, Any]],
    *,
    building: Optional[str],
    device_type: Optional[str],
    status: Optional[str],
    device_id: Optional[str],
    alarm_device_ids_set: Optional[Set[str]],
) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for d in devices:
        if not isinstance(d, dict):
            continue
        did = str(d.get("deviceId", "")).strip()
        if device_id and did != device_id:
            continue
        if building and str(d.get("building", "")).strip() != building.strip():
            continue
        if device_type and str(d.get("deviceType", "")).strip().lower() != device_type.strip().lower():
            continue
        if status and status != "all" and str(d.get("status", "")).strip() != status.strip():
            continue
        row = dict(d)
        if alarm_device_ids_set is not None:
            row["hasAlarm"] = did in alarm_device_ids_set
        out.append(row)
    return out


def query_devices_local(
    *,
    building: Optional[str] = None,
    device_type: Optional[str] = None,
    status: Optional[str] = None,
    device_id: Optional[str] = None,
    with_alarms: bool = False,
    run_id: Optional[str] = "latest",
) -> Dict[str, Any]:
    doc = load_device_registry()
    devices = [d for d in (doc.get("devices") or []) if isinstance(d, dict)]
    alarm_ids_set: Optional[Set[str]] = None
    if with_alarms:
        ids = alarm_device_ids(run_id=run_id)
        alarm_ids_set = set(ids)
    filtered = _filter_devices(
        devices,
        building=building,
        device_type=device_type,
        status=status or "all",
        device_id=device_id,
        alarm_device_ids_set=alarm_ids_set,
    )
    return {
        "version": 1,
        "runId": resolve_run_id(run_id) if with_alarms else None,
        "campus": doc.get("campus", ""),
        "total": len(filtered),
        "devices": filtered,
    }


def main(argv: Optional[List[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Query campus devices (registry); alarms from database")
    p.add_argument("--building", default=None)
    p.add_argument("--device-type", default=None)
    p.add_argument("--status", default="all")
    p.add_argument("--device-id", default=None)
    p.add_argument("--with-alarms", action="store_true", help="hasAlarm from DB latest run")
    p.add_argument("--run-id", default="latest", help="Inspection run for hasAlarm")
    p.add_argument("--http", action="store_true")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    if args.http:
        query: Dict[str, str] = {}
        if args.building:
            query["building"] = args.building
        if args.device_type:
            query["deviceType"] = args.device_type
        if args.status:
            query["status"] = args.status
        if args.device_id:
            query["deviceId"] = args.device_id
        if args.with_alarms:
            query["withAlarms"] = "true"
        if args.run_id:
            query["runId"] = args.run_id
        result = http_get_json("/devices", query=query)
    else:
        result = query_devices_local(
            building=args.building,
            device_type=args.device_type,
            status=args.status,
            device_id=args.device_id,
            with_alarms=args.with_alarms,
            run_id=args.run_id,
        )

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("deviceId\tbuilding\tdeviceType\tname\tstatus\thasAlarm")
        for d in result.get("devices") or []:
            print(
                f"{d.get('deviceId', '')}\t{d.get('building', '')}\t{d.get('deviceType', '')}\t"
                f"{d.get('name', '')}\t{d.get('status', '')}\t{d.get('hasAlarm', '')}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
