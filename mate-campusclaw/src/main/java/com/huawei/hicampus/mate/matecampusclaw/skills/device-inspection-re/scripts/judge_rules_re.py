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
DEFAULT_MOCK_API_URL = "http://127.0.0.1:18081/fetch"
DEFAULT_FETCH_PORT = 18081

_PREV_RE = re.compile(r"\$?prev\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)")

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


def _mock_api_marker() -> str:
    return "mock_api_server.py"


def _process_cmdline(pid: int) -> str:
    if pid <= 0:
        return ""
    if sys.platform == "win32":
        try:
            return subprocess.check_output(
                ["wmic", "process", "where", f"ProcessId={pid}", "get", "CommandLine", "/VALUE"],
                text=True,
                errors="replace",
                timeout=5,
            )
        except Exception:
            pass
        try:
            ps_cmd = f"(Get-CimInstance Win32_Process -Filter \"ProcessId={pid}\").CommandLine"
            return subprocess.check_output(
                ["powershell", "-NoProfile", "-Command", ps_cmd],
                text=True,
                errors="replace",
                timeout=5,
            ).strip()
        except Exception:
            return ""
    try:
        return subprocess.check_output(
            ["ps", "-p", str(pid), "-o", "args="],
            text=True,
            errors="replace",
            timeout=5,
        ).strip()
    except Exception:
        return ""


def _pid_runs_mock_api(pid: int) -> bool:
    return _mock_api_marker() in _process_cmdline(pid)


def _listening_pids_on_port(port: int) -> List[int]:
    pids: List[int] = []
    if sys.platform == "win32":
        try:
            out = subprocess.check_output(["netstat", "-ano"], text=True, errors="replace")
        except Exception:
            return pids
        suffix = f":{port}"
        for line in out.splitlines():
            if suffix not in line or "LISTENING" not in line:
                continue
            token = line.split()[-1].strip()
            if token.isdigit():
                pids.append(int(token))
    else:
        try:
            out = subprocess.check_output(["lsof", "-ti", f"tcp:{port}"], text=True, errors="replace")
        except Exception:
            return pids
        for token in out.split():
            if token.strip().isdigit():
                pids.append(int(token.strip()))
    return sorted(set(pids))


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
    stopped: set[int] = set()
    if pid_path.is_file():
        try:
            raw = pid_path.read_text(encoding="utf-8").strip()
            if raw.isdigit():
                pid = int(raw)
                if _pid_runs_mock_api(pid):
                    _kill_pid(pid)
                    stopped.add(pid)
        except Exception:
            pass
        try:
            pid_path.unlink(missing_ok=True)
        except Exception:
            pass
    for pid in _listening_pids_on_port(DEFAULT_FETCH_PORT):
        if pid in stopped:
            continue
        if _pid_runs_mock_api(pid):
            _kill_pid(pid)


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
    except Exception:
        return
    deadline = time.time() + 3.0
    while time.time() < deadline:
        if _is_port_listening("127.0.0.1", DEFAULT_FETCH_PORT):
            pid_path = _mock_api_pid_path()
            pid_path.parent.mkdir(parents=True, exist_ok=True)
            pid_path.write_text(str(proc.pid), encoding="utf-8")
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


def _compile_rule_engine_text(text: str) -> rule_engine.Rule:
    normalized = _PREV_RE.sub(r"__prev_\1__", text.strip())
    return rule_engine.Rule(normalized)


def _facts_for_sample(point: Dict[str, Any], *, prev_point: Dict[str, Any] | None) -> Dict[str, Any]:
    facts = {k: v for k, v in point.items() if k != "ts"}
    if prev_point is not None:
        for k, v in prev_point.items():
            if k == "ts":
                continue
            facts[f"__prev_{k}__"] = v
    return facts


