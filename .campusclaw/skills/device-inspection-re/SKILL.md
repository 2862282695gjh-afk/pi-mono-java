---
name: device-inspection-re
description: >-
  设备巡检（rules_re.json / rule-engine）：判规则、写库、展示巡检报告、告警统计、生成维修工单。
  与 excel-antlr-to-rules-json 配套。当用户提到「巡检」「告警」「故障设备」「rules_re」
  「统计」「工单」「巡检结果」时触发。
---

# 设备巡检（rule-engine 版）

本技能消费 **`rules_re.json`**，使用 PyPI `rule-engine` 判告警，结果写入 **`device_inspection_re.db`**。  
巡检完成后可在**本 skill 内**完成：告警展示、统计、维修工单（无需切换 skill）。

## 与 campus-device-ops 的关系

| 阶段 | Skill | 动作 |
|------|-------|------|
| ① 巡检 + 结果展示/统计/工单 | **device-inspection-re**（本 skill） | 写库 + 读库展示 |
| ② 园区运维（推送 digest 等） | campus-device-ops | 读同一库；推送 mock |

两 skill 共用 `device_inspection_re.db`。本 skill 负责 **巡检写入** 与 **巡检结果呈现**；campus-device-ops 负责推送 digest、运维问答等扩展能力。

## 能力

| # | 能力 | 脚本 |
|---|------|------|
| 1 | 执行巡检、写库 | `judge_rules_re.py` |
| 2 | 查告警 | `query_alarms.py` |
| 3 | **巡检结果展示**（摘要 + 汇总表 + 明细表） | `format_inspection_report.py` |
| 4 | **告警统计** | `alarm_stats.py` |
| 5 | **维修工单** | `create_work_order.py` |
| 6 | **关闭工单** | `close_work_order.py` |

## 推荐 Agent 流程

```text
用户：跑一轮巡检 / 看故障
  1. judge_rules_re.py --json
  2. format_inspection_report.py --markdown     ← 按输出约束展示
  3. alarm_stats.py --json                      ← 需要聚合统计时

用户：为某设备开工单
  → create_work_order.py --device-id EF-001 --rule-ids <id> --json

用户：只要某台设备告警
  → query_alarms.py --device-id VAV-CO2-201 --json
```

## 数据流

```text
judge_rules_re.py           →  judge + :18081/fetch  →  device_inspection_re.db
format_inspection_report.py →  读库 → 展示用 Markdown / JSON
alarm_stats.py              →  读库 → 按楼栋/等级/负责人统计
create_work_order.py        →  读库 → mock_fixtures/work_orders/WO-*.json
query_alarms.py             →  读库（默认 latest）
```

## 运行

```bash
pip install -r skills/device-inspection-re/requirements.txt

# 1. 巡检（入库）
python skills/device-inspection-re/scripts/judge_rules_re.py --json

# 2. 展示巡检结果（Agent 向用户呈现此输出）
python skills/device-inspection-re/scripts/format_inspection_report.py --markdown

# 3. 告警统计
python skills/device-inspection-re/scripts/alarm_stats.py --json

# 4. 查单设备 / 列表 run
python skills/device-inspection-re/scripts/query_alarms.py --json
python skills/device-inspection-re/scripts/query_alarms.py --list-runs

# 5. 生成维修工单
python skills/device-inspection-re/scripts/create_work_order.py \
  --device-id EF-001 --rule-ids dev_rule_a1029126 --json

# 6. 关闭工单
python skills/device-inspection-re/scripts/close_work_order.py --work-order-id WO-20260610-001 --json
python skills/device-inspection-re/scripts/close_work_order.py --all --json
python skills/device-inspection-re/scripts/close_work_order.py --list-open --json
```

## 规则来源

1. `DEVICE_INSPECTION_RE_RULES_PATH`
2. `workspace/rules/rules_re.json`
3. `.campusclaw/rule-engines-pack/.../rules_re.json`

## 数据库

| 项目 | 说明 |
|------|------|
| mock | SQLite `mock_fixtures/device_inspection_re.db` |
| 环境变量 | `DEVICE_INSPECTION_RE_DB_PATH` |
| 取数 | `DEVICE_INSPECTION_RE_API_URL` 默认 `http://127.0.0.1:18081/fetch` |
| 工单落盘 | `mock_fixtures/work_orders/WO-*.json` |

负责人信息默认读取同级 **campus-device-ops** 的 `assignee_map.json` / `assignees.json`（可通过 `CAMPUS_DEVICE_OPS_SKILL_ROOT` 指定）。

## Windows 编码注意事项

部分脚本输出中文时可能触发 `gbk` 编码错误。解决方案：

1. 脚本内部已调用 `configure_stdio_utf8()` 修复 stdout/stderr 编码
2. 若仍有问题，运行前设置环境变量：`$env:PYTHONIOENCODING='utf-8'`

## 输出约束（向用户展示时）

**巡检展示**（`format_inspection_report.py`）必须包含：

1. **摘要**：故障设备数、告警总数、按楼栋/等级统计
2. **设备汇总表**：deviceId | deviceName | building | alertCount | ruleNames | assignee
3. **告警明细表**：完整 `ruleName`（禁止用「风机/变频」等缩写）、原因分析、专家建议

通用规则：

- **只展示触发故障的设备**
- 原因分析、专家建议为空时展示 `—`
- 设备列使用正式 asset ID（如 `EF-001`）
- **禁止免责声明**

## 工单展示约束

`create_work_order.py` 输出必须向用户展示：

- 工单号 `id`
- 设备信息（deviceId、deviceName、building）
- 故障明细（ruleId、ruleName、reasonAnalysis、expertAdvice）
- `problemAnalysis`、`disposalSuggestions`

## Mock 夹具

| 路径 | 说明 |
|------|------|
| `mock_fixtures/devices/<deviceId>.json` | 时序夹具 |
| `mock_fixtures/devices/registry.json` | 设备台账 |

Mock 取数：`:18081/fetch`（`mock_api_server.py`）。

Agent 细则见 **`templates/agent-guide.md`**。
