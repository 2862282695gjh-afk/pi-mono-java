from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from device_registry import load_device_registry, resolve_device


def fixtures_dir() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_FIXTURES_DIR", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return Path(__file__).resolve().parents[1] / "mock_fixtures"


def device_fixture_path(canonical_device_id: str) -> Path:
    return fixtures_dir() / "devices" / f"{canonical_device_id}.json"


def legacy_rule_fixture_path(request_id: str) -> Path:
    return fixtures_dir() / f"{request_id}.json"


def _read_fixture_doc(path: Path) -> Dict[str, Any]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict):
        raise ValueError(f"fixture must be object: {path}")
    return doc


def _extract_series(doc: Dict[str, Any]) -> Tuple[str, List[Dict[str, Any]]]:
    device_type = str(doc.get("deviceType", "")).strip()
    component = str(doc.get("component", "")).strip()
    if isinstance(doc.get("devices"), list):
        for entry in doc["devices"]:
            if not isinstance(entry, dict):
                continue
            data = entry.get("data")
            if not isinstance(data, list) or not data:
                continue
            raw = str(entry.get("deviceId", "")).strip()
            resolved = resolve_device(raw, device_type=device_type, component=component)
            return resolved["deviceId"], data
    data = doc.get("data")
    if isinstance(data, list) and data:
        raw = str(doc.get("deviceId", "")).strip()
        resolved = resolve_device(raw, device_type=device_type, component=component)
        return resolved["deviceId"], data
    return "", []


def _merge_point_values(existing: Any, new: Any) -> Any:
    if existing is None:
        return new
    if isinstance(existing, (int, float)) and isinstance(new, (int, float)):
        ex = float(existing)
        nv = float(new)
        if ex == nv:
            return ex
        return max(ex, nv)
    return new


def merge_series(series_list: List[List[Dict[str, Any]]]) -> List[Dict[str, Any]]:
    if not series_list:
        return []
    max_len = max(len(s) for s in series_list)
    merged: List[Dict[str, Any]] = []
    for idx in range(max_len):
        ts: Optional[float] = None
        points: Dict[str, Any] = {}
        for series in series_list:
            if not series:
                continue
            row = series[idx] if idx < len(series) else series[-1]
            if ts is None:
                ts = float(row.get("ts", 0))
            row_points = row.get("points") or {}
            if isinstance(row_points, dict):
                for name, value in row_points.items():
                    points[name] = _merge_point_values(points.get(name), value)
        if ts is not None:
            merged.append({"ts": ts, "points": points})
    return merged


def build_device_fixture_doc(
    *,
    canonical_device_id: str,
    device_type: str,
    component: str,
    data: List[Dict[str, Any]],
    scenario: str = "fault",
    rule_series: Optional[Dict[str, List[Dict[str, Any]]]] = None,
) -> Dict[str, Any]:
    doc: Dict[str, Any] = {
        "version": 2,
        "deviceId": canonical_device_id,
        "deviceType": device_type,
        "component": component,
        "scenario": scenario,
        "data": data,
    }
    if rule_series:
        doc["ruleSeries"] = rule_series
    return doc


