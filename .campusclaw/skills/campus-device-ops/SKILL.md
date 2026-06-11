---
name: campus-device-ops
description: >-
  园区设备运维：查告警、按楼栋/设备统计故障、推送给负责人、生成维修工单、回答「某设备怎么了」。
  基于 device-inspection-re 巡检结果（DB 只读）。
  当用户提到「园区」「设备」「告警」「巡检结果」「工单」「推送」「统计」「故障」
  「设备状态」「运维」「负责人」「通知」「设备清单」「楼栋」时触发。
  即使用户只是说"某设备怎么了"、"帮我查一下告警"、"给负责人推送"也应该使用此技能。
---

# 园区设备运维（campus-device-ops）

消费 **device-inspection-re** 写入的巡检结果（`device_inspection_re.db`），提供统计、问答、推送、工单。所需 BMS / 工单 / 推送接口均为 **mock**（见 `reference.md`）。

## 与设备巡检的关系（上下游）

| 阶段 | Skill | 动作 | 数据库 |
|------|-------|------|--------|
| ① 巡检 | **device-inspection-re** | `judge_rules_re.py` 判规则、**写库** | `device_inspection_re.db` |
| ② 运维 | **campus-device-ops** | 查告警 / 统计 / 问答 / 推送 / 工单，**只读库** | 同上（默认） |

**默认不单独维护 `campus_ops.db`。** 用户先跑设备巡检，后续园区运维问答均基于 **最近一次 device-inspection-re 的 runId**。

## 能力

| # | 能力 | 脚本 |
|---|------|------|
| 1 | 园区设备查询、告警设备统计 | `query_devices.py`、`alarm_stats.py` |
| 2 | 告警 AI 推送（mock 落盘 + Agent 写文案） | `push_alert_digest.py` |
| 3 | 告警问答上下文 | `build_qa_context.py` |
| 4 | 维修工单 | `create_work_order.py` |
| 5 | 关闭工单 | `close_work_order.py` |

`run_inspection.py` 仅 **委托** `device-inspection-re` 再巡一轮并返回同一库中的 run；日常 follow-up **不要重复巡检**，直接 `query_alarms.py`（默认 `latest`）。

## 快速参考

| 场景 | 命令 |
|------|------|
| 安装依赖 | `pip install -r <skill-path>/requirements.txt` |
| 查最新告警 | `python scripts/query_alarms.py --json` |
| 按楼栋统计 | `python scripts/alarm_stats.py --json` |
| 查单设备告警 | `python scripts/query_alarms.py --device-id <ID> --json` |
| 按楼栋筛选 | `python scripts/query_alarms.py --building <楼栋> --json` |
| 查设备清单 | `python scripts/query_devices.py --json` |
| 生成推送文案 | `python scripts/push_alert_digest.py --write-ai-message --json` |
| 创建工单 | `python scripts/create_work_order.py --device-id <ID> --rule-ids <ID> --json` |
| 关闭工单 | `python scripts/close_work_order.py --work-order-id <ID> --json` |
| 列出未关闭工单 | `python scripts/close_work_order.py --list-open --json` |
| 问答上下文 | `python scripts/build_qa_context.py --json` |

**路径说明**：`<skill-path>` = `${OPENCLAW_WORKSPACE}/skills/campus-device-ops`

## 数据流

| 脚本 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `judge_rules_re.py` | rules_re.json + API | `device_inspection_re.db` | 执行巡检并写库（device-inspection-re） |
| `query_alarms.py` | `device_inspection_re.db` | JSON | 查询告警（默认 latest run） |
| `alarm_stats.py` | `device_inspection_re.db` | JSON | 按楼栋/设备类型/负责人统计 |
| `query_devices.py` | `registry.json` | JSON | 查询设备清单 |
| `build_qa_context.py` | `device_inspection_re.db` | JSON | 生成问答上下文 |
| `push_alert_digest.py` | `device_inspection_re.db` | 推送文案 | 生成告警推送文案 |
| `create_work_order.py` | `device_inspection_re.db` | 工单 JSON | 生成维修工单 |
| `close_work_order.py` | 工单 JSON | 更新状态 | 关闭工单 |

## 目录

```text
campus-device-ops/
├── SKILL.md
├── reference.md
├── mock_api_server.py        ← 运维 API :18082（读共享巡检库）
├── mock_fixtures/
│   ├── devices/assignee_map.json  ← 运维 overlay（assigneeId/status）
│   ├── contacts/assignees.json
│   ├── work_orders/
│   └── notifications/outbox/
└── scripts/
    ├── openclaw_env.py           ← OpenClaw 路径/env 对齐
    ├── verify_openclaw_pipeline.py
    ├── query_alarms.py
    ├── alarm_stats.py
    └── ...
```

## 硬规则

1. **查故障必须读 device-inspection-re 的数据库**（或 `CAMPUS_OPS_DB_PATH` 指向的同一文件）
2. 仅当用户明确要求「重新巡检」时，才跑 `device-inspection-re` 或 `run_inspection.py`
3. 问答/推送/工单事实必须来自 DB 查询结果
4. 工单 `problemAnalysis` / `disposalSuggestions` 由脚本出骨架，Agent 润色并引用 `ruleId`

