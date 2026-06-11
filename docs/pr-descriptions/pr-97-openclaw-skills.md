# PR title (replace current title)

```
feat(skills): 新增 campus-device-ops 园区运维与 device-inspection-re 巡检 skill，完善 excel-antlr-to-rules-json
```

# PR body (paste into GitHub description)

## Summary

引入 OpenClaw 三 skill 协作链路（Excel 编译规则 → 巡检写库 → 园区运维只读）：

| Skill | 能力 |
|-------|------|
| `excel-antlr-to-rules-json` | Excel → `rules_re.json`（ANTLR + rule-engine） |
| `device-inspection-re` | 判规则、写 `device_inspection_re.db`、报告/统计/工单 |
| `campus-device-ops` | 读共享库：告警查询、digest 推送、问答上下文、工单关单 |

**Review 整改要点：**

- 删除 campus 侧重复 `judge_rules.py`（393 行）；巡检统一走 `judge_rules_re.py`
- 工单 ID 对齐 `WO-YYYYMMDD-NNN` schema；`push_alert_digest --write-ai-message` 修复
- mock 停服：PID 文件 + 仅终止 `mock_api_server.py`（`ps`/`Get-CimInstance`，macOS/Linux/Windows）
- 读方去掉 SQLite DDL；`apply_openclaw_defaults(force=False)` 默认不覆盖用户 env
- 双 skill 脚本对齐：`.campusclaw/skills/SYNCED_SCRIPTS.md`
- 移除未使用的 `fetch_api_server.py`（:18083 尸体）

**入库 vs 排除（`.gitignore`）：**

| 入库（seed / 可 fresh-clone 运行） | 排除（运行时产物） |
|-----------------------------------|-------------------|
| `mock_api_server.py`（:18081）、`devices/*.json`、`registry.json`、`assignees.json` | `*.db`、`work_orders/`、`notifications/outbox/`、`state/` |
| `.campusclaw/rules/rules_re.json`（demo seed） | |

**文档：** [openclaw-device-skills.md](../designs/openclaw-device-skills.md) · [ADR-0006](../decisions/0006-shared-inspection-sqlite-db.html)

## Test plan

- [x] **Windows 11** — 完整管线 ×2（验证 mock reload / 停服路径）：

```text
cd .campusclaw/skills/campus-device-ops/scripts
python verify_openclaw_pipeline.py
# run 1: PIPELINE OK runId=insp_20260611_073245 alerts=21 devices=5 digests=3
python verify_openclaw_pipeline.py
# run 2: PIPELINE OK runId=insp_20260611_073247 alerts=21 devices=5 digests=3

python smoke_test.py
# SMOKE OK
```

- [ ] **macOS**（reviewer 环境）— 同上命令连续两遍，贴输出到本 PR
