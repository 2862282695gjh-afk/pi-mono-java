"""Shared helpers. Registry/assignee cache + alarm enrichment synced with device-inspection-re/scripts/_common.py."""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

_ASSIGNEES_BY_ID_CACHE: Optional[Dict[str, Dict[str, Any]]] = None
_REGISTRY_BY_DEVICE_ID_CACHE: Optional[Dict[str, Dict[str, Any]]] = None


DEFAULT_MOCK_API_BASE = "http://127.0.0.1:18082/api/v1"
DEFAULT_FETCH_API_URL = "http://127.0.0.1:18083/fetch"
DEFAULT_MOCK_PORT = 18082
DEFAULT_FETCH_PORT = 18083


def configure_stdio_utf8() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def campusclaw_root() -> Path:
    return skill_root().parents[1]


def device_inspection_re_skill_root() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_SKILL_ROOT", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    openclaw = Path.home() / ".openclaw" / "workspace" / "skills" / "device-inspection-re"
    if openclaw.is_dir():
        return openclaw
    return campusclaw_root() / "skills" / "device-inspection-re"


def default_inspection_db_path() -> Path:
    for key in ("CAMPUS_OPS_DB_PATH", "DEVICE_INSPECTION_RE_DB_PATH"):
        env = os.environ.get(key, "").strip()
        if env:
            return Path(env).expanduser().resolve()
    return device_inspection_re_skill_root() / "mock_fixtures" / "device_inspection_re.db"


def device_inspection_re_judge_script() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_JUDGE_SCRIPT", "").strip()
    if env:
        p = Path(env).expanduser().resolve()
        if p.is_file():
            return p
    p = device_inspection_re_skill_root() / "scripts" / "judge_rules_re.py"
    if p.is_file():
        return p
    raise FileNotFoundError(
        "device-inspection-re judge_rules_re.py not found; set DEVICE_INSPECTION_RE_JUDGE_SCRIPT"
    )


