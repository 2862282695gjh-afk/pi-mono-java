"""
Campus device ops mock API (port 18082).

Implements the contract documented in reference.md.
Time-series fetch for inspection uses this skill's fetch_api_server.py (:18083/fetch).
"""
from __future__ import annotations

import json
import os
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_SKILL_DIR = Path(__file__).resolve().parent
_SCRIPTS = _SKILL_DIR / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

import _common  # noqa: E402
from build_qa_context import build_qa_context  # noqa: E402
from create_work_order import create_work_order  # noqa: E402
from db_store import (  # noqa: E402
    active_alarms_payload,
    compute_alarm_stats_from_db,
    list_inspection_runs,
    query_alarms,
)
from push_alert_digest import build_digests  # noqa: E402
from query_devices import query_devices_local  # noqa: E402
from run_inspection import run_inspection_local  # noqa: E402

try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

HOST = "127.0.0.1"
PORT = int(os.environ.get("CAMPUS_OPS_MOCK_PORT", "18082"))


def _read_json_body(handler: BaseHTTPRequestHandler) -> Dict[str, Any]:
    length = int(handler.headers.get("Content-Length", "0") or "0")
    raw = handler.rfile.read(length) if length > 0 else b"{}"
    obj = json.loads(raw.decode("utf-8"))
    if not isinstance(obj, dict):
        raise ValueError("JSON body must be an object")
    return obj


def _send_json(handler: BaseHTTPRequestHandler, status: int, obj: Any) -> None:
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _parse_path(path: str) -> Tuple[str, Dict[str, str]]:
    if "?" in path:
        p, qs = path.split("?", 1)
        params = urllib.parse.parse_qs(qs)
        flat = {k: (v[0] if v else "") for k, v in params.items()}
        return p, flat
    return path, {}


def _route_get(path: str, query: Dict[str, str]) -> Tuple[int, Any]:
    if path == "/api/v1/health":
        return 200, {"status": "ok", "service": "campus-device-ops-mock", "port": PORT}

    if path == "/api/v1/devices":
        with_alarms = query.get("withAlarms", "").lower() in ("1", "true", "yes")
        result = query_devices_local(
            building=query.get("building") or None,
            device_type=query.get("deviceType") or None,
            status=query.get("status") or "all",
            device_id=query.get("deviceId") or None,
            with_alarms=with_alarms,
            run_id=query.get("runId") or "latest",
        )
        return 200, result

    if path == "/api/v1/inspection/runs":
        limit = int(query.get("limit", "20") or "20")
        return 200, {"version": 1, "runs": list_inspection_runs(limit=limit)}

    if path.startswith("/api/v1/devices/"):
        device_id = path.split("/api/v1/devices/", 1)[1].strip("/")
        result = query_devices_local(device_id=device_id)
        devices = result.get("devices") or []
        if not devices:
            return 404, {"error": "device not found", "deviceId": device_id}
        return 200, {"version": 1, "device": devices[0]}

    if path == "/api/v1/alarms":
        run_id = query.get("runId") or "latest"
        alarms = query_alarms(
            run_id=run_id,
            building=query.get("building") or None,
            device_id=query.get("deviceId") or None,
            rule_id=query.get("ruleId") or None,
        )
        return 200, {"version": 1, "runId": run_id, "total": len(alarms), "alarms": alarms}

    if path == "/api/v1/alarms/active":
        try:
            return 200, active_alarms_payload(run_id=query.get("runId") or "latest")
        except FileNotFoundError:
            return 200, {"version": 1, "runId": None, "endTs": None, "faultDevices": [], "alertsByDevice": {}}

    if path == "/api/v1/alarms/stats":
        try:
            return 200, compute_alarm_stats_from_db(run_id=query.get("runId") or "latest")
        except FileNotFoundError:
            return 404, {"error": "no inspection run yet; run device-inspection-re/scripts/judge_rules_re.py first"}

    if path == "/api/v1/qa/context":
        try:
            from db_store import load_inspection_doc

            doc = load_inspection_doc(run_id=query.get("runId") or "latest")
        except FileNotFoundError:
            return 404, {"error": "no inspection run yet"}
        max_alarms = int(query.get("maxAlarms", "100") or "100")
        return 200, build_qa_context(doc, max_alarms=max_alarms)

    if path == "/api/v1/work-orders":
        wo_dir = _common.work_orders_dir()
        orders: List[Dict[str, Any]] = []
        if wo_dir.is_dir():
            for f in sorted(wo_dir.glob("WO-*.json")):
                obj = _common.load_json(f)
                if isinstance(obj, dict):
                    orders.append(obj)
        return 200, {"version": 1, "total": len(orders), "workOrders": orders}

    if path.startswith("/api/v1/work-orders/"):
        wo_id = path.split("/api/v1/work-orders/", 1)[1].strip("/")
        f = _common.work_orders_dir() / f"{wo_id}.json"
        if not f.is_file():
            return 404, {"error": "work order not found", "id": wo_id}
        return 200, {"version": 1, "workOrder": _common.load_json(f)}

    if path == "/api/v1/contacts/assignees":
        return 200, _common.load_assignees()

    return 404, {"error": "not found", "path": path}


