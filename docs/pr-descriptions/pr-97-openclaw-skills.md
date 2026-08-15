# PR title (replace current title)

```
feat(skills): OpenClaw 三 skill 协作 — 分类型巡检、范围台数统计、规则缺失终止
```

# PR body (paste into GitHub description)

## Summary

引入 OpenClaw 三 skill 协作链路（Excel 编译规则 → 巡检写库 → 园区运维只读）：

| Skill | 能力 |
|-------|------|
| `excel-antlr-to-rules-json` | Excel → `rules_re.json`（ANTLR + rule-engine；`last_point` / `prev()` 对齐） |
| `device-inspection-re` | 判规则、写 `device_inspection_re.db`、报告/统计/工单 |
| `campus-device-ops` | 读共享库：告警查询、digest 推送、问答上下文、工单关单 |

### 本批增量（OpenClaw 实测后回同步）

| 能力 | 说明 |
|------|------|
| **分设备类型巡检** | `judge_rules_re.py` / `run_inspection.py` 支持 `--device-type VAV`（可重复/逗号分隔）；run 入库带 `deviceTypeFilter` |
| **范围台数统计** | `inspectionSummary.inspectedDeviceCount` = **此次巡检范围**总台数（VAV 巡 3 台、全量巡 registry 全部）；含故障/正常/healthScore |
| **规则文件缺失** | `rules_re_paths.py` → `rules_not_found` + `action: stop_skill`；Agent 须提示用户并终止 skill |
| **设备 ID 规范** | registry 统一 ASCII canonical ID（`VAV_001`、`EF_001` 等）；扩充 12 台 demo 设备 |
| **MySQL 建表参考** | `device-inspection-re/templates/schema.mysql.sql` |
| **报告/统计 UX** | `format_inspection_report` 健康度与范围摘要；`alarm_stats` 中文 CLI |

**Review 整改要点（沿用）：**

- 删除 campus 侧重复 `judge_rules.py`；巡检统一走 `judge_rules_re.py`
- 工单 ID 对齐 `WO-YYYYMMDD-NNN`；`push_alert_digest --write-ai-message` 修复
- mock 停服：PID 文件 + 仅终止 `mock_api_server.py`（跨平台）
- 读方去掉 SQLite DDL；双 skill 脚本对齐见 `SYNCED_SCRIPTS.md`（含 `rules_re_paths.py`、`inspection_scope.py`）

**入库 vs 排除（`.gitignore`）：**

| 入库（seed / 可 fresh-clone 运行） | 排除（运行时产物） |
|-----------------------------------|-------------------|
| `mock_api_server.py`（:18081）、`devices/*.json`、`registry.json`、`assignees.json`、`timeseries/*.json` | `*.db`、`work_orders/`、`notifications/outbox/`、`state/` |
| `.campusclaw/rules/rules_re.json`（demo seed） | |

**文档：** [openclaw-device-skills.md](../designs/openclaw-device-skills.md) · [ADR-0006](../decisions/0006-shared-inspection-sqlite-db.html)

## 关键命令

```bash
# 全量巡检
python skills/device-inspection-re/scripts/judge_rules_re.py --json

# 仅 VAV（3 台范围统计）
python skills/device-inspection-re/scripts/judge_rules_re.py --device-type VAV --json

# 展示报告（含 inspectedDeviceCount / faultDeviceCount）
python skills/device-inspection-re/scripts/format_inspection_report.py --markdown

# 管线验证
python skills/campus-device-ops/scripts/verify_openclaw_pipeline.py
```

## Test plan

- [x] **Windows** — `judge_rules_re.py --device-type VAV` → `inspectedDeviceCount=3, faultDeviceCount=2`
- [x] **Windows** — 缺失 rules → exit 1 + `rules_not_found` JSON
- [x] **Windows** — `verify_openclaw_pipeline.py`（全量巡检 → stats → digest → qa）
- [ ] **macOS**（reviewer）— 同上 + 连续两遍 pipeline，贴输出
