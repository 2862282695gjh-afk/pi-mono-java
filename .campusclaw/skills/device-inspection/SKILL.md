---
name: device-inspection
description: 设备巡检：按 rules.json 调用取数接口获取时间序列数据，执行规则判断，只输出触发故障的设备与告警明细（含原因分析与专家建议）。
---

# 设备巡检（Device Inspection）

本技能只做一件事：**根据 rules.json + 本 skill 取数 mock 输出告警**（不依赖其它 skill）。

## 平行架构

| Skill | 规则 | 取数端口 | 数据库 |
|-------|------|----------|--------|
| **device-inspection** | `rules.json` | `:18080` | `device_inspection.db` |
| device-inspection-re | `rules_re.json` | `:18081` | `device_inspection_re.db` |
| campus-device-ops | `rules_re.json` | `:18083` | `campus_ops.db` |

每次巡检结果**自动写入本 skill 数据库**；查历史故障用 `query_alarms.py`（读库）。

## 规则来源（可移植，不绑定本机用户目录）

默认规则文件按以下顺序解析（脚本内部用**相对本脚本所在 `.campusclaw` 目录**的路径，换机器也可用）：

1. 环境变量 `DEVICE_INSPECTION_RULES_PATH`（若设置且文件存在）
2. **预制规则**：`.campusclaw/rules/rules.json`（与本技能一同放在仓库里）
3. 若当前工作目录下存在 `demo_rule_engine/rules.json`，则作为可选回退（便于你在别的 demo 工程里跑）

可选：用 `--rules-extra` 追加更多 rules 文件（按 `rules[].id` 覆盖，后者覆盖前者）。

## 输出约束（强制）

- **只展示触发故障的设备**（不要展示正常设备）
- 每条告警必须展示：
  - **deviceId**（资产编号，如 `EF-001`、`VAV-DMP-101`；禁止只写 `设备类型_部件`）
  - **deviceName**（设备名称）
  - **building**（楼栋，无则 `—`）
  - **deviceType** / **component**（类型与部件）
  - **ruleId** / **ruleName**（规则 ID 与故障名称）
  - **原因分析**、**专家处理建议**（为空展示 `—`）
- Mock 取数若返回 `送排风_排风机` 等伪 ID，脚本会通过 `mock_fixtures/devices/registry.json` 解析为正式 deviceId
- **禁止输出任何免责声明/提示语**

## 设备台账

`mock_fixtures/devices/registry.json`：`deviceId` + `legacyIds`（mock 别名）+ 名称/楼栋/房间。  
环境变量 `DEVICE_INSPECTION_REGISTRY_PATH` 可指向生产台账 JSON。

## 运行

请在**仓库根目录**（`pi-mono-java`）下执行，保证相对路径一致。

### TSV（推荐：便于复制到 Excel）

```bash
python .campusclaw/skills/device-inspection/scripts/judge_rules.py
```

### JSON（给上层 UI / 工具链使用）

```bash
python .campusclaw/skills/device-inspection/scripts/judge_rules.py --json
```

### 查故障（读数据库，默认最近一次巡检）

```bash
python .campusclaw/skills/device-inspection/scripts/query_alarms.py --json
python .campusclaw/skills/device-inspection/scripts/query_alarms.py --list-runs
python .campusclaw/skills/device-inspection/scripts/query_alarms.py --device-id VAV_001 --json
```

可选参数：

- `--rules`：显式指定规则文件路径（覆盖默认）
- `--rules-extra`：额外要合并的 rules.json（可多次提供；后者覆盖前者）
- `--end-ts`：结束时间戳（epoch seconds 或 ISO8601）。默认：当前 UTC 时间
- `--no-save-db`：仅输出结果，不写入数据库
- `query_alarms.py --run-id insp_YYYYMMDD_HHMMSS`：查指定批次

## 数据库

| 项目 | 说明 |
|------|------|
| mock | SQLite `mock_fixtures/device_inspection.db` |
| 环境变量 | `DEVICE_INSPECTION_DB_PATH` |
| 表 | `inspection_runs`、`inspection_alarms` |
| 生产参考 | `templates/schema.sql` |

```text
judge_rules.py  →  判规则  →  写入 device_inspection.db
query_alarms.py  →  读库查故障（默认 latest）
```

## 取数接口（mock API）

默认取数接口：`http://127.0.0.1:18080/fetch`（可用环境变量 `DEVICE_INSPECTION_API_URL` 覆盖）。

若接口不可达，脚本会尝试自动启动 mock API，顺序为：

1. 环境变量 `DEVICE_INSPECTION_MOCK_API_SCRIPT`（若设置且文件存在）
2. `.campusclaw/skills/device-inspection/mock_api_server.py`（与本技能同目录）
3. 当前工作目录下的 `mock_api_server.py`

mock API 默认优先读取离线夹具数据（fixtures）：

- `.campusclaw/skills/device-inspection/mock_fixtures/<requestId>.json`

可用环境变量 `DEVICE_INSPECTION_FIXTURES_DIR` 指向其它 fixtures 目录。

fixture 支持两种形态：

- 单设备：

```json
{"deviceId":"VAV_001","data":[{"ts":1714200000,"points":{"room_temp":22.1}}]}
```

- 多设备（同一条规则一次返回多台设备）：

```json
{"devices":[{"deviceId":"VAV_001","data":[...]},{"deviceId":"VAV_002","data":[...]}]}
```
