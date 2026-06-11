# Synced script pairs (device-inspection-re ↔ campus-device-ops)

These files share behavior contracts. When changing query/stats/work-order logic, **update both copies** in the same commit.

| device-inspection-re | campus-device-ops | Notes |
|----------------------|-------------------|-------|
| `scripts/close_work_order.py` | `scripts/close_work_order.py` | Identical CLI |
| `scripts/create_work_order.py` | `scripts/create_work_order.py` | WO id `WO-YYYYMMDD-NNN`, includes `runId` |
| `scripts/alarm_stats.py` | `scripts/alarm_stats.py` | Same stats fields + `byAssigneeNamed` |
| `scripts/query_alarms.py` | `scripts/query_alarms.py` | Filters: building, device-type, device-id, rule-id |
| `scripts/db_store.py` | `scripts/db_store.py` | **Read paths only** on campus side (no DDL / no `save_inspection_run`) |
| `scripts/_common.py` | `scripts/_common.py` | Shared: assignee/registry cache, `infer_alert_priority`, `enrich_alarm_row` |

Writer-only (do not duplicate into campus read path): `judge_rules_re.py`, `save_inspection_run`, schema migrations.

Campus-only: `push_alert_digest.py`, `build_qa_context.py`, `openclaw_env.py`, `run_inspection.py`.