def judge_rule(rule: Dict[str, Any], series: List[Dict[str, Any]], *, compiled: rule_engine.Rule) -> JudgeResult:
    rid = str(rule.get("id", ""))
    name = str(rule.get("name", ""))
    effective = rule.get("effective") or {}
    metric = str(effective.get("metric", "ratio_true")).strip() or "ratio_true"
    norm = sorted(series, key=lambda p: float(p["ts"]))

    if metric == "last_point":
        if not norm:
            return JudgeResult(rid, name, False, 0.0, 0, 1.0, 1)
        last = norm[-1]
        prev = norm[-2] if len(norm) >= 2 else None
        facts = _facts_for_sample(last, prev_point=prev)
        try:
            matched = bool(compiled.matches(facts))
        except Exception:
            matched = False
        ratio = 1.0 if matched else 0.0
        return JudgeResult(rid, name, matched, ratio, 1, 1.0, 1)

    window_seconds = int(rule["window"]["durationSeconds"])
    threshold = float(effective["threshold"])
    min_samples = int(effective.get("minSamples", 1))

    now_ts = max(float(p["ts"]) for p in norm)
    start_ts = now_ts - window_seconds
    window_points = [p for p in norm if start_ts <= float(p["ts"]) <= now_ts]

    hits = 0
    total = 0
    for idx, p in enumerate(window_points):
        if _PREV_RE.search(str((rule.get("trigger") or {}).get("rule_engine", ""))) and idx == 0:
            continue
        prev_point = window_points[idx - 1] if idx > 0 else None
        facts = _facts_for_sample(p, prev_point=prev_point)
        total += 1
        try:
            ok = bool(compiled.matches(facts))
        except Exception:
            ok = False
        hits += 1 if ok else 0

    ratio = (hits / total) if total else 0.0
    matched = (total >= min_samples) and (ratio >= threshold)
    return JudgeResult(rid, name, matched, ratio, total, threshold, min_samples)


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


def device_types_in_rules(rules: List[Dict[str, Any]]) -> List[str]:
    found: set[str] = set()
    for rule in rules:
        if not isinstance(rule, dict):
            continue
        dt = str((rule.get("meta") or {}).get("deviceType", "")).strip()
        if dt:
            found.add(dt)
    return sorted(found)


def parse_device_type_filters(raw: Optional[List[str]]) -> Optional[List[str]]:
    if not raw:
        return None
    out: List[str] = []
    for item in raw:
        for part in str(item).split(","):
            token = part.strip()
            if token and token not in out:
                out.append(token)
    return out or None


def filter_rules_by_device_types(
    rules: List[Dict[str, Any]],
    device_types: Optional[List[str]],
) -> List[Dict[str, Any]]:
    if not device_types:
        return rules
    allowed = set(device_types)
    return [
        r
        for r in rules
        if isinstance(r, dict) and str((r.get("meta") or {}).get("deviceType", "")).strip() in allowed
    ]


