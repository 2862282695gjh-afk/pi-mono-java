# Deprecated (campus-device-ops)

These components belonged to the old **standalone inspection** flow. Default production path:

1. **Write DB**: `device-inspection-re/scripts/judge_rules_re.py` → `device_inspection_re.db`
2. **Read DB**: `campus-device-ops/scripts/query_alarms.py` and siblings

| File | Status |
|------|--------|
| `judge_rules.py` | Removed (was duplicate); use `device-inspection-re/scripts/judge_rules_re.py` |
| `fetch_api_server.py` | Deprecated — inspection uses device-inspection-re `:18081` |
| `mock_fixtures/timeseries/` | Deprecated — fixtures live under device-inspection-re |
| `mock_fixtures/campus_ops.db` | Deprecated — use shared `device_inspection_re.db` |

Optional: `run_inspection.py` delegates to device-inspection-re when user explicitly asks to re-run inspection from ops skill.
