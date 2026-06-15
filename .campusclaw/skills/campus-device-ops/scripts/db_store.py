"""
Read inspection results from device-inspection-re SQLite (shared DB).

campus-device-ops does not own a separate inspection database in the default setup.
query_alarms / compute_alarm_stats stay synced with device-inspection-re/scripts/db_store.py (read paths).
Production: replace with PostgreSQL using templates/schema.sql as reference.
"""
from __future__ import annotations

import json
import sqlite3
from collections import Counter
from typing import Any, Dict, List, Optional, Set

from _common import (
    assignees_by_id,
    default_inspection_db_path,
    enrich_inspection_with_registry,
    load_device_registry,
    registry_lookup,
)


def db_path():
    return default_inspection_db_path()


def _table_columns(conn: sqlite3.Connection, table: str) -> Set[str]:
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return {str(r[1]) for r in rows}


def _decode_scope_device_types(raw: Optional[str]) -> Optional[List[str]]:
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    parsed = json.loads(text)
    if not isinstance(parsed, list):
        return None
    out: List[str] = []
    for item in parsed:
        token = str(item).strip()
        if token and token not in out:
            out.append(token)
    return out or None


def _connect() -> sqlite3.Connection:
    path = db_path()
    if not path.is_file():
        raise FileNotFoundError(
            f"inspection database not found: {path}; "
            "run device-inspection-re/scripts/judge_rules_re.py first"
        )
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _assignee_for_device(device_id: str) -> tuple[str, str]:
    meta = registry_lookup(device_id)
    aid = str(meta.get("assigneeId", "")).strip()
    name = str(assignees_by_id().get(aid, {}).get("name", "")).strip()
    return aid, name


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
        cols = _table_columns(conn, "inspection_runs")
        kind_col = ", rules_kind" if "rules_kind" in cols else ""
        scope_col = ", scope_device_types" if "scope_device_types" in cols else ""
        rows = conn.execute(
            f"""
            SELECT run_id, rules_path{kind_col}, end_ts,
                   fault_device_count, total_alert_count, created_at{scope_col}
            FROM inspection_runs
            ORDER BY created_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    out: List[Dict[str, Any]] = []
    for r in rows:
        item = dict(r)
        if "rules_kind" not in item:
            item["rules_kind"] = "rules_re.json"
        if "scope_device_types" in item:
            item["deviceTypeFilter"] = _decode_scope_device_types(item.pop("scope_device_types"))
        out.append(item)
    return out


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

    with _connect() as conn:
        cols = _table_columns(conn, "inspection_alarms")
        optional = [
            "device_name",
            "building",
            "device_type",
            "component",
            "floor",
            "room",
        ]
        select_cols = [
            "device_id",
            "rule_id",
            "rule_name",
            "message",
            "reason_analysis",
            "expert_advice",
        ]
        for name in optional:
            if name in cols:
                select_cols.append(name)
        order_by = "building, device_id, rule_id" if "building" in cols else "device_id, rule_id"
        sql = f"""
            SELECT {', '.join(select_cols)}
            FROM inspection_alarms
            WHERE {' AND '.join(clauses)}
            ORDER BY {order_by}
        """
        rows = conn.execute(sql, params).fetchall()

    result: List[Dict[str, Any]] = []
    for r in rows:
        did = str(r["device_id"])
        meta = registry_lookup(did)
        aid, aname = _assignee_for_device(did)
        item = {
            "deviceId": did,
            "ruleId": r["rule_id"],
            "ruleName": r["rule_name"],
            "message": r["message"],
            "reasonAnalysis": r["reason_analysis"] or "—",
            "expertAdvice": r["expert_advice"] or "—",
            "building": (r["building"] if "building" in r.keys() else "") or meta.get("building", ""),
            "deviceName": (r["device_name"] if "device_name" in r.keys() else "") or meta.get("name", ""),
            "deviceType": (r["device_type"] if "device_type" in r.keys() else "") or meta.get("deviceType", ""),
            "component": (r["component"] if "component" in r.keys() else "") or meta.get("component", ""),
            "assigneeId": aid,
            "assigneeName": aname,
        }
        if "floor" in r.keys():
            item["floor"] = r["floor"] or meta.get("floor", "")
        elif meta.get("floor"):
            item["floor"] = meta.get("floor", "")
        if "room" in r.keys():
            item["room"] = r["room"] or meta.get("room", "")
        elif meta.get("room"):
            item["room"] = meta.get("room", "")
        result.append(item)
    if building:
        want = building.strip()
        result = [a for a in result if str(a.get("building", "")).strip() == want]
    if device_type:
        want = device_type.strip()
        result = [a for a in result if str(a.get("deviceType", "")).strip() == want]
    return result


def alarm_device_ids(*, run_id: Optional[str] = None) -> List[str]:
    rid = resolve_run_id(run_id)
    if not rid:
        return []
    with _connect() as conn:
        rows = conn.execute(
            "SELECT DISTINCT device_id FROM inspection_alarms WHERE run_id = ? ORDER BY device_id",
            (rid,),
        ).fetchall()
    return [str(r["device_id"]) for r in rows]


def load_inspection_doc(*, run_id: Optional[str] = None) -> Dict[str, Any]:
    rid = resolve_run_id(run_id)
    if not rid:
        raise FileNotFoundError(
            "no inspection in database; run device-inspection-re/scripts/judge_rules_re.py first"
        )

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
    enriched = enrich_inspection_with_registry(inspection)
    doc: Dict[str, Any] = {
        "version": 1,
        "runId": rid,
        "rulesPath": run["rules_path"],
        "inspection": inspection,
        "enriched": enriched,
        "createdAt": run["created_at"],
    }
    if "rules_kind" in run.keys():
        doc["rulesKind"] = run["rules_kind"]
    if "scope_device_types" in run.keys():
        doc["deviceTypeFilter"] = _decode_scope_device_types(run["scope_device_types"])
    return doc


def compute_alarm_stats_from_db(*, run_id: Optional[str] = None) -> Dict[str, Any]:
    from _common import enrich_alarm_row, infer_alert_priority

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

    for a in alarms:
        did = str(a.get("deviceId", ""))
        fault_devices_set.add(did)
        by_building[str(a.get("building") or "unknown")] += 1
        by_device_type[str(a.get("deviceType") or "unknown")] += 1
        by_rule[str(a.get("ruleId") or "")] += 1
        by_assignee[str(a.get("assigneeId") or "unassigned")] += 1
        by_level[infer_alert_priority(str(a.get("ruleName") or ""))] += 1

    registry_total = len(load_device_registry().get("devices") or [])

    from inspection_scope import build_inspection_scope_summary  # noqa: E402

    scope_summary = build_inspection_scope_summary(
        registry_doc=load_device_registry(),
        fault_device_ids=sorted(fault_devices_set),
        device_type_filter=doc.get("deviceTypeFilter"),
        total_alert_count=len(alarms),
    )

    return {
        "version": 1,
        "runId": rid,
        "endTs": inspection.get("end_ts"),
        "deviceTypeFilter": doc.get("deviceTypeFilter"),
        "scopeLabel": scope_summary.get("scopeLabel"),
        "inspectedDeviceCount": scope_summary.get("inspectedDeviceCount"),
        "faultDeviceCount": scope_summary.get("faultDeviceCount"),
        "healthyDeviceCount": scope_summary.get("healthyDeviceCount"),
        "healthScore": scope_summary.get("healthScore"),
        "healthPercentage": scope_summary.get("healthPercentage"),
        "registryDeviceCount": scope_summary.get("inspectedDeviceCount"),
        "alarmDeviceCount": scope_summary.get("faultDeviceCount"),
        "totalAlertCount": len(alarms),
        "campusRegistryDeviceCount": registry_total,
        "byBuilding": dict(by_building),
        "byDeviceType": dict(by_device_type),
        "byRule": dict(by_rule),
        "byAssignee": dict(by_assignee),
        "byLevel": dict(by_level),
        "faultDevices": scope_summary.get("faultDevices"),
        "healthyDevices": scope_summary.get("healthyDevices"),
        "inspectionSummary": scope_summary,
    }


def active_alarms_payload(*, run_id: Optional[str] = None) -> Dict[str, Any]:
    doc = load_inspection_doc(run_id=run_id)
    inspection = doc.get("inspection") or {}
    return {
        "version": 1,
        "runId": doc.get("runId"),
        "endTs": inspection.get("end_ts"),
        "faultDevices": inspection.get("fault_devices") or [],
        "alertsByDevice": inspection.get("alerts_by_device") or {},
    }
