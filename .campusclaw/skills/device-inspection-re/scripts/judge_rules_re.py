from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

import rule_engine

try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass


class JudgeError(Exception):
    pass


_SCRIPT_FILE = Path(__file__).resolve()
_SKILL_ROOT = _SCRIPT_FILE.parents[1]
_CAMPUSCLAW_ROOT = _SCRIPT_FILE.parents[3]
DEFAULT_BUNDLED_RULES_PATH = _CAMPUSCLAW_ROOT / "rules" / "rules_re.json"
FALLBACK_RULES_PATH = (
    _CAMPUSCLAW_ROOT / "rule-engines-pack" / "02-rule-engine-pypi" / "rules" / "rules_re.json"
)
DEFAULT_MOCK_API_URL = "http://127.0.0.1:18081/fetch"
DEFAULT_FETCH_PORT = 18081

_RE_KEYWORDS: Set[str] = {
    "and",
    "or",
    "not",
    "true",
    "false",
    "null",
    "in",
    "matches",
    "contains",
    "startswith",
    "endswith",
}


def _default_rules_path() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_RULES_PATH", "").strip()
    if env:
        p = Path(env).expanduser().resolve()
        if p.is_file():
            return p
    if DEFAULT_BUNDLED_RULES_PATH.is_file():
        return DEFAULT_BUNDLED_RULES_PATH
    openclaw_rules = Path.home() / ".openclaw" / "workspace" / "rules" / "rules_re.json"
    if openclaw_rules.is_file():
        return openclaw_rules
    return FALLBACK_RULES_PATH

def _default_fixtures_dir() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_FIXTURES_DIR", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return _SKILL_ROOT / "mock_fixtures"


def _parse_ts_to_epoch_seconds(ts: Any) -> float:
    if isinstance(ts, (int, float)):
        return float(ts)
    if isinstance(ts, str):
        s = ts.strip()
        try:
            return float(s)
        except Exception:
            pass
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    raise JudgeError(f"Invalid ts type: {type(ts)}")


def _load_rules_doc(path: Path) -> Dict[str, Any]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict):
        raise JudgeError(f"rules_re.json must be an object: {path}")
    if doc.get("version") != 1:
        raise JudgeError(f"version must be 1: {path}")
    rules = doc.get("rules")
    if not isinstance(rules, list):
        raise JudgeError(f"rules must be an array: {path}")
    return doc


def _merge_rules_docs(*docs: Dict[str, Any]) -> List[Dict[str, Any]]:
    by_id: Dict[str, Dict[str, Any]] = {}
    ordered: List[str] = []
    for doc in docs:
        for r in doc.get("rules", []) or []:
            if not isinstance(r, dict):
                continue
            rid = str(r.get("id", "")).strip()
            if not rid:
                continue
            if rid not in by_id:
                ordered.append(rid)
            by_id[rid] = r
    return [by_id[i] for i in ordered if i in by_id]


def _symbol_names_in_expression(text: str) -> Set[str]:
    out: Set[str] = set()
    for m in re.finditer(r"(?<!\$)\b([A-Za-z_][A-Za-z0-9_]*)\b", text):
        name = m.group(1)
        if name.lower() in _RE_KEYWORDS:
            continue
        out.add(name)
    return out


def _is_port_listening(host: str, port: int, *, timeout_s: float = 0.5) -> bool:
    sock = socket.socket()
    try:
        sock.settimeout(timeout_s)
        return sock.connect_ex((host, port)) == 0
    finally:
        try:
            sock.close()
        except Exception:
            pass


def _mock_api_pid_path() -> Path:
    return _default_fixtures_dir() / "state" / "mock_api_server.pid"


