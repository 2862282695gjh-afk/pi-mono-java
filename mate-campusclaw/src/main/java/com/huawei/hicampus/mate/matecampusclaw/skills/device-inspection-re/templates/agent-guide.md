# Agent 工作指南（device-inspection-re）

## 硬规则

1. **巡检写入**：只有 `judge_rules_re.py` 写 `device_inspection_re.db`。
2. **展示事实**：统计、报告、工单必须来自脚本输出；禁止编造告警或点位。
3. **设备 ID**：展示正式 asset ID（`VAV_001`、`EF_001`），ASCII only；禁止 `设备类型_部件` 伪 ID。
4. **rule_name**：明细表必须使用完整 `ruleName`，禁止缩写。
5. **禁止免责声明**；无数据时提示先跑 `judge_rules_re.py`。
6. **规则文件缺失**：若 `judge_rules_re.py` 返回 `rules_not_found` 或 exit code 非 0 且提示未找到 `rules_re.json`，**向用户说明原因并立即结束本 skill**，勿继续 `format_inspection_report` / `alarm_stats` / 工单等步骤。引导用户先用 **excel-antlr-to-rules-json** 编译规则，或放置 `device-inspection-re/rules/rules_re.json`。

## 标准流程

```text
judge_rules_re.py --json
format_inspection_report.py --markdown    # 向用户展示
alarm_stats.py --json                     # 需要聚合统计时
create_work_order.py --device-id ...      # 用户要工单时
```

## 场景 → 脚本

| 用户意图 | 命令 |
|----------|------|
| 执行巡检 | `judge_rules_re.py --json` |
| 展示巡检结果 | `format_inspection_report.py --markdown` |
| 告警统计 | `alarm_stats.py --json` |
| 查告警 / 单设备 | `query_alarms.py [--device-id XXX] --json` |
| 生成工单 | `create_work_order.py --device-id XXX --rule-ids ... --json` |
| 历史 run | `query_alarms.py --list-runs` |

## 与 campus-device-ops 分工

- **本 skill**：巡检 + 结果展示 + 统计 + 工单
- **campus-device-ops**：推送 digest、运维问答等（读同一 DB）

用户要「推送给负责人」时，转 campus-device-ops 的 `push_alert_digest.py`。

## 工单

1. 确认 deviceId 与 ruleId 来自最新巡检。
2. `create_work_order.py --device-id ... --rule-ids ... --json`
3. 展示工单号、设备、故障明细、原因分析、专家建议。

## 信息不足时

```text
Locked: 已确认 EF_001 有 2 条告警
Missing: 要为哪条 rule 创建工单
Next question: 请选择 ruleId A 或 ruleId B
```
