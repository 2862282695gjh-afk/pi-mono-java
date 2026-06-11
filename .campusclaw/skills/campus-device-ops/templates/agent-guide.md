# Agent 工作指南（campus-device-ops）

## 硬规则

1. **巡检库**：默认读 **device-inspection-re** 的 `device_inspection_re.db`（与 `CAMPUS_OPS_DB_PATH` 同一文件）。
2. **事实来源**：设备、告警、统计、工单必须来自脚本输出或 mock API；**禁止编造**点位读数或未出现的告警。
3. **推送文案**：基于 `push_alert_digest.py` 或 `/notifications/push` 的 `items`；可润色，不可添加 items 中没有的设备/规则。
4. **工单分析**：`problemAnalysis` / `disposalSuggestions` 必须引用 `ruleId` 与 rules_re 中的「原因分析」「专家处理建议」；Agent 可排序、归纳，不可 contradict 原文。

## 典型流程

```text
device-inspection-re/judge_rules_re.py   # 用户要巡检时：写 device_inspection_re.db
query_alarms.py                          # 查故障（读库，默认 latest）
alarm_stats.py                           # 统计（读库）
build_qa_context.py                      # 问答上下文（读库）
push_alert_digest.py                     # 推送（读库）
create_work_order.py                     # 工单（读库）
```

`run_inspection.py` = 委托 device-inspection-re 再巡一轮；**查告警时不要默认重跑**。

## 问答

- 先 `build_qa_context.py` 或 `query_alarms.py`（读数据库），再回答用户。
- 库中无巡检记录时：提示执行 **device-inspection-re** 的 `judge_rules_re.py`。
- **规则文件缺失**（`rules_not_found` / 未找到 `rules_re.json`）：向用户说明，引导 **excel-antlr-to-rules-json** 或放置 `rules/rules_re.json`，**立即结束 campus-device-ops / 巡检相关 skill**，勿委托 `run_inspection` 后继续推送或工单。
- **不要**为「查当前故障」重复跑巡检，除非用户明确要求重新巡检。

## 工单

1. 用户确认设备与告警后：`create_work_order.py --device-id ... --rule-ids ...`
2. 若需深度分析：Agent 生成 `problemAnalysis` / `disposalSuggestions` JSON 文件，用 `--analysis-json` / `--disposal-json` 传入。
3. 工单 ID 格式：`WO-YYYYMMDD-NNN`

## 推送

1. `push_alert_digest.py` 生成 per-assignee digest。
2. Agent 按 `templates/push-digest.template.md` 写 `aiMessage`，POST `/notifications/push` 或更新 outbox JSON。

## 每轮最多 1 个问题

信息不足时（例如多台设备同名告警需用户选一台），使用：

```text
Locked: 已确认 A栋 2 台 VAV 告警
Missing: 要为哪台 deviceId 创建工单
Next question: 请选择 VAV_001 或 VAV_002
```