def _kill_pid(pid: int) -> None:
    if pid <= 0:
        return
    if sys.platform == "win32":
        subprocess.run(
            ["taskkill", "/F", "/PID", str(pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        subprocess.run(
            ["kill", "-TERM", str(pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )


def _stop_mock_api_server() -> None:
    pid_path = _mock_api_pid_path()
    if pid_path.is_file():
        try:
            raw = pid_path.read_text(encoding="utf-8").strip()
            if raw.isdigit():
                _kill_pid(int(raw))
        except Exception:
            pass
        try:
            pid_path.unlink(missing_ok=True)
        except Exception:
            pass
    if sys.platform == "win32" and _is_port_listening("127.0.0.1", DEFAULT_FETCH_PORT):
        try:
            out = subprocess.check_output(["netstat", "-ano"], text=True, errors="replace")
        except Exception:
            return
        suffix = f":{DEFAULT_FETCH_PORT}"
        for line in out.splitlines():
            if suffix not in line or "LISTENING" not in line:
                continue
            pid = line.split()[-1].strip()
            if pid.isdigit():
                _kill_pid(int(pid))


def _try_start_mock_api_server() -> None:
    if os.environ.get("DEVICE_INSPECTION_RE_MOCK_RELOAD", "1").strip().lower() not in ("0", "false", "no"):
        _stop_mock_api_server()
    elif _is_port_listening("127.0.0.1", DEFAULT_FETCH_PORT):
        return
    script = _SKILL_ROOT / "mock_api_server.py"
    if not script.is_file():
        return
    try:
        proc = subprocess.Popen(
            [sys.executable, str(script)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        )
        pid_path = _mock_api_pid_path()
        pid_path.parent.mkdir(parents=True, exist_ok=True)
        pid_path.write_text(str(proc.pid), encoding="utf-8")
    except Exception:
        return
    deadline = time.time() + 3.0
    while time.time() < deadline:
        if _is_port_listening("127.0.0.1", DEFAULT_FETCH_PORT):
            return
        time.sleep(0.1)


def _call_mock_api(*, queries: List[Dict[str, Any]], end_ts: float) -> Dict[str, Any]:
    url = os.environ.get("DEVICE_INSPECTION_RE_API_URL", DEFAULT_MOCK_API_URL).strip() or DEFAULT_MOCK_API_URL
    body = json.dumps({"endTs": end_ts, "queries": queries}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url=url, method="POST", data=body, headers={"Content-Type": "application/json; charset=utf-8"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
    except Exception:
        _try_start_mock_api_server()
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
    obj = json.loads(raw.decode("utf-8"))
    if not isinstance(obj, dict):
        raise JudgeError("API response must be a JSON object")
    return obj


def _normalize_data_points(data: Any) -> List[Dict[str, Any]]:
    if not isinstance(data, list) or not data:
        raise JudgeError("data must be a non-empty JSON array")
    out: List[Dict[str, Any]] = []
    for i, item in enumerate(data):
        if not isinstance(item, dict):
            raise JudgeError(f"data[{i}] must be an object")
        if "ts" not in item:
            raise JudgeError(f"data[{i}] missing ts")
        if "points" in item and isinstance(item.get("points"), dict):
            facts = dict(item["points"])
        else:
            facts = {k: v for k, v in item.items() if k not in {"ts", "device_type", "deviceType", "component"}}
        out.append({"ts": _parse_ts_to_epoch_seconds(item["ts"]), **facts})
    out.sort(key=lambda p: float(p["ts"]))
    return out


@dataclass(frozen=True)
class JudgeResult:
    rule_id: str
    rule_name: str
    matched: bool
    ratio_true: float
    samples: int
    threshold: float
    min_samples: int


def judge_rule(rule: Dict[str, Any], series: List[Dict[str, Any]], *, compiled: rule_engine.Rule) -> JudgeResult:
    window_seconds = int(rule["window"]["durationSeconds"])
    threshold = float(rule["effective"]["threshold"])
    min_samples = int(rule["effective"].get("minSamples", 1))

    now_ts = max(float(p["ts"]) for p in series)
    start_ts = now_ts - window_seconds
    window_points = [p for p in series if start_ts <= float(p["ts"]) <= now_ts]

    hits = 0
    total = 0
    for p in window_points:
        facts = {k: v for k, v in p.items() if k != "ts"}
        total += 1
        try:
            ok = bool(compiled.matches(facts))
        except Exception:
            ok = False
        hits += 1 if ok else 0

    ratio = (hits / total) if total else 0.0
    matched = (total >= min_samples) and (ratio >= threshold)
    return JudgeResult(
        rule_id=str(rule.get("id", "")),
        rule_name=str(rule.get("name", "")),
        matched=matched,
        ratio_true=float(ratio),
        samples=int(total),
        threshold=threshold,
        min_samples=min_samples,
    )


@dataclass(frozen=True)
class Alert:
    device_id: str
    rule_id: str
    rule_name: str
    message: str
    reason_analysis: str
    expert_advice: str


def build_alert(rule: Dict[str, Any], jr: JudgeResult, *, device_id: str = "") -> Alert:
    rid = str(rule.get("id", "")).strip()
    name = str(rule.get("name", "")).strip()
    msg = f"告警：{name}（rule_id={rid}）"
    ra = str(rule.get("原因分析", "") or "").strip()
    ea = str(rule.get("专家处理建议", "") or "").strip()
    return Alert(device_id=str(device_id or "").strip(), rule_id=rid, rule_name=name, message=msg, reason_analysis=ra, expert_advice=ea)


def main(argv: Optional[List[str]] = None) -> int:
    os.environ.setdefault("DEVICE_INSPECTION_RE_FIXTURES_DIR", str(_default_fixtures_dir()))

    p = argparse.ArgumentParser(description="设备巡检（rules_re.json / rule-engine）")
    p.add_argument("--rules", default=str(_default_rules_path()), help="rules_re.json 路径")
    p.add_argument("--rules-extra", action="append", default=[], help="额外合并的 rules 文件（可多次）")
    p.add_argument("--end-ts", default=None, help="结束时间 Unix 秒或 ISO8601，默认当前 UTC")
    p.add_argument("--json", action="store_true", help="JSON 输出")
    p.add_argument("--no-save-db", action="store_true", help="不写入巡检数据库")
    args = p.parse_args(argv)

    primary = Path(args.rules).expanduser().resolve()
    docs: List[Dict[str, Any]] = [_load_rules_doc(primary)]
    for extra in args.rules_extra or []:
        ep = Path(str(extra)).expanduser().resolve()
        if ep.is_file():
            docs.append(_load_rules_doc(ep))
    rules = _merge_rules_docs(*docs)
    if not rules:
        raise JudgeError("merged rules must be non-empty")

    compiled_by_id: Dict[str, rule_engine.Rule] = {}
    for r in rules:
        trig = r.get("trigger") or {}
        text = str(trig.get("rule_engine", "")).strip()
        rid = str(r.get("id", "")).strip()
        if not rid or not text:
            raise JudgeError(f"rule {rid!r} missing trigger.rule_engine")
        try:
            compiled_by_id[rid] = rule_engine.Rule(text)
        except Exception as e:
            raise JudgeError(f"rule {rid} rule_engine syntax error: {e}") from e

    end_ts = _parse_ts_to_epoch_seconds(args.end_ts) if args.end_ts else datetime.now(tz=timezone.utc).timestamp()

    queries: List[Dict[str, Any]] = []
    for r in rules:
        meta = r.get("meta") or {}
        window = r.get("window") or {}
        trig = r.get("trigger") or {}
        text = str(trig.get("rule_engine", "")).strip()
        device_type = str(meta.get("deviceType", "")).strip()
        component = str(meta.get("component", "")).strip()
        points = [str(x).strip() for x in (meta.get("points") or []) if str(x).strip()]
        extra = sorted(_symbol_names_in_expression(text))
        points = sorted(set(points) | set(extra))
        window_seconds = int(window.get("durationSeconds", 0) or 0)
        if not device_type or not component or not points or window_seconds <= 0:
            continue
        queries.append(
            {
                "requestId": str(r.get("id", "")).strip(),
                "ruleName": str(r.get("name", "")).strip(),
                "deviceType": device_type,
                "component": component,
                "points": points,
                "windowSeconds": window_seconds,
            }
        )

    if os.environ.get("DEVICE_INSPECTION_RE_MOCK_RELOAD", "1").strip().lower() not in ("0", "false", "no"):
        _try_start_mock_api_server()

    api_resp = _call_mock_api(queries=queries, end_ts=end_ts)
    items = api_resp.get("items", [])
    if not isinstance(items, list):
        raise JudgeError("items must be an array")

    rules_by_id = {str(r.get("id", "")).strip(): r for r in rules if isinstance(r, dict)}
    alerts: List[Dict[str, Any]] = []
    for it in items:
        if not isinstance(it, dict):
            continue
        rule_id = str(it.get("requestId", "")).strip()
        device_id = str(it.get("deviceId", "")).strip()
        if not rule_id or rule_id not in rules_by_id:
            continue
        rule = rules_by_id[rule_id]
        norm = _normalize_data_points(it.get("data", []))
        jr = judge_rule(rule, norm, compiled=compiled_by_id[rule_id])
        if jr.matched:
            from device_registry import enrich_alert_dict  # noqa: E402

            alerts.append(enrich_alert_dict(asdict(build_alert(rule, jr, device_id=device_id)), rule))

    alerts_by_device: Dict[str, List[Dict[str, Any]]] = {}
    for a in alerts:
        did = str(a.get("device_id", "")).strip() or "unknown"
        alerts_by_device.setdefault(did, []).append(a)
    fault_devices = sorted([d for d, arr in alerts_by_device.items() if arr and d != "unknown"])

    results: Dict[str, Any] = {
        "fault_devices": fault_devices,
        "alerts_by_device": alerts_by_device,
        "end_ts": end_ts,
    }

    if not args.no_save_db:
        from db_store import db_path as inspection_db_path
        from db_store import save_inspection_run

        run_id = save_inspection_run(
            rules_path=str(primary),
            inspection=results,
            rules_by_id=rules_by_id,
            rules_kind="rules_re.json",
        )
        results["runId"] = run_id
        print(f"saved inspection runId={run_id} db={inspection_db_path()}", file=sys.stderr)

    if args.json:
        print(json.dumps(results, ensure_ascii=False))
    else:
        if not fault_devices:
            print("OK：无告警")
        else:
            print("设备\t故障\t原因分析\t专家处理建议")

            def _cell(x: Any) -> str:
                s = str(x or "").strip()
                if not s:
                    return "—"
                return s.replace("\t", " ").replace("\r\n", " ").replace("\n", " ").replace("\r", " ")

            for d in fault_devices:
                for a in alerts_by_device.get(d, []):
                    print(
                        f"{_cell(a.get('device_id', d))}\t{_cell(a.get('rule_name', ''))}\t"
                        f"{_cell(a.get('reason_analysis', ''))}\t{_cell(a.get('expert_advice', ''))}"
                    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except JudgeError as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from e
