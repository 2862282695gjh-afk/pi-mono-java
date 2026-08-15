from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def registry_path() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_REGISTRY_PATH", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return skill_root() / "mock_fixtures" / "devices" / "registry.json"


def load_device_registry() -> Dict[str, Any]:
    path = registry_path()
    if not path.is_file():
        return {"version": 1, "devices": []}
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict):
        raise ValueError(f"registry must be object: {path}")
    return doc


def _index_registry(doc: Dict[str, Any]) -> Tuple[Dict[str, Dict[str, Any]], Dict[Tuple[str, str], Dict[str, Any]]]:
    by_id: Dict[str, Dict[str, Any]] = {}
    by_type_component: Dict[Tuple[str, str], Dict[str, Any]] = {}
    for d in doc.get("devices") or []:
        if not isinstance(d, dict):
            continue
        canonical = str(d.get("deviceId", "")).strip()
        if not canonical:
            continue
        by_id[canonical] = d
        for alias in d.get("legacyIds") or []:
            aid = str(alias).strip()
            if aid:
                by_id[aid] = d
        dt = str(d.get("deviceType", "")).strip()
        comp = str(d.get("component", "")).strip()
        if dt and comp:
            by_type_component[(dt, comp)] = d
        for alt in d.get("legacyComponents") or []:
            alt_comp = str(alt).strip()
            if dt and alt_comp:
                by_type_component[(dt, alt_comp)] = d
    return by_id, by_type_component


def pseudo_device_id(device_type: str, component: str) -> str:
    return f"{(device_type or 'dev').strip()}_{(component or 'comp').strip()}"


def resolve_device(
    raw_device_id: str,
    *,
    device_type: str = "",
    component: str = "",
    registry_doc: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Map mock pseudo-id (deviceType_component) or legacy alias to canonical asset record."""
    doc = registry_doc if registry_doc is not None else load_device_registry()
    by_id, by_tc = _index_registry(doc)
    raw = str(raw_device_id or "").strip()

    if raw and raw in by_id:
        hit = by_id[raw]
    else:
        dt = device_type.strip()
        comp = component.strip()
        hit = by_tc.get((dt, comp)) if dt and comp else None
        if hit is None and raw == pseudo_device_id(dt, comp):
            hit = by_tc.get((dt, comp))

    if hit:
        return {
            "deviceId": str(hit.get("deviceId", raw)).strip() or raw,
            "deviceName": str(hit.get("name", "")).strip(),
            "building": str(hit.get("building", "")).strip(),
            "floor": str(hit.get("floor", "")).strip(),
            "room": str(hit.get("room", "")).strip(),
            "deviceType": str(hit.get("deviceType", device_type)).strip(),
            "component": str(hit.get("component", component)).strip(),
        }

    return {
        "deviceId": raw or pseudo_device_id(device_type, component),
        "deviceName": "",
        "building": "",
        "floor": "",
        "room": "",
        "deviceType": device_type.strip(),
        "component": component.strip(),
    }


def enrich_alert_dict(alert: Dict[str, Any], rule: Dict[str, Any]) -> Dict[str, Any]:
    meta = rule.get("meta") or {}
    device_type = str(meta.get("deviceType", "")).strip()
    component = str(meta.get("component", "")).strip()
    resolved = resolve_device(
        str(alert.get("device_id", "")).strip(),
        device_type=device_type,
        component=component,
    )
    out = dict(alert)
    out["device_id"] = resolved["deviceId"]
    out["device_name"] = resolved["deviceName"]
    out["building"] = resolved["building"]
    out["floor"] = resolved["floor"]
    out["room"] = resolved["room"]
    out["device_type"] = resolved["deviceType"] or device_type
    out["component"] = resolved["component"] or component
    return out
