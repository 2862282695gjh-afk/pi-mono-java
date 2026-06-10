from __future__ import annotations

import os
from pathlib import Path
from typing import Dict


def openclaw_workspace_root() -> Path:
    env = os.environ.get("OPENCLAW_WORKSPACE", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return Path.home() / ".openclaw" / "workspace"


def apply_openclaw_defaults(*, force: bool = True) -> Dict[str, str]:
    """
    Pin skill paths for OpenClaw workspace so ops reads the same DB/rules as device-inspection-re.
    Returns env keys that were set.
    """
    ws = openclaw_workspace_root()
    applied: Dict[str, str] = {}

    def put(key: str, value: str) -> None:
        if force or not os.environ.get(key, "").strip():
            os.environ[key] = value
            applied[key] = value

    di_re = ws / "skills" / "device-inspection-re"
    campus = ws / "skills" / "campus-device-ops"
    rules = ws / "rules" / "rules_re.json"
    db = di_re / "mock_fixtures" / "device_inspection_re.db"
    fixtures = di_re / "mock_fixtures"
    registry = fixtures / "devices" / "registry.json"

    if di_re.is_dir():
        put("DEVICE_INSPECTION_RE_SKILL_ROOT", str(di_re))
        put("DEVICE_INSPECTION_RE_FIXTURES_DIR", str(fixtures))
        put("DEVICE_INSPECTION_RE_REGISTRY_PATH", str(registry))
    if db.parent.is_dir():
        put("DEVICE_INSPECTION_RE_DB_PATH", str(db))
        put("CAMPUS_OPS_DB_PATH", str(db))
    if rules.is_file():
        put("DEVICE_INSPECTION_RE_RULES_PATH", str(rules))
    if campus.is_dir():
        put("CAMPUS_OPS_FIXTURES_DIR", str(campus / "mock_fixtures"))

    pack_rules = Path(__file__).resolve().parents[3] / "rule-engines-pack" / "02-rule-engine-pypi" / "rules" / "rules_re.json"
    if rules.is_file() and os.environ.get("DEVICE_INSPECTION_RE_RULES_PATH", "") == str(pack_rules.resolve()):
        put("DEVICE_INSPECTION_RE_RULES_PATH", str(rules))

    return applied


if __name__ == "__main__":
    import json
    import sys

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    applied = apply_openclaw_defaults(force=True)
    print(json.dumps(applied, ensure_ascii=False, indent=2))
