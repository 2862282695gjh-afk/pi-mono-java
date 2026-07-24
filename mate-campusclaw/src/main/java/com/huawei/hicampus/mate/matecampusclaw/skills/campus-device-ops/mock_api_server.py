"""
Campus device ops mock API (port 18082).

Thin wrapper around scripts/api_routes.py — same contract as api_server.py.
For production / external integration use api_server.py (binds 0.0.0.0, CORS).
"""
from __future__ import annotations

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

_SKILL_DIR = Path(__file__).resolve().parent
_SCRIPTS = _SKILL_DIR / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from api_routes import parse_path, read_json_body, route_get, route_post  # noqa: E402

try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

HOST = "127.0.0.1"
PORT = int(os.environ.get("CAMPUS_OPS_MOCK_PORT", "18082"))


def _send_json(handler: BaseHTTPRequestHandler, status: int, obj: Any) -> None:
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def do_GET(self) -> None:
        try:
            path, query = parse_path(self.path)
            status, obj = route_get(path, query, port=PORT)
            _send_json(self, status, obj)
        except Exception as e:  # noqa: BLE001
            _send_json(self, 500, {"error": str(e)})

    def do_POST(self) -> None:
        try:
            path, _ = parse_path(self.path)
            length = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(length) if length > 0 else b"{}"
            body = read_json_body(raw)
            status, obj = route_post(path, body)
            _send_json(self, status, obj)
        except Exception as e:  # noqa: BLE001
            _send_json(self, 500, {"error": str(e)})


def main() -> None:
    server = HTTPServer((HOST, PORT), Handler)
    print(f"campus-device-ops mock API listening on http://{HOST}:{PORT}/api/v1/health")
    server.serve_forever()


if __name__ == "__main__":
    main()
