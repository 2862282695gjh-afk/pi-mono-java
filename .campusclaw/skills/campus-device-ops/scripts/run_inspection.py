from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import (  # noqa: E402
    configure_stdio_utf8,
    device_inspection_re_judge_script,
    device_inspection_re_skill_root,
    http_post_json,
    write_json,
)
from db_store import load_inspection_doc  # noqa: E402
from rules_re_paths import RulesNotFoundError, emit_rules_not_found, resolve_rules_re_path  # noqa: E402


def run_inspection_local(
    *,
    rules_path: Path,
    end_ts: Optional[str],
    output: Optional[Path],
    device_types: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """
    Delegate inspection to device-inspection-re (writes device_inspection_re.db).
    campus-device-ops reads that DB for stats / QA / push / work orders.
    """
    script = device_inspection_re_judge_script()
    env = os.environ.copy()
    fixtures = device_inspection_re_skill_root() / "mock_fixtures"
    env.setdefault("DEVICE_INSPECTION_RE_FIXTURES_DIR", str(fixtures))

    cmd = [sys.executable, str(script), "--rules", str(rules_path), "--json"]
    if end_ts:
        cmd.extend(["--end-ts", end_ts])
    for dt in device_types or []:
        token = str(dt).strip()
        if token:
            cmd.extend(["--device-type", token])

    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", env=env)
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip()
        raise RuntimeError(f"device-inspection-re failed: {err}")

    payload = json.loads(proc.stdout)
    if not isinstance(payload, dict):
        raise RuntimeError("inspection output must be JSON object")

    run_id = str(payload.get("runId", "")).strip() or None
    doc = load_inspection_doc(run_id=run_id)

    if output:
        write_json(output, doc)

    return doc


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(
        description="Run device-inspection-re and return latest campus_ops view (shared DB)"
    )
    p.add_argument("--rules", default=None, help="rules_re.json path")
    p.add_argument("--end-ts", default=None, help="End timestamp (epoch or ISO8601)")
    p.add_argument(
        "--device-type",
        action="append",
        default=[],
        help="Only inspect rules for these device types (repeatable or comma-separated)",
    )
    p.add_argument("--output", default=None, help="Optional: also write snapshot JSON")
    p.add_argument("--http", action="store_true", help="POST /inspection/run on mock API :18082")
    p.add_argument("--json", action="store_true", help="Print JSON to stdout")
    args = p.parse_args(argv)

    try:
        rules_path = (
            Path(args.rules).expanduser().resolve() if args.rules else resolve_rules_re_path()
        )
    except RulesNotFoundError as err:
        emit_rules_not_found(err=err, json_mode=args.json)
        return 1

    if args.http:
        body: Dict[str, Any] = {"rulesPath": str(rules_path)}
        if args.end_ts:
            body["endTs"] = args.end_ts
        if args.device_type:
            body["deviceTypeFilter"] = args.device_type
        doc = http_post_json("/inspection/run", body)
    else:
        out_path = Path(args.output).expanduser().resolve() if args.output else None
        doc = run_inspection_local(
            rules_path=rules_path,
            end_ts=args.end_ts,
            output=out_path,
            device_types=args.device_type or None,
        )

    if args.json or args.output is None:
        print(json.dumps(doc, ensure_ascii=False, indent=2))
    else:
        print(f"wrote: {args.output} (runId={doc.get('runId')})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RulesNotFoundError as err:
        emit_rules_not_found(err=err, json_mode="--json" in sys.argv)
        raise SystemExit(1) from err
    except Exception as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