def write_device_fixture(path: Path, doc: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")


def select_device_series(doc: Dict[str, Any], *, request_id: str = "") -> List[Dict[str, Any]]:
    rid = str(request_id or "").strip()
    rule_series = doc.get("ruleSeries")
    if rid and isinstance(rule_series, dict) and isinstance(rule_series.get(rid), list):
        return list(rule_series[rid])
    data = doc.get("data")
    if isinstance(data, list) and data:
        return data
    _, extracted = _extract_series(doc)
    return extracted


def load_device_fixture(
    *,
    device_type: str,
    component: str,
    raw_device_id: str = "",
    request_id: str = "",
) -> Optional[Dict[str, Any]]:
    resolved = resolve_device(raw_device_id, device_type=device_type, component=component)
    canonical = str(resolved.get("deviceId", "")).strip()
    if not canonical:
        return None
    path = device_fixture_path(canonical)
    if not path.is_file():
        return None
    doc = _read_fixture_doc(path)
    data = select_device_series(doc, request_id=request_id)
    if not data:
        return None
    return {
        "deviceId": canonical,
        "deviceType": str(doc.get("deviceType", device_type)).strip() or device_type,
        "component": str(doc.get("component", component)).strip() or component,
        "scenario": str(doc.get("scenario", "fault")).strip() or "fault",
        "data": data,
    }


def load_legacy_rule_fixture(*, request_id: str) -> Optional[Tuple[str, List[Dict[str, Any]]]]:
    if not request_id:
        return None
    path = legacy_rule_fixture_path(request_id)
    if not path.is_file():
        return None
    doc = _read_fixture_doc(path)
    return _extract_series(doc)


def project_points(data: List[Dict[str, Any]], points: List[str]) -> List[Dict[str, Any]]:
    wanted = [str(p).strip() for p in points if str(p).strip()]
    if not wanted:
        return list(data)
    out: List[Dict[str, Any]] = []
    for row in data:
        src = row.get("points") or {}
        if not isinstance(src, dict):
            src = {}
        projected = {name: src[name] for name in wanted if name in src}
        out.append({"ts": row.get("ts"), "points": projected})
    return out


def list_rule_fixture_paths(*, fixtures_root: Optional[Path] = None) -> List[Path]:
    root = fixtures_root or fixtures_dir()
    skip = {"scenario_overrides.json"}
    paths: List[Path] = []
    for path in sorted(root.glob("*.json")):
        if path.name in skip:
            continue
        paths.append(path)
    return paths


def _load_scenario_overrides() -> Dict[str, Any]:
    path = fixtures_dir() / "scenario_overrides.json"
    if not path.is_file():
        return {}
    doc = _read_fixture_doc(path)
    return doc if isinstance(doc, dict) else {}


def consolidate_rule_fixtures_to_devices(
    *,
    out_dir: Optional[Path] = None,
    source_dir: Optional[Path] = None,
) -> Dict[str, Path]:
    src = source_dir or fixtures_dir()
    out_root = out_dir or (fixtures_dir() / "devices")
    overrides = _load_scenario_overrides()
    healthy_rule_ids = {str(x).strip() for x in (overrides.get("healthyRuleIds") or []) if str(x).strip()}

    grouped: Dict[str, Dict[str, Any]] = {}
    for path in list_rule_fixture_paths(fixtures_root=src):
        doc = _read_fixture_doc(path)
        rule_id = str(doc.get("requestId") or path.stem).strip()
        canonical, series = _extract_series(doc)
        if not canonical or not series or not rule_id:
            continue
        scenario = str(doc.get("scenario", "fault")).strip() or "fault"
        if rule_id in healthy_rule_ids:
            scenario = "healthy"
        bucket = grouped.setdefault(
            canonical,
            {
                "deviceType": str(doc.get("deviceType", "")).strip(),
                "component": str(doc.get("component", "")).strip(),
                "series_list": [],
                "scenarios": [],
                "ruleSeries": {},
            },
        )
        if not bucket["deviceType"]:
            meta = resolve_device(canonical)
            bucket["deviceType"] = meta.get("deviceType", "")
            bucket["component"] = meta.get("component", "")
        bucket["series_list"].append(series)
        bucket["scenarios"].append(scenario)
        bucket["ruleSeries"][rule_id] = series

    written: Dict[str, Path] = {}
    registry = load_device_registry()
    by_id = {str(d.get("deviceId", "")).strip(): d for d in registry.get("devices") or [] if isinstance(d, dict)}
    for canonical, bucket in grouped.items():
        reg = by_id.get(canonical, {})
        device_type = str(bucket.get("deviceType") or reg.get("deviceType", "")).strip()
        component = str(bucket.get("component") or reg.get("component", "")).strip()
        scenarios = list(bucket.get("scenarios") or [])
        scenario = "healthy" if scenarios and all(s == "healthy" for s in scenarios) else "fault"
        data = merge_series(list(bucket.get("series_list") or []))
        rule_series = dict(bucket.get("ruleSeries") or {})
        doc = build_device_fixture_doc(
            canonical_device_id=canonical,
            device_type=device_type,
            component=component,
            data=data,
            scenario=scenario,
            rule_series=rule_series,
        )
        target = out_root / f"{canonical}.json"
        write_device_fixture(target, doc)
        written[canonical] = target
    return written
