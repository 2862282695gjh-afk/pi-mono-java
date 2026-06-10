from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List

_ASSIGNEES_BY_ID_CACHE: Dict[str, Dict[str, Any]] | None = None
_REGISTRY_BY_DEVICE_ID_CACHE: Dict[str, Dict[str, Any]] | None = None


def configure_stdio_utf8() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def campus_ops_skill_root() -> Path:
    env = os.environ.get("CAMPUS_DEVICE_OPS_SKILL_ROOT", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    openclaw = Path.home() / ".openclaw" / "workspace" / "skills" / "campus-device-ops"
    if openclaw.is_dir():
        return openclaw
    sibling = skill_root().parent / "campus-device-ops"
    return sibling


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def load_assignee_map() -> Dict[str, Any]:
    path = campus_ops_skill_root() / "mock_fixtures" / "devices" / "assignee_map.json"
    if not path.is_file():
        return {"version": 1, "assigneesByDeviceId": {}, "statusByDeviceId": {}}
    doc = load_json(path)
    if not isinstance(doc, dict):
        raise ValueError(f"assignee_map must be object: {path}")
    return doc


def load_assignees() -> Dict[str, Any]:
    path = campus_ops_skill_root() / "mock_fixtures" / "contacts" / "assignees.json"
    if not path.is_file():
        return {"version": 1, "assignees": []}
    doc = load_json(path)
    if not isinstance(doc, dict):
        raise ValueError(f"assignees must be object: {path}")
    return doc


def assignees_by_id() -> Dict[str, Dict[str, Any]]:
    global _ASSIGNEES_BY_ID_CACHE
    if _ASSIGNEES_BY_ID_CACHE is not None:
        return _ASSIGNEES_BY_ID_CACHE
    doc = load_assignees()
    out: Dict[str, Dict[str, Any]] = {}
    for row in doc.get("assignees") or []:
        if isinstance(row, dict):
            aid = str(row.get("assigneeId", "")).strip()
            if aid:
                out[aid] = row
    _ASSIGNEES_BY_ID_CACHE = out
    return out


def registry_by_device_id() -> Dict[str, Dict[str, Any]]:
    global _REGISTRY_BY_DEVICE_ID_CACHE
    if _REGISTRY_BY_DEVICE_ID_CACHE is not None:
        return _REGISTRY_BY_DEVICE_ID_CACHE
    from device_registry import load_device_registry

    doc = load_device_registry()
    overlay = load_assignee_map()
    by_device = overlay.get("assigneesByDeviceId") or {}
    assignees = assignees_by_id()
    out: Dict[str, Dict[str, Any]] = {}
    for row in doc.get("devices") or []:
        if not isinstance(row, dict):
            continue
        item = dict(row)
        did = str(item.get("deviceId", "")).strip()
        if did in by_device:
            item["assigneeId"] = by_device[did]
        assignee = assignees.get(str(item.get("assigneeId", "")).strip(), {})
        if assignee.get("name"):
            item["assigneeName"] = assignee["name"]
        if did:
            out[did] = item
        for alias in row.get("legacyIds") or []:
            aid = str(alias).strip()
            if aid:
                out[aid] = item
    _REGISTRY_BY_DEVICE_ID_CACHE = out
    return out


def registry_lookup(device_id: str) -> Dict[str, Any]:
    did = str(device_id or "").strip()
    if not did:
        return {}
    hit = registry_by_device_id().get(did)
    return dict(hit) if hit else {}


def work_orders_dir() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_WORK_ORDERS_DIR", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return skill_root() / "mock_fixtures" / "work_orders"


def infer_alert_priority(rule_name: str) -> str:
    name = str(rule_name or "").lower()
    if any(k in name for k in ("故障", "fault", "inverter", "压缩机")):
        return "high"
    if any(k in name for k in ("超限", "overlimit", "over_range")):
        return "medium"
    return "low"


def enrich_alarm_row(alarm: Dict[str, Any]) -> Dict[str, Any]:
    row = dict(alarm)
    meta = registry_lookup(str(row.get("deviceId", "")))
    aid = str(meta.get("assigneeId", "")).strip() or "unassigned"
    assignee = assignees_by_id().get(aid, {})
    row.setdefault("deviceName", meta.get("name", row.get("deviceName", "")))
    row.setdefault("building", meta.get("building", row.get("building", "")))
    row["assigneeId"] = aid
    row["assigneeName"] = str(assignee.get("name", aid))
    row["priority"] = infer_alert_priority(str(row.get("ruleName", "")))
    return row
