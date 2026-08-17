from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import campusclaw_root, configure_stdio_utf8, default_rules_re_path  # noqa: E402
from db_store import compute_alarm_stats_from_db, get_latest_run_id, query_alarms  # noqa: E402
from openclaw_env import apply_openclaw_defaults  # noqa: E402


def _workspace_root() -> Path:
    env = os.environ.get("OPENCLAW_WORKSPACE", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return campusclaw_root()


def _resolve_rules_path(ws: Path) -> Path:
    try:
        return default_rules_re_path()
    except FileNotFoundError:
        pass
    env = os.environ.get("DEVICE_INSPECTION_RE_RULES_PATH", "").strip()
    if env:
        candidate = Path(env).expanduser().resolve()
        if candidate.is_file():
            return candidate
    primary = ws / "skills" / "device-inspection-re" / "rules" / "rules_re.json"
    if primary.is_file():
        return primary
    pack = ws / "rule-engines-pack" / "02-rule-engine-pypi" / "rules" / "rules_re.json"
    if pack.is_file():
        return pack
    raise FileNotFoundError(
        "rules_re.json not found; compile via excel-antlr-to-rules-json or place at "
        f"{primary}"
    )


def _run_json(cmd: List[str], *, cwd: Path) -> Dict[str, Any]:
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", cwd=str(cwd))
    stdout = proc.stdout or ""
    stderr = proc.stderr or ""
    if proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(cmd)}\n{stderr or stdout}")
    return json.loads(stdout)


def main() -> int:
    configure_stdio_utf8()
    p = argparse.ArgumentParser(description="Verify OpenClaw pipeline: inspection DB → stats → push digests")
    p.add_argument("--skip-inspection", action="store_true", help="Skip re-run device-inspection-re")
    p.add_argument("--push-http", action="store_true", help="Also POST mock /notifications/push")
    args = p.parse_args()

    applied = apply_openclaw_defaults(force=False)
    ws = _workspace_root()
    py = sys.executable
    campus_scripts = ws / "skills" / "campus-device-ops" / "scripts"
    di_scripts = ws / "skills" / "device-inspection-re" / "scripts"

    if not campus_scripts.is_dir():
        raise FileNotFoundError(f"campus-device-ops scripts not found: {campus_scripts}")

    print("env pinned:", ", ".join(f"{k}=..." for k in sorted(applied.keys())))

    if not args.skip_inspection:
        judge = di_scripts / "judge_rules_re.py"
        if not judge.is_file():
            raise FileNotFoundError(f"judge_rules_re.py not found: {judge}")
        rules = _resolve_rules_path(ws)
        print("1/4 device-inspection-re judge")
        _run_json([py, str(judge), "--rules", str(rules), "--json"], cwd=ws)
    else:
        print("1/4 skip inspection (use existing DB)")

    run_id = get_latest_run_id()
    if not run_id:
        raise RuntimeError("no inspection run in database")
    print(f"    latest runId={run_id}")

    print("2/4 alarm_stats + query_alarms")
    stats = compute_alarm_stats_from_db(run_id=run_id)
    alarms = query_alarms(run_id=run_id)
    assert stats["totalAlertCount"] == len(alarms), "stats vs alarms mismatch"
    assert stats["alarmDeviceCount"] >= 1, "expected at least one fault device"

    print("3/4 push_alert_digest --write-ai-message")
    push_cmd = [
        py,
        str(campus_scripts / "push_alert_digest.py"),
        "--run-id",
        run_id,
        "--write-ai-message",
        "--json",
    ]
    if args.push_http:
        push_cmd.append("--push-http")
    push = _run_json(push_cmd, cwd=ws)
    assert push.get("digestCount", 0) >= 1, "expected at least one digest"
    for d in push.get("digests") or []:
        assert str(d.get("aiMessage", "")).strip(), f"missing aiMessage for {d.get('assigneeId')}"

    print("4/4 build_qa_context")
    _run_json([py, str(campus_scripts / "build_qa_context.py"), "--json"], cwd=ws)

    print(
        f"PIPELINE OK runId={run_id} alerts={stats['totalAlertCount']} "
        f"devices={stats['alarmDeviceCount']} digests={push.get('digestCount')}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc
