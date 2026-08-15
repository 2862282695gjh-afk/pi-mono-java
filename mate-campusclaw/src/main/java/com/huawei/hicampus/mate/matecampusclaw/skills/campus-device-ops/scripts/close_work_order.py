"""Close work orders. Synced with device-inspection-re/scripts/close_work_order.py — update both copies together."""
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

from _common import configure_stdio_utf8, load_json, work_orders_dir, write_json  # noqa: E402

configure_stdio_utf8()


def list_open_work_orders() -> List[Dict[str, Any]]:
    """Return all work orders with status == 'open'."""
    wo_dir = work_orders_dir()
    if not wo_dir.is_dir():
        return []
    open_orders: List[Dict[str, Any]] = []
    for path in sorted(wo_dir.glob("WO-*.json")):
        try:
            doc = load_json(path)
            if isinstance(doc, dict) and doc.get("status") == "open":
                doc["_path"] = str(path)
                open_orders.append(doc)
        except Exception:
            continue
    return open_orders


def close_work_order(wo_id: str) -> Optional[Dict[str, Any]]:
    """Close a single work order by id. Returns the closed doc or None if not found."""
    wo_dir = work_orders_dir()
    if not wo_dir.is_dir():
        return None
    path = wo_dir / f"{wo_id}.json"
    if not path.is_file():
        return None
    doc = load_json(path)
    if not isinstance(doc, dict):
        return None
    if doc.get("status") != "open":
        return doc  # already closed/resolved, return as-is
    now = datetime.now(tz=timezone.utc).isoformat()
    doc["status"] = "closed"
    doc["closedAt"] = now
    write_json(path, doc)
    return doc


def close_all_work_orders() -> List[Dict[str, Any]]:
    """Close all open work orders. Returns list of closed docs."""
    closed: List[Dict[str, Any]] = []
    now = datetime.now(tz=timezone.utc).isoformat()
    wo_dir = work_orders_dir()
    if not wo_dir.is_dir():
        return closed
    for path in sorted(wo_dir.glob("WO-*.json")):
        try:
            doc = load_json(path)
            if isinstance(doc, dict) and doc.get("status") == "open":
                doc["status"] = "closed"
                doc["closedAt"] = now
                write_json(path, doc)
                closed.append(doc)
        except Exception:
            continue
    return closed


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Close maintenance work order(s)")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--work-order-id", default=None, help="Work order id to close (e.g. WO-20260610-001)")
    group.add_argument("--all", action="store_true", help="Close all open work orders")
    group.add_argument("--list-open", action="store_true", help="List open work orders without closing")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    if args.list_open:
        open_orders = list_open_work_orders()
        result = {
            "version": 1,
            "total": len(open_orders),
            "workOrders": [
                {"id": wo.get("id"), "deviceId": wo.get("deviceId"), "deviceName": wo.get("deviceName"),
                 "building": wo.get("building"), "createdAt": wo.get("createdAt"), "assigneeName": wo.get("assigneeName")}
                for wo in open_orders
            ],
        }
        if args.json:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            if not open_orders:
                print("No open work orders.")
            else:
                print(f"Open work orders ({len(open_orders)}):")
                for wo in open_orders:
                    print(f"  {wo.get('id')} | {wo.get('deviceId')} | {wo.get('deviceName')} | {wo.get('building')} | {wo.get('assigneeName', 'N/A')}")
        return 0

    if args.work_order_id:
        wo_id = args.work_order_id.strip()
        doc = close_work_order(wo_id)
        if doc is None:
            print(f"Work order not found: {wo_id}", file=sys.stderr)
            return 1
        if args.json:
            print(json.dumps(doc, ensure_ascii=False, indent=2))
        else:
            status = doc.get("status", "unknown")
            print(f"{wo_id}: {status}" + (f" at {doc.get('closedAt', '')}" if status == "closed" else ""))
        return 0

    # --all
    closed = close_all_work_orders()
    result = {"version": 1, "closedCount": len(closed), "workOrderIds": [wo.get("id") for wo in closed]}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        if not closed:
            print("No open work orders to close.")
        else:
            print(f"Closed {len(closed)} work order(s):")
            for wo in closed:
                print(f"  {wo.get('id')} | {wo.get('deviceId')} | {wo.get('deviceName')}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