def _route_post(path: str, body: Dict[str, Any]) -> Tuple[int, Any]:
    if path == "/api/v1/inspection/run":
        rules_path = Path(str(body.get("rulesPath", "")).strip()) if body.get("rulesPath") else _common.default_rules_re_path()
        end_ts = body.get("endTs")
        end_ts_str = str(end_ts).strip() if end_ts is not None else None
        doc = run_inspection_local(rules_path=rules_path, end_ts=end_ts_str, output=None)
        return 200, doc

    if path == "/api/v1/work-orders":
        device_id = str(body.get("deviceId", "")).strip()
        if not device_id:
            return 400, {"error": "deviceId required"}
        try:
            from db_store import load_inspection_doc

            doc = load_inspection_doc(run_id=str(body.get("runId") or "latest"))
        except FileNotFoundError:
            return 404, {"error": "no inspection run yet"}
        rule_ids_raw = body.get("ruleIds") or body.get("alarmIds") or []
        rule_ids = [str(x).strip() for x in rule_ids_raw if str(x).strip()] if isinstance(rule_ids_raw, list) else []
        try:
            wo = create_work_order(
                device_id=device_id,
                rule_ids=rule_ids,
                inspection_doc=doc,
                assignee_id=str(body.get("assigneeId", "")).strip() or None,
                problem_analysis=body.get("problemAnalysis") if isinstance(body.get("problemAnalysis"), dict) else None,
                disposal_suggestions=body.get("disposalSuggestions")
                if isinstance(body.get("disposalSuggestions"), dict)
                else None,
            )
        except ValueError as e:
            return 400, {"error": str(e)}
        out = _common.work_orders_dir() / f"{wo['id']}.json"
        _common.write_json(out, wo)
        return 201, {"version": 1, "workOrder": wo}

    if path == "/api/v1/notifications/push":
        try:
            from db_store import load_inspection_doc

            doc = load_inspection_doc(run_id=str(body.get("runId") or "latest"))
        except FileNotFoundError:
            return 404, {"error": "no inspection run yet"}
        digests = build_digests(doc)
        assignee_filter = str(body.get("assigneeId", "")).strip()
        if assignee_filter:
            digests = [d for d in digests if d.get("assigneeId") == assignee_filter]
        out_dir = _common.notifications_outbox_dir()
        out_dir.mkdir(parents=True, exist_ok=True)
        files: List[str] = []
        for d in digests:
            p = out_dir / f"{d['digestId']}.json"
            _common.write_json(p, d)
            files.append(str(p))
        ai_message = str(body.get("aiMessage", "")).strip()
        if ai_message and digests:
            for d in digests:
                d["aiMessage"] = ai_message
                _common.write_json(out_dir / f"{d['digestId']}.json", d)
        return 200, {"version": 1, "digestCount": len(digests), "files": files, "digests": digests}

    return 404, {"error": "not found", "path": path}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def do_GET(self) -> None:
        try:
            path, query = _parse_path(self.path)
            status, obj = _route_get(path, query)
            _send_json(self, status, obj)
        except Exception as e:  # noqa: BLE001
            _send_json(self, 500, {"error": str(e)})

    def do_POST(self) -> None:
        try:
            path, _ = _parse_path(self.path)
            body = _read_json_body(self)
            status, obj = _route_post(path, body)
            _send_json(self, status, obj)
        except Exception as e:  # noqa: BLE001
            _send_json(self, 500, {"error": str(e)})


def main() -> None:
    server = HTTPServer((HOST, PORT), Handler)
    print(f"campus-device-ops mock API listening on http://{HOST}:{PORT}/api/v1/health")
    server.serve_forever()


if __name__ == "__main__":
    main()
