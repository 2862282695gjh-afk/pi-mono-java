from __future__ import annotations

import os
from pathlib import Path
from typing import Dict, Optional


def _workspace_root() -> Path:
    return Path(__file__).resolve().parents[2].parent


def _find_rules_re() -> Optional[Path]:
    rels = (
        "skills/device-inspection-re/rules/rules_re.json",
        "rule-engines-pack/02-rule-engine-pypi/rules/rules_re.json",
    )
    root = _workspace_root()
    for base in (root, *root.parents):
        for rel in rels:
            candidate = base / rel
            if candidate.is_file():
                return candidate
    return None


def openclaw_workspace_root() -> Path:
    env = os.environ.get("OPENCLAW_WORKSPACE", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    ws = _workspace_root()
    if (ws / "skills" / "campus-device-ops").is_dir():
        return ws
    return Path.home() / ".openclaw" / "workspace"


def apply_openclaw_defaults(*, force: bool = False) -> Dict[str, str]:
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
    rules_path = ws / "skills" / "device-inspection-re" / "rules" / "rules_re.json"
    if not rules_path.is_file():
        found = _find_rules_re()
        if found is not None:
            rules_path = found
    if rules_path.is_file():
        put("DEVICE_INSPECTION_RE_RULES_PATH", str(rules_path))
    if campus.is_dir():
        put("CAMPUS_OPS_FIXTURES_DIR", str(campus / "mock_fixtures"))

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