## 快速开始

```bash
pip install -r .campusclaw/skills/campus-device-ops/requirements.txt
pip install -r .campusclaw/skills/device-inspection-re/requirements.txt

# 1. 设备巡检（写库）
python .campusclaw/skills/device-inspection-re/scripts/judge_rules_re.py --json

# 2. 园区运维（读库）
python .campusclaw/skills/campus-device-ops/scripts/query_alarms.py --json
python .campusclaw/skills/campus-device-ops/scripts/alarm_stats.py --json
python .campusclaw/skills/campus-device-ops/scripts/build_qa_context.py --json
python .campusclaw/skills/campus-device-ops/scripts/push_alert_digest.py --write-ai-message --json
python .campusclaw/skills/campus-device-ops/scripts/create_work_order.py \
  --device-id EF_001 --rule-ids dev_rule_a1029126 --json

# 5. 关闭工单
python .campusclaw/skills/campus-device-ops/scripts/close_work_order.py --work-order-id WO-20260610-001 --json
python .campusclaw/skills/campus-device-ops/scripts/close_work_order.py --all --json
python .campusclaw/skills/campus-device-ops/scripts/close_work_order.py --list-open --json

# OpenClaw 一键验收（先巡检 → 统计 → 推送 → 问答）
python .campusclaw/skills/campus-device-ops/scripts/verify_openclaw_pipeline.py --skip-inspection
```

OpenClaw workspace 示例：

```bash
python skills/campus-device-ops/scripts/openclaw_env.py  # 可选：打印 pinned env
python skills/device-inspection-re/scripts/judge_rules_re.py --json
python skills/campus-device-ops/scripts/query_alarms.py --building 能源中心 --json
python skills/campus-device-ops/scripts/push_alert_digest.py --write-ai-message --json
python skills/campus-device-ops/scripts/verify_openclaw_pipeline.py --skip-inspection
```

已废弃的独立巡检路径见 **`DEPRECATED.md`**（`:18083` fetch、`campus_ops.db` 等）。

## 环境变量

| 变量 | 说明 |
|------|------|
| `CAMPUS_OPS_DB_PATH` | 巡检库路径；**默认** = `device-inspection-re/mock_fixtures/device_inspection_re.db` |
| `DEVICE_INSPECTION_RE_DB_PATH` | 同上（与 device-inspection-re 共用） |
| `DEVICE_INSPECTION_RE_SKILL_ROOT` | device-inspection-re skill 根目录 |
| `DEVICE_INSPECTION_RE_JUDGE_SCRIPT` | 巡检脚本路径（默认 `judge_rules_re.py`） |
| `CAMPUS_OPS_API_URL` | 运维 mock API，默认 `http://127.0.0.1:18082/api/v1` |

## Agent 工作流

| 用户意图 | 操作 | 说明 |
|----------|------|------|
| 查最新告警 / 按楼栋统计 | `query_alarms.py --json` / `alarm_stats.py --json` | 读 latest run，勿重跑巡检 |
| 设备清单 | `query_devices.py --json` | 或直接读 registry.json |
| 某设备数据异常 | `query_alarms.py --device-id XX` + 读 mock 数据 | 对比规则阈值和实际值，解释异常原因 |
| 推送告警给负责人 | `push_alert_digest.py --write-ai-message` | 生成文案，由用户自行转发（当前为 mock） |
| 重新巡检 | `device-inspection-re/judge_rules_re.py` | 仅当用户明确要求；若返回 `rules_not_found` 则向用户说明并**终止 skill** |
| 开工单 | `build_qa_context.py` → 问答 → `create_work_order.py` | 完整流程 |

## 问答使用

`build_qa_context.py` 输出的 JSON 包含：
- `summary`：告警摘要（按楼栋、设备类型）
- `alerts`：完整告警列表
- `instructions`：「只从这个上下文回答，不要编造告警」

Agent 问答时：加载此 JSON 作为上下文，根据 `alerts` 字段回答用户问题。禁止编造不在 JSON 中的告警。

## 推送说明

当前推送为 **mock** 实现：生成文案后写入 `notifications/outbox/` 目录，不会实际发送邮件/短信。
Agent 应将生成的推送给用户展示，由用户自行转发给负责人。

## Windows 编码注意事项

部分脚本输出中文时可能触发 `gbk` 编码错误。解决方案：

1. 脚本内部已调用 `configure_stdio_utf8()` 修复 stdout/stderr 编码
2. 若仍有问题，运行前设置环境变量：`$env:PYTHONIOENCODING='utf-8'`

## 输出约束

- 统计结果用表格展示，包含设备 ID、设备名称、楼栋、告警数
- 推送文案由 `push_message.py` 生成，Agent 不要自己编
- 工单必须包含：工单号、设备信息、故障明细、原因分析、专家建议
- 问答必须基于 DB 查询结果，禁止编造告警
- 展示设备时使用正式 asset ID（如 `VAV_001`、`EF_001`），ASCII only
- 原因分析、专家建议为空时展示 `—`

Mock API 详见 **`reference.md`**。
