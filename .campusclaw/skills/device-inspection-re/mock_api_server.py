from __future__ import annotations

import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Dict, List, Tuple

_SKILL_DIR = Path(__file__).resolve().parent / "scripts"
sys.path.insert(0, str(_SKILL_DIR))

from fixture_store import (  # type: ignore  # noqa: E402
    load_device_fixture,
    load_legacy_rule_fixture,
    project_points,
)
from judge_rules_re import JudgeError  # type: ignore  # noqa: E402
from device_registry import canonical_device_id, resolve_device  # type: ignore  # noqa: E402

try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass


def _now_ts() -> float:
    return datetime.now(tz=timezone.utc).timestamp()


def _read_json_body(handler: BaseHTTPRequestHandler) -> Dict[str, Any]:
    length = int(handler.headers.get("Content-Length", "0") or "0")
    raw = handler.rfile.read(length) if length > 0 else b"{}"
    obj = json.loads(raw.decode("utf-8"))
    if not isinstance(obj, dict):
        raise ValueError("json body must be an object")
    return obj


def _device_id(device_type: str, component: str) -> str:
    return canonical_device_id(device_type, component)


def _should_fault(*, device_id: str, request_id: str, end_ts: float) -> bool:
    salt = int(float(end_ts) // 3600)
    key = f"{device_id}|{request_id}|{salt}".encode("utf-8")
    bucket = int(hashlib.sha1(key).hexdigest()[:8], 16) % 100
    return bucket < 10


def _synthetic_series(
    *,
    points: List[str],
    window_seconds: int,
    end_ts: float,
    fault: bool,
    device_type: str,
    component: str,
) -> Tuple[str, List[Dict[str, Any]]]:
    start_ts = end_ts - float(window_seconds)
    step = 60
    samples = max(2, int(window_seconds / step) + 1)
    data: List[Dict[str, Any]] = []
    for i in range(samples):
        ts = start_ts + i * step
        pv: Dict[str, Any] = {}
        for p in points:
            pl = p.lower()
            if "alarm" in pl or "fault" in pl:
                pv[p] = 0.0 if fault else 1.0
            elif fault:
                pv[p] = 5000.0 if "co2" in pl else -10.0
            else:
                pv[p] = 800.0 if "co2" in pl else 22.0
        data.append({"ts": ts, "points": pv})
    resolved = resolve_device(_device_id(device_type, component), device_type=device_type, component=component)
    return resolved["deviceId"], data


def _build_timeseries_for_query(query: Dict[str, Any], *, end_ts: float) -> List[Tuple[str, List[Dict[str, Any]]]]:
    device_type = str(query.get("deviceType", "")).strip()
    component = str(query.get("component", "")).strip()
    points = [str(p).strip() for p in (query.get("points") or []) if str(p).strip()]
    window_seconds = int(query.get("windowSeconds", 0) or 0)
    if window_seconds <= 0:
        raise JudgeError("invalid windowSeconds")

    request_id = str(query.get("requestId", "")).strip()
    pseudo = _device_id(device_type, component)

    device_doc = load_device_fixture(
        device_type=device_type,
        component=component,
        raw_device_id=pseudo,
        request_id=request_id,
    )
    if device_doc is not None:
        data = project_points(list(device_doc.get("data") or []), points)
        return [(str(device_doc["deviceId"]), data)]

    legacy = load_legacy_rule_fixture(request_id=request_id)
    if legacy is not None:
        canonical, full_data = legacy
        resolved = resolve_device(canonical or pseudo, device_type=device_type, component=component)
        data = project_points(full_data, points)
        return [(resolved["deviceId"], data)]

    fault = _should_fault(device_id=pseudo, request_id=request_id, end_ts=end_ts)
    did, data = _synthetic_series(
        points=points,
        window_seconds=window_seconds,
        end_ts=end_ts,
        fault=fault,
        device_type=device_type,
        component=component,
    )
    return [(did, data)]


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code: int, obj: Any) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/fetch":
            self._send_json(404, {"error": "not found"})
            return
        req: Dict[str, Any] = {}
        try:
            req = _read_json_body(self)
            end_ts = float(req.get("endTs", _now_ts()))
            if isinstance(req.get("query"), dict):
                queries = [req["query"]]
            elif isinstance(req.get("queries"), list):
                queries = [q for q in req["queries"] if isinstance(q, dict)]
            else:
                raise ValueError("must provide query or queries")
            items: List[Dict[str, Any]] = []
            for q in queries:
                for did, data in _build_timeseries_for_query(q, end_ts=end_ts):
                    items.append(
                        {
                            "deviceId": did,
                            "requestId": str(q.get("requestId", "")).strip(),
                            "deviceType": str(q.get("deviceType", "")).strip(),
                            "component": str(q.get("component", "")).strip(),
                            "points": q.get("points", []),
                            "data": data,
                        }
                    )
            self._send_json(200, {"version": 1, "items": items})
        except Exception as e:  # noqa: BLE001
            self._send_json(400, {"error": str(e), "received": req})

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return


def main() -> int:
    host = "127.0.0.1"
    port = int(os.environ.get("DEVICE_INSPECTION_RE_MOCK_PORT", "18081"))
    httpd = HTTPServer((host, port), Handler)
    print(f"device-inspection-re mock api on http://{host}:{port}/fetch")
    httpd.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
