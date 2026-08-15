from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import campusclaw_root, configure_stdio_utf8  # noqa: E402
from openclaw_env import apply_openclaw_defaults  # noqa: E402


def _run_json(cmd: list[str], *, cwd: Path) -> dict:
    proc = subprocess.run(cmd, capture_output=True, cwd=str(cwd))
    stdout = (proc.stdout or b"").decode("utf-8", errors="replace")
    stderr = (proc.stderr or b"").decode("utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(cmd)}\n{stderr or stdout}")
    return json.loads(stdout)


def main() -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Smoke test campus-device-ops against shared inspection DB")
    p.add_argument("--skip-inspection", action="store_true", help="Use existing DB run (faster)")
    args = p.parse_args()

    apply_openclaw_defaults(force=False)
    py = sys.executable
    scripts = _SCRIPT_DIR
    root = campusclaw_root()

    if args.skip_inspection:
        print("1/6 skip run_inspection (existing DB)")
    else:
        print("1/6 run_inspection.py (delegates to device-inspection-re → shared DB)")
        doc = _run_json([py, str(scripts / "run_inspection.py"), "--json"], cwd=root)
        run_id = doc.get("runId")
        faults = len((doc.get("inspection") or {}).get("fault_devices") or [])
        print(f"    runId={run_id} fault_devices={faults} db=device_inspection_re.db")

    print("2/6 query_alarms.py")
    alarms = _run_json([py, str(scripts / "query_alarms.py"), "--json"], cwd=root)
    total = int(alarms.get("total", 0))
    if args.skip_inspection:
        assert "alarms" in alarms, "query_alarms missing alarms key"
    else:
        assert total >= 1, f"expected alarms after inspection, got total={total}"

    print("3/6 alarm_stats.py + query_devices.py")
    _run_json([py, str(scripts / "alarm_stats.py"), "--json"], cwd=root)
    _run_json([py, str(scripts / "query_devices.py"), "--with-alarms", "--json"], cwd=root)

    print("4/6 build_qa_context.py")
    _run_json([py, str(scripts / "build_qa_context.py"), "--json"], cwd=root)

    print("5/6 push_alert_digest.py --write-ai-message")
    push = _run_json(
        [py, str(scripts / "push_alert_digest.py"), "--write-ai-message", "--json"],
        cwd=root,
    )
    assert push.get("digestCount", 0) >= 1

    print("6/6 create_work_order.py")
    device_id = None
    for a in alarms.get("alarms") or []:
        if isinstance(a, dict) and a.get("deviceId"):
            device_id = str(a["deviceId"])
            break
    if not device_id:
        print("    skip work order (no alarms)")
    else:
        wo = _run_json(
            [py, str(scripts / "create_work_order.py"), "--device-id", device_id, "--json"],
            cwd=root,
        )
        print(f"    workOrderId={wo.get('id')}")

    verify = root / "build" / "verify_inspection_dbs.py"
    if verify.is_file():
        print("verify_inspection_dbs.py")
        proc = subprocess.run([py, str(verify)], cwd=str(root))
        if proc.returncode != 0:
            return proc.returncode

    print("SMOKE OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
