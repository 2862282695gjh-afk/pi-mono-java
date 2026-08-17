---
name: device-inspection-re
description: >-
  设备巡检（rules_re.json / rule-engine）：判规则、写库、展示巡检报告、告警统计、生成维修工单。
  当用户提到「巡检」「告警」「故障」「设备异常」「检查设备」「设备状态」「rules_re」「统计」
  「工单」「巡检结果」「设备健康」「故障排查」「设备诊断」时触发。
  即使用户只是说"看看设备怎么了"、"有没有问题"、"设备正常吗"也应该使用此技能。
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

## 路径说明

`<skill-path>` = 本 skill 根目录（含 `SKILL.md`）；`<workspace>` = 含 `skills/` 的父目录。脚本按 `__file__` 解析路径，可从任意 cwd 调用。

## 快速参考

| 场景 | 命令 |
|------|------|
| 全量巡检 | `python <skill-path>/scripts/judge_rules_re.py --json` |
| 只巡检 VAV | `python <skill-path>/scripts/judge_rules_re.py --device-type VAV --json` |
| 多类型巡检 | `python <skill-path>/scripts/judge_rules_re.py --device-type VAV --device-type 新风机 --json` |
| 列出设备类型 | `python <skill-path>/scripts/judge_rules_re.py --list-device-types --json` |
| 巡检 + 健康度 | `python <skill-path>/scripts/judge_rules_re.py --json --include-healthy` |
| 展示巡检报告 | `python <skill-path>/scripts/format_inspection_report.py --markdown` |
| 报告 + 健康设备 | `python <skill-path>/scripts/format_inspection_report.py --markdown --include-healthy` |
| 告警统计 | `python <skill-path>/scripts/alarm_stats.py --json` |
| 查单设备告警 | `python <skill-path>/scripts/query_alarms.py --device-id VAV_003 --json` |
| 查历史巡检 | `python <skill-path>/scripts/query_alarms.py --list-runs` |
| 创建工单 | `python <skill-path>/scripts/create_work_order.py --device-id <ID> --rule-ids <ID> --json` |
| 关闭工单 | `python <skill-path>/scripts/close_work_order.py --work-order-id <ID> --json` |
| 列出未关闭工单 | `python <skill-path>/scripts/close_work_order.py --list-open --json` |

## 推荐 Agent 流程

```text
用户：跑一轮巡检 / 看故障
  1. judge_rules_re.py --json
  2. format_inspection_report.py --markdown     ← 按输出约束展示
  3. alarm_stats.py --json                      ← 需要聚合统计时

用户：为某设备开工单
  → create_work_order.py --device-id EF_001 --rule-ids <id> --json

用户：只要某台设备告警
  → query_alarms.py --device-id VAV_003 --json

用户：只巡检某一类设备（如 VAV / 新风机）
  → judge_rules_re.py --device-type VAV --json
  → judge_rules_re.py --device-type VAV --device-type 新风机 --json   # 多类型
  → judge_rules_re.py --list-device-types --json                      # 列出规则中的 deviceType
```

## 数据流

| 脚本 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `judge_rules_re.py` | rules_re.json + API `:18081/fetch` | `device_inspection_re.db` | 执行巡检判定并写库 |
| `format_inspection_report.py` | `device_inspection_re.db` | Markdown / JSON | 生成巡检展示报告 |
| `alarm_stats.py` | `device_inspection_re.db` | JSON | 按楼栋/等级/负责人统计 |
| `create_work_order.py` | `device_inspection_re.db` | `mock_fixtures/work_orders/WO-*.json` | 生成维修工单 |
| `query_alarms.py` | `device_inspection_re.db` | JSON | 查询告警（默认 latest run） |
| `close_work_order.py` | `mock_fixtures/work_orders/WO-*.json` | 更新工单状态 | 关闭工单 |

## 运行

```bash
pip install -r <skill-path>/requirements.txt

# 1. 巡检（入库）
python <skill-path>/scripts/judge_rules_re.py --json

# 1b. 按设备类型巡检（只判选中类型的规则，结果仍写入同一 DB）
python <skill-path>/scripts/judge_rules_re.py --device-type VAV --json
python <skill-path>/scripts/judge_rules_re.py --list-device-types

# 2. 展示巡检结果（Agent 向用户呈现此输出）
python <skill-path>/scripts/format_inspection_report.py --markdown

# 3. 告警统计
python <skill-path>/scripts/alarm_stats.py --json

# 4. 查单设备 / 列表 run
python <skill-path>/scripts/query_alarms.py --json
python <skill-path>/scripts/query_alarms.py --list-runs

# 5. 生成维修工单
python <skill-path>/scripts/create_work_order.py \
  --device-id EF_001 --rule-ids dev_rule_a1029126 --json

# 6. 关闭工单
python <skill-path>/scripts/close_work_order.py --work-order-id WO-20260610-001 --json
python <skill-path>/scripts/close_work_order.py --all --json
python <skill-path>/scripts/close_work_order.py --list-open --json
```

## 规则来源

1. `DEVICE_INSPECTION_RE_RULES_PATH`
2. `device-inspection-re/rules/rules_re.json`（本 skill 目录下）
3. 向上搜索 `skills/device-inspection-re/rules/rules_re.json`

**若以上路径均无 `rules_re.json`**：脚本以 exit code 1 退出，并输出 `rules_not_found`（`--json` 时为结构化 JSON）。Agent **必须向用户说明并终止本次 skill**，引导使用 **excel-antlr-to-rules-json** 从 Excel 编译规则，勿继续执行报告/统计/工单。

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

1. **摘要**：**此次巡检范围**总台数（`inspectedDeviceCount`）、故障台数（`faultDeviceCount`）、正常台数（`healthyDeviceCount`）、告警条数；分类型巡检时总台数仅统计该类型（如 VAV 3 台），全量巡检为 registry 全部设备
2. **设备汇总表**：deviceId | deviceName | building | alertCount | ruleNames | assignee
3. **告警明细表**：完整 `ruleName`（禁止用「风机/变频」等缩写）、原因分析、专家建议

通用规则：

- **只展示触发故障的设备**
- 原因分析、专家建议为空时展示 `—`
- 设备列使用正式 asset ID（如 `VAV_001`、`EF_001`），须为 ASCII
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