def main(argv: Optional[List[str]] = None) -> int:
    os.environ.setdefault("DEVICE_INSPECTION_RE_FIXTURES_DIR", str(_default_fixtures_dir()))

    p = argparse.ArgumentParser(description="设备巡检（rules_re.json / rule-engine）")
    p.add_argument("--rules", default=None, help="rules_re.json 路径（默认自动搜索）")
    p.add_argument("--rules-extra", action="append", default=[], help="额外合并的 rules 文件（可多次）")
    p.add_argument("--end-ts", default=None, help="结束时间 Unix 秒或 ISO8601，默认当前 UTC")
    p.add_argument("--json", action="store_true", help="JSON 输出")
    p.add_argument("--no-save-db", action="store_true", help="不写入巡检数据库")
    p.add_argument(
        "--device-type",
        action="append",
        default=[],
        help="只巡检指定设备类型（可多次或逗号分隔，如 VAV 或 送排风）；默认全部",
    )
    p.add_argument(
        "--list-device-types",
        action="store_true",
        help="列出 rules 中的 deviceType 后退出",
    )
    p.add_argument(
        "--include-healthy",
        action="store_true",
        help="输出中包含正常设备的健康度评分",
    )
    args = p.parse_args(argv)

    from rules_re_paths import resolve_rules_re_path  # noqa: E402

    explicit_rules = Path(args.rules).expanduser().resolve() if args.rules else None
    primary = resolve_rules_re_path(explicit_rules)
    docs: List[Dict[str, Any]] = [_load_rules_doc(primary)]
    for extra in args.rules_extra or []:
        ep = Path(str(extra)).expanduser().resolve()
        if ep.is_file():
            docs.append(_load_rules_doc(ep))
    rules = _merge_rules_docs(*docs)
    if not rules:
        raise JudgeError("merged rules must be non-empty")

    if args.list_device_types:
        types = device_types_in_rules(rules)
        if args.json:
            print(json.dumps({"version": 1, "deviceTypes": types}, ensure_ascii=False))
        else:
            for dt in types:
                print(dt)
        return 0

    device_type_filter = parse_device_type_filters(args.device_type)
    all_rule_count = len(rules)
    if device_type_filter:
        rules = filter_rules_by_device_types(rules, device_type_filter)
        if not rules:
            known = ", ".join(device_types_in_rules(_merge_rules_docs(*docs)))
            want = ", ".join(device_type_filter)
            raise JudgeError(f"no rules for device-type filter [{want}]; known types: {known}")

    compiled_by_id: Dict[str, rule_engine.Rule] = {}
    for r in rules:
        trig = r.get("trigger") or {}
        text = str(trig.get("rule_engine", "")).strip()
        rid = str(r.get("id", "")).strip()
        if not rid or not text:
            raise JudgeError(f"rule {rid!r} missing trigger.rule_engine")
        try:
            compiled_by_id[rid] = _compile_rule_engine_text(text)
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
    total_alerts = sum(len(v) for v in alerts_by_device.values())

    from device_registry import load_device_registry  # noqa: E402
    from inspection_scope import build_inspection_scope_summary  # noqa: E402

    inspection_summary = build_inspection_scope_summary(
        registry_doc=load_device_registry(),
        fault_device_ids=fault_devices,
        device_type_filter=device_type_filter,
        total_alert_count=total_alerts,
    )

    results: Dict[str, Any] = {
        "fault_devices": fault_devices,
        "alerts_by_device": alerts_by_device,
        "end_ts": end_ts,
        "deviceTypeFilter": device_type_filter,
        "rulesJudged": len(rules),
        "rulesTotal": all_rule_count,
        "inspectionSummary": inspection_summary,
    }

    if args.include_healthy:
        results["healthy_devices"] = inspection_summary.get("healthyDevices") or []

    if not args.no_save_db:
        from db_store import db_path as inspection_db_path
        from db_store import save_inspection_run

        run_id = save_inspection_run(
            rules_path=str(primary),
            inspection=results,
            rules_by_id=rules_by_id,
            rules_kind="rules_re.json",
            scope_device_types=device_type_filter,
        )
        results["runId"] = run_id
        print(f"saved inspection runId={run_id} db={inspection_db_path()}", file=sys.stderr)

    if args.json:
        print(json.dumps(results, ensure_ascii=False))
    else:
        summary = inspection_summary
        scope_label = summary.get("scopeLabel", "全部设备")
        inspected = summary.get("inspectedDeviceCount", 0)
        fault_count = summary.get("faultDeviceCount", 0)
        healthy_count = summary.get("healthyDeviceCount", 0)
        print(
            f"巡检范围：{scope_label}；共 {inspected} 台，故障 {fault_count} 台，正常 {healthy_count} 台，"
            f"告警 {summary.get('totalAlertCount', 0)} 条"
        )
        if not fault_devices:
            print("OK：范围内无故障设备")
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
    from rules_re_paths import RulesNotFoundError, emit_rules_not_found  # noqa: E402

    try:
        raise SystemExit(main())
    except RulesNotFoundError as e:
        emit_rules_not_found(err=e, json_mode="--json" in sys.argv)
        raise SystemExit(1) from e
    except JudgeError as e:
        error_msg = str(e)
        # 提供更友好的中文错误提示
        if "no rules for device-type filter" in error_msg:
            print(f"❌ 未找到匹配的设备类型规则。{error_msg}", file=sys.stderr)
        elif "merged rules must be non-empty" in error_msg:
            print("❌ 规则文件为空，请检查 rules_re.json 是否存在且内容正确。", file=sys.stderr)
        elif "rule_engine syntax error" in error_msg:
            print(f"❌ 规则语法错误：{error_msg}", file=sys.stderr)
        elif "API response must be a JSON object" in error_msg:
            print("❌ API 响应格式错误，请检查巡检服务是否正常运行。", file=sys.stderr)
        elif "data must be a non-empty JSON array" in error_msg:
            print("❌ 返回数据为空，请检查设备是否在线。", file=sys.stderr)
        else:
            print(f"❌ 巡检失败：{error_msg}", file=sys.stderr)
        raise SystemExit(1) from e
    except FileNotFoundError as e:
        print(f"❌ 文件未找到：{e}", file=sys.stderr)
        print("提示：请确认 rules_re.json 文件路径是否正确。", file=sys.stderr)
        raise SystemExit(1) from e
    except ConnectionError as e:
        print(f"❌ 连接失败：{e}", file=sys.stderr)
        print("提示：请确认巡检 API 服务是否已启动（默认 http://127.0.0.1:18081）。", file=sys.stderr)
        raise SystemExit(1) from e
