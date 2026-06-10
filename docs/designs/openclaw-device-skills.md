# OpenClaw device skills (excel / inspection / ops)

## Scope

Three OpenClaw skills under `.campusclaw/skills/` share one inspection database and one rules file:

| Skill | Role | Writes DB |
|-------|------|-----------|
| `excel-antlr-to-rules-json` | Compile `故障规则.xlsx` → `rules_re.json` | No |
| `device-inspection-re` | Run rules, persist inspection | **Yes** (`device_inspection_re.db`) |
| `campus-device-ops` | Query, push digest, Q&A, work orders | **No** (read-only) |

## Shared artifacts

```
rules/rules_re.json                          ← demo seed in repo (.campusclaw/rules/); replace via excel-antlr skill
device-inspection-re/mock_fixtures/
  device_inspection_re.db                    ← runtime (gitignored)
  devices/registry.json                    ← seed (in repo)
  devices/<deviceId>.json                  ← seed timeseries (in repo)
device-inspection-re/mock_api_server.py    ← :18081/fetch (in repo)
campus-device-ops/mock_fixtures/
  contacts/assignees.json                  ← seed (in repo)
  devices/assignee_map.json                ← ops overlay (in repo)
  work_orders/                             ← runtime (gitignored)
  notifications/outbox/                    ← runtime (gitignored)
```

## Contracts

- **Schema migrations** (`ALTER TABLE`, new columns): only `device-inspection-re` when writing runs.
- **campus-device-ops** must not run DDL on the shared SQLite file.
- **Work order IDs**: `WO-YYYYMMDD-NNN` (three-digit sequence per day), see `templates/work-order.schema.json`.
- **Close work order**: `campus-device-ops/scripts/close_work_order.py` (`--work-order-id`, `--list-open`, `--all`).

## Verification

```bash
# Full pipeline (includes judge / write DB)
python .campusclaw/skills/campus-device-ops/scripts/verify_openclaw_pipeline.py

# Ops-only smoke (existing DB)
python .campusclaw/skills/campus-device-ops/scripts/smoke_test.py --skip-inspection
```

ADR: [0006-shared-inspection-sqlite-db](../decisions/0006-shared-inspection-sqlite-db.html)
