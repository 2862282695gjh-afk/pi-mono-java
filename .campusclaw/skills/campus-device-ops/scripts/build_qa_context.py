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

from _common import configure_stdio_utf8, write_json  # noqa: E402
from db_store import load_inspection_doc, query_alarms  # noqa: E402


def build_qa_context(doc: Dict[str, Any], *, max_alarms: int = 100) -> Dict[str, Any]:
    run_id = doc.get("runId")
    all_alarms = query_alarms(run_id=run_id)
    alarms = all_alarms[:max_alarms]
    inspection = doc.get("inspection") or {}

    return {
        "version": 1,
        "runId": run_id,
        "generatedAt": datetime.now(tz=timezone.utc).isoformat(),
        "endTs": inspection.get("end_ts"),
        "summary": {
            "alarmDeviceCount": len({a["deviceId"] for a in all_alarms}),
            "totalAlertCount": len(all_alarms),
            "contextAlertCount": len(alarms),
            "byBuilding": _count_field(all_alarms, "building"),
            "byDeviceType": _count_field(all_alarms, "deviceType"),
        },
        "alerts": alarms,
        "instructions": (
            "Answer only from this context (loaded from database). "
            "If no rows, say run run_inspection.py first. Do not invent alarms."
        ),
    }


def _count_field(rows: List[Dict[str, Any]], field: str) -> Dict[str, int]:
    out: Dict[str, int] = {}
    for r in rows:
        key = str(r.get(field) or "unknown")
        out[key] = out.get(key, 0) + 1
    return out


def main(argv: Optional[List[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Build Q&A context from database alarms")
    p.add_argument("--run-id", default="latest", help="Inspection run_id or latest")
    p.add_argument("--output", default=None, help="Write context JSON to path")
    p.add_argument("--max-alarms", type=int, default=100)
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    doc = load_inspection_doc(run_id=args.run_id)
    ctx = build_qa_context(doc, max_alarms=args.max_alarms)

    if args.output:
        out = Path(args.output).expanduser().resolve()
        write_json(out, ctx)
        print(f"wrote: {out}")

    if args.json or not args.output:
        print(json.dumps(ctx, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
