"""
SQLite persistence for device-inspection-re (rules_re.json).

query_alarms / compute_alarm_stats stay synced with campus-device-ops/scripts/db_store.py (read paths).
Production: replace with PostgreSQL; see templates/schema.sql.
"""
from __future__ import annotations

import os
import sqlite3
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def db_path() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_DB_PATH", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    return skill_root() / "mock_fixtures" / "device_inspection_re.db"


def _connect() -> sqlite3.Connection:
    path = db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    init_schema(conn)
    return conn


def init_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS inspection_runs (
            run_id TEXT PRIMARY KEY,
            rules_path TEXT NOT NULL,
            rules_kind TEXT NOT NULL DEFAULT 'rules_re.json',
            end_ts REAL NOT NULL,
            fault_device_count INTEGER NOT NULL,
            total_alert_count INTEGER NOT NULL,
            created_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS inspection_alarms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            run_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            rule_id TEXT NOT NULL,
            rule_name TEXT NOT NULL,
            message TEXT,
            reason_analysis TEXT,
            expert_advice TEXT,
            device_type TEXT,
            component TEXT,
            FOREIGN KEY (run_id) REFERENCES inspection_runs(run_id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_di_re_alarms_run ON inspection_alarms(run_id);
        CREATE INDEX IF NOT EXISTS idx_di_re_alarms_device ON inspection_alarms(device_id);
        CREATE INDEX IF NOT EXISTS idx_di_re_alarms_rule ON inspection_alarms(rule_id);
        """
    )
    _ensure_alarm_columns(conn)
    conn.commit()


def _ensure_alarm_columns(conn: sqlite3.Connection) -> None:
    existing = {str(r[1]) for r in conn.execute("PRAGMA table_info(inspection_alarms)").fetchall()}
    for col, ddl in (
        ("device_name", "ALTER TABLE inspection_alarms ADD COLUMN device_name TEXT"),
        ("building", "ALTER TABLE inspection_alarms ADD COLUMN building TEXT"),
        ("floor", "ALTER TABLE inspection_alarms ADD COLUMN floor TEXT"),
        ("room", "ALTER TABLE inspection_alarms ADD COLUMN room TEXT"),
    ):
        if col not in existing:
            conn.execute(ddl)


def _new_run_id() -> str:
    now = datetime.now(tz=timezone.utc)
    return f"insp_{now.strftime('%Y%m%d_%H%M%S')}"


def _meta_for_rule(rules_by_id: Dict[str, Dict[str, Any]], rule_id: str) -> tuple[str, str]:
    rule = rules_by_id.get(rule_id) or {}
    meta = rule.get("meta") or {}
    return str(meta.get("deviceType", "") or ""), str(meta.get("component", "") or "")


def save_inspection_run(
    *,
    rules_path: str,
    inspection: Dict[str, Any],
    rules_by_id: Dict[str, Dict[str, Any]],
    rules_kind: str = "rules_re.json",
) -> str:
    end_ts = float(inspection.get("end_ts") or 0.0)
    alerts_by_device = dict(inspection.get("alerts_by_device") or {})
    fault_devices = list(inspection.get("fault_devices") or [])
    total_alerts = sum(len(v) for v in alerts_by_device.values())

    run_id = _new_run_id()
    created_at = datetime.now(tz=timezone.utc).isoformat()

    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO inspection_runs
            (run_id, rules_path, rules_kind, end_ts, fault_device_count, total_alert_count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (run_id, rules_path, rules_kind, end_ts, len(fault_devices), total_alerts, created_at),
        )
        for did, alerts in alerts_by_device.items():
            for alert in alerts or []:
                if not isinstance(alert, dict):
                    continue
                rid = str(alert.get("rule_id", "")).strip()
                if not str(alert.get("device_type", "")).strip():
                    device_type, component = _meta_for_rule(rules_by_id, rid)
                else:
                    device_type = str(alert.get("device_type", "")).strip()
                    component = str(alert.get("component", "")).strip()
                conn.execute(
                    """
                    INSERT INTO inspection_alarms (
                        run_id, device_id, rule_id, rule_name, message,
                        reason_analysis, expert_advice, device_type, component,
                        device_name, building, floor, room
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        run_id,
                        str(alert.get("device_id", did)),
                        rid,
                        str(alert.get("rule_name", "")),
                        str(alert.get("message", "")),
                        str(alert.get("reason_analysis", "") or ""),
                        str(alert.get("expert_advice", "") or ""),
                        device_type,
                        component,
                        str(alert.get("device_name", "") or ""),
                        str(alert.get("building", "") or ""),
                        str(alert.get("floor", "") or ""),
                        str(alert.get("room", "") or ""),
                    ),
                )
        conn.commit()
    return run_id


def resolve_run_id(run_id: Optional[str]) -> Optional[str]:
    if run_id and run_id.lower() not in ("latest", "last", ""):
        return run_id
    return get_latest_run_id()


def get_latest_run_id() -> Optional[str]:
    with _connect() as conn:
        row = conn.execute(
            "SELECT run_id FROM inspection_runs ORDER BY created_at DESC LIMIT 1"
        ).fetchone()
    return str(row["run_id"]) if row else None


def list_inspection_runs(*, limit: int = 20) -> List[Dict[str, Any]]:
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT run_id, rules_path, rules_kind, end_ts,
                   fault_device_count, total_alert_count, created_at
            FROM inspection_runs
            ORDER BY created_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return [dict(r) for r in rows]


def query_alarms(
    *,
    run_id: Optional[str] = None,
    building: Optional[str] = None,
    device_id: Optional[str] = None,
    rule_id: Optional[str] = None,
    device_type: Optional[str] = None,
) -> List[Dict[str, Any]]:
    rid = resolve_run_id(run_id)
    if not rid:
        return []

    clauses = ["run_id = ?"]
    params: List[Any] = [rid]
    if device_id:
        clauses.append("device_id = ?")
        params.append(device_id.strip())
    if rule_id:
        clauses.append("rule_id = ?")
        params.append(rule_id.strip())
    if device_type:
        clauses.append("device_type = ?")
        params.append(device_type.strip())

    sql = f"""
        SELECT device_id, device_name, building, floor, room,
               rule_id, rule_name, message, reason_analysis, expert_advice,
               device_type, component
        FROM inspection_alarms
        WHERE {' AND '.join(clauses)}
        ORDER BY building, device_id, rule_id
    """
    with _connect() as conn:
        rows = conn.execute(sql, params).fetchall()
    result = [
        {
            "deviceId": r["device_id"],
            "deviceName": r["device_name"] or "",
            "building": r["building"] or "",
            "floor": r["floor"] or "",
            "room": r["room"] or "",
            "ruleId": r["rule_id"],
            "ruleName": r["rule_name"],
            "message": r["message"],
            "reasonAnalysis": r["reason_analysis"] or "—",
            "expertAdvice": r["expert_advice"] or "—",
            "deviceType": r["device_type"],
            "component": r["component"],
        }
        for r in rows
    ]
    if building:
        want = building.strip()
        result = [a for a in result if str(a.get("building", "")).strip() == want]
    return result


def load_inspection_doc(*, run_id: Optional[str] = None) -> Dict[str, Any]:
    rid = resolve_run_id(run_id)
    if not rid:
        raise FileNotFoundError("no inspection in database; run judge_rules_re.py first")

    with _connect() as conn:
        run = conn.execute("SELECT * FROM inspection_runs WHERE run_id = ?", (rid,)).fetchone()
        if not run:
            raise FileNotFoundError(f"inspection run not found: {rid}")
        rows = conn.execute(
            "SELECT * FROM inspection_alarms WHERE run_id = ? ORDER BY device_id, rule_id",
            (rid,),
        ).fetchall()

    alerts_by_device: Dict[str, List[Dict[str, Any]]] = {}
    for r in rows:
        did = str(r["device_id"])
        alerts_by_device.setdefault(did, []).append(
            {
                "device_id": did,
                "rule_id": r["rule_id"],
                "rule_name": r["rule_name"],
                "message": r["message"],
                "reason_analysis": r["reason_analysis"],
                "expert_advice": r["expert_advice"],
            }
        )
    inspection = {
        "fault_devices": sorted(alerts_by_device.keys()),
        "alerts_by_device": alerts_by_device,
        "end_ts": run["end_ts"],
    }
    return {
        "version": 1,
        "runId": rid,
        "rulesPath": run["rules_path"],
        "rulesKind": run["rules_kind"],
        "inspection": inspection,
        "createdAt": run["created_at"],
    }


def compute_alarm_stats_from_db(*, run_id: Optional[str] = None) -> Dict[str, Any]:
    from _common import enrich_alarm_row, infer_alert_priority, registry_by_device_id
    from device_registry import load_device_registry

    doc = load_inspection_doc(run_id=run_id)
    inspection = doc.get("inspection") or {}
    rid = doc.get("runId")
    alarms = [enrich_alarm_row(a) for a in query_alarms(run_id=rid)]

    by_building: Counter[str] = Counter()
    by_device_type: Counter[str] = Counter()
    by_rule: Counter[str] = Counter()
    by_assignee: Counter[str] = Counter()
    by_level: Counter[str] = Counter()
    fault_devices_set: set[str] = set()

    for alarm in alarms:
        did = str(alarm.get("deviceId", ""))
        fault_devices_set.add(did)
        by_building[str(alarm.get("building") or "unknown")] += 1
        by_device_type[str(alarm.get("deviceType") or "unknown")] += 1
        by_rule[str(alarm.get("ruleId") or "")] += 1
        by_assignee[str(alarm.get("assigneeId") or "unassigned")] += 1
        by_level[infer_alert_priority(str(alarm.get("ruleName") or ""))] += 1

    registry_total = len(load_device_registry().get("devices") or [])

    return {
        "version": 1,
        "runId": rid,
        "endTs": inspection.get("end_ts"),
        "registryDeviceCount": registry_total,
        "alarmDeviceCount": len(fault_devices_set),
        "totalAlertCount": len(alarms),
        "byBuilding": dict(by_building),
        "byDeviceType": dict(by_device_type),
        "byRule": dict(by_rule),
        "byAssignee": dict(by_assignee),
        "byLevel": dict(by_level),
        "faultDevices": sorted(fault_devices_set),
    }


def build_device_alarm_summary(*, run_id: Optional[str] = None) -> List[Dict[str, Any]]:
    from _common import enrich_alarm_row

    rid = resolve_run_id(run_id)
    if not rid:
        return []
    grouped: Dict[str, Dict[str, Any]] = {}
    for alarm in [enrich_alarm_row(a) for a in query_alarms(run_id=rid)]:
        did = str(alarm.get("deviceId", ""))
        bucket = grouped.setdefault(
            did,
            {
                "deviceId": did,
                "deviceName": alarm.get("deviceName", ""),
                "building": alarm.get("building", ""),
                "deviceType": alarm.get("deviceType", ""),
                "component": alarm.get("component", ""),
                "assigneeId": alarm.get("assigneeId", ""),
                "assigneeName": alarm.get("assigneeName", ""),
                "alertCount": 0,
                "ruleNames": [],
            },
        )
        bucket["alertCount"] += 1
        rule_name = str(alarm.get("ruleName", "")).strip()
        if rule_name and rule_name not in bucket["ruleNames"]:
            bucket["ruleNames"].append(rule_name)
    return sorted(grouped.values(), key=lambda row: (str(row.get("building", "")), str(row.get("deviceId", ""))))