def mock_fixtures_dir() -> Path:
    env = os.environ.get("CAMPUS_OPS_FIXTURES_DIR", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return skill_root() / "mock_fixtures"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def default_rules_re_path() -> Path:
    env = os.environ.get("CAMPUS_OPS_RULES_PATH", "").strip()
    if env:
        p = Path(env).expanduser().resolve()
        if p.is_file():
            return p
    root = campusclaw_root()
    candidates = [
        root / "rules" / "rules_re.json",
        Path.home() / ".openclaw" / "workspace" / "rules" / "rules_re.json",
        root / "rule-engines-pack" / "02-rule-engine-pypi" / "rules" / "rules_re.json",
    ]
    for c in candidates:
        if c.is_file():
            return c
    raise FileNotFoundError(
        "rules_re.json not found; set CAMPUS_OPS_RULES_PATH or place at .campusclaw/rules/rules_re.json"
    )


def timeseries_fixtures_dir() -> Path:
    env = os.environ.get("CAMPUS_OPS_TIMESERIES_FIXTURES_DIR", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return mock_fixtures_dir() / "timeseries"


def judge_rules_script() -> Path:
    """Deprecated local judge; use device-inspection-re judge_rules_re.py instead."""
    raise FileNotFoundError(
        "campus-device-ops judge_rules.py removed; run device-inspection-re/scripts/judge_rules_re.py"
    )


def device_inspection_re_registry_path() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_REGISTRY_PATH", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return device_inspection_re_skill_root() / "mock_fixtures" / "devices" / "registry.json"


def load_assignee_map() -> Dict[str, Any]:
    path = mock_fixtures_dir() / "devices" / "assignee_map.json"
    if not path.is_file():
        return {"version": 1, "assigneesByDeviceId": {}, "statusByDeviceId": {}}
    doc = load_json(path)
    if not isinstance(doc, dict):
        raise ValueError(f"assignee_map must be object: {path}")
    return doc


def load_device_registry() -> Dict[str, Any]:
    """Canonical devices from device-inspection-re; assigneeId/status from campus assignee_map."""
    path = device_inspection_re_registry_path()
    doc = load_json(path)
    if not isinstance(doc, dict):
        raise ValueError(f"registry must be object: {path}")
    overlay = load_assignee_map()
    by_device = overlay.get("assigneesByDeviceId") or {}
    status_by = overlay.get("statusByDeviceId") or {}
    devices: List[Dict[str, Any]] = []
    for d in doc.get("devices") or []:
        if not isinstance(d, dict):
            continue
        row = dict(d)
        did = str(row.get("deviceId", "")).strip()
        if did in by_device:
            row["assigneeId"] = by_device[did]
        if did in status_by:
            row["status"] = status_by[did]
        elif "status" not in row:
            row["status"] = "online"
        devices.append(row)
    return {"version": doc.get("version", 1), "campus": doc.get("campus", ""), "devices": devices}


def load_assignees() -> Dict[str, Any]:
    path = mock_fixtures_dir() / "contacts" / "assignees.json"
    if not path.is_file():
        return {"version": 1, "assignees": []}
    doc = load_json(path)
    if not isinstance(doc, dict):
        raise ValueError(f"assignees must be object: {path}")
    return doc


def registry_by_device_id() -> Dict[str, Dict[str, Any]]:
    global _REGISTRY_BY_DEVICE_ID_CACHE
    if _REGISTRY_BY_DEVICE_ID_CACHE is not None:
        return _REGISTRY_BY_DEVICE_ID_CACHE
    doc = load_device_registry()
    out: Dict[str, Dict[str, Any]] = {}
    for d in doc.get("devices") or []:
        if not isinstance(d, dict):
            continue
        canonical = str(d.get("deviceId", "")).strip()
        if not canonical:
            continue
        out[canonical] = d
        for alias in d.get("legacyIds") or []:
            aid = str(alias).strip()
            if aid:
                out[aid] = d
    _REGISTRY_BY_DEVICE_ID_CACHE = out
    return out


def registry_lookup(device_id: str) -> Dict[str, Any]:
    did = str(device_id or "").strip()
    if not did:
        return {}
    hit = registry_by_device_id().get(did)
    return dict(hit) if hit else {}


def assignees_by_id() -> Dict[str, Dict[str, Any]]:
    global _ASSIGNEES_BY_ID_CACHE
    if _ASSIGNEES_BY_ID_CACHE is not None:
        return _ASSIGNEES_BY_ID_CACHE
    doc = load_assignees()
    out: Dict[str, Dict[str, Any]] = {}
    for a in doc.get("assignees") or []:
        if isinstance(a, dict):
            aid = str(a.get("assigneeId", "")).strip()
            if aid:
                out[aid] = a
    _ASSIGNEES_BY_ID_CACHE = out
    return out


def state_dir() -> Path:
    return mock_fixtures_dir() / "state"


def last_inspection_path() -> Path:
    return state_dir() / "last_inspection.json"


def work_orders_dir() -> Path:
    return mock_fixtures_dir() / "work_orders"


def notifications_outbox_dir() -> Path:
    return mock_fixtures_dir() / "notifications" / "outbox"


def api_base_url() -> str:
    return os.environ.get("CAMPUS_OPS_API_URL", DEFAULT_MOCK_API_BASE).rstrip("/")


def is_port_listening(host: str, port: int, *, timeout_s: float = 0.5) -> bool:
    sock = socket.socket()
    try:
        sock.settimeout(timeout_s)
        return sock.connect_ex((host, port)) == 0
    finally:
        try:
            sock.close()
        except Exception:
            pass


def ensure_fetch_api_running() -> None:
    """Start campus-device-ops :18083/fetch mock if not already listening."""
    if is_port_listening("127.0.0.1", DEFAULT_FETCH_PORT):
        return
    env_script = os.environ.get("CAMPUS_OPS_FETCH_API_SCRIPT", "").strip()
    script = Path(env_script).expanduser() if env_script else skill_root() / "fetch_api_server.py"
    if not script.is_file():
        return
    try:
        subprocess.Popen(
            [sys.executable, str(script)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        )
    except Exception:
        return
    deadline = time.time() + 3.0
    while time.time() < deadline:
        if is_port_listening("127.0.0.1", DEFAULT_FETCH_PORT):
            return
        time.sleep(0.1)


def ensure_mock_api_server() -> None:
    if is_port_listening("127.0.0.1", DEFAULT_MOCK_PORT):
        return
    script = skill_root() / "mock_api_server.py"
    if not script.is_file():
        return
    try:
        subprocess.Popen(
            [sys.executable, str(script)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        )
    except Exception:
        return
    deadline = time.time() + 3.0
    while time.time() < deadline:
        if is_port_listening("127.0.0.1", DEFAULT_MOCK_PORT):
            return
        time.sleep(0.1)


def http_get_json(path: str, *, query: Optional[Dict[str, str]] = None) -> Any:
    ensure_mock_api_server()
    url = f"{api_base_url()}{path}"
    if query:
        qs = "&".join(f"{k}={urllib.parse.quote(v)}" for k, v in query.items() if v is not None)
        if qs:
            url = f"{url}?{qs}"
    req = urllib.request.Request(url=url, method="GET", headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_post_json(path: str, body: Dict[str, Any]) -> Any:
    ensure_mock_api_server()
    url = f"{api_base_url()}{path}"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url=url,
        method="POST",
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


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


def enrich_inspection_with_registry(inspection: Dict[str, Any]) -> Dict[str, Any]:
    assignees = assignees_by_id()
    enriched_devices: List[Dict[str, Any]] = []
    for did in inspection.get("fault_devices") or []:
        meta = registry_lookup(str(did))
        aid = str(meta.get("assigneeId", "")).strip()
        assignee = assignees.get(aid, {})
        enriched_devices.append(
            {
                "deviceId": str(did),
                "name": meta.get("name", ""),
                "building": meta.get("building", ""),
                "floor": meta.get("floor", ""),
                "room": meta.get("room", ""),
                "deviceType": meta.get("deviceType", ""),
                "component": meta.get("component", ""),
                "assigneeId": aid,
                "assigneeName": assignee.get("name", ""),
            }
        )
    out = dict(inspection)
    out["enriched_fault_devices"] = enriched_devices
    return out
