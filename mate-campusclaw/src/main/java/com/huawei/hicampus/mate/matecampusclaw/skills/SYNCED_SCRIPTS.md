# Synced script pairs (device-inspection-re ↔ campus-device-ops)

These files share behavior contracts. When changing query/stats/work-order logic, **update both copies** in the same commit.

| device-inspection-re | campus-device-ops | Notes |
|----------------------|-------------------|-------|
| `scripts/close_work_order.py` | `scripts/close_work_order.py` | Identical CLI |
| `scripts/create_work_order.py` | `scripts/create_work_order.py` | WO id `WO-YYYYMMDD-NNN`, includes `runId` |
| `scripts/alarm_stats.py` | `scripts/alarm_stats.py` | Same stats fields + `byAssigneeNamed` |
| `scripts/query_alarms.py` | `scripts/query_alarms.py` | Filters: building, device-type, device-id, rule-id |
| `scripts/db_store.py` | `scripts/db_store.py` | **Read paths only** on campus side (no DDL / no `save_inspection_run`) |
| `scripts/db_backend.py` | `scripts/db_backend.py` | SQLite + MySQL connection layer |
| `scripts/_common.py` | `scripts/_common.py` | Shared: assignee/registry cache, `infer_alert_priority`, `enrich_alarm_row` |
| `scripts/rules_re_paths.py` | `scripts/rules_re_paths.py` | `rules_re.json` 路径解析；缺失时 `rules_not_found` + `stop_skill` |
| `scripts/inspection_scope.py` | `scripts/inspection_scope.py` | 按 `deviceTypeFilter` 计算此次巡检总台数/故障/正常 |

Writer-only (do not duplicate into campus read path): `judge_rules_re.py`, `save_inspection_run`, schema migrations.

Campus-only: `push_alert_digest.py`, `build_qa_context.py`, `openclaw_env.py`, `run_inspection.py`.
