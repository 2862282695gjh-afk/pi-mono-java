# campus-device-ops Mock API 设计

本文档描述 **v1 mock 接口契约**。生产对接时替换 `CAMPUS_OPS_API_URL` 与取数 URL，skill 脚本签名不变。

## 服务地址

| 服务 | 默认 URL | 说明 |
|------|----------|------|
| 园区运维 mock | `http://127.0.0.1:18082/api/v1` | 设备台账、巡检编排、告警统计、工单、推送 |
| 巡检时序取数 mock | `http://127.0.0.1:18081/fetch` | **device-inspection-re** 的 `mock_api_server.py`（本 skill 不承载取数） |

环境变量：

| 变量 | 默认 |
|------|------|
| `CAMPUS_OPS_API_URL` | `http://127.0.0.1:18082/api/v1` |
| `CAMPUS_OPS_RULES_PATH` | `.campusclaw/rules/rules_re.json` → 回退 `rule-engines-pack/.../rules_re.json` |
| `CAMPUS_OPS_FIXTURES_DIR` | skill 内 `mock_fixtures/` |
| `CAMPUS_OPS_DB_PATH` | 默认 = device-inspection-re 的 `device_inspection_re.db` |
| `DEVICE_INSPECTION_RE_DB_PATH` | 同上（共享巡检库） |

## 数据库（告警持久化）

- **mock（默认）**：与 **device-inspection-re** 共用 SQLite `device_inspection_re.db`
- **生产参考**：`templates/schema.sql`（PostgreSQL）

| 表 | 说明 |
|----|------|
| `inspection_runs` | 每次巡检一条（run_id、end_ts、告警数量） |
| `inspection_alarms` | 每条告警一行（设备、规则、原因、建议、楼栋等） |

**写入**：`device-inspection-re/scripts/judge_rules_re.py`（或 `run_inspection.py` 委托执行）  
**读取**：campus-device-ops 的 `query_alarms.py`、`alarm_stats.py` 等（默认 `runId=latest`）

环境变量 `CAMPUS_OPS_DB_PATH` / `DEVICE_INSPECTION_RE_DB_PATH` 指向同一文件。

启动 mock（仅需运维 API；巡检取数走 device-inspection-re `:18081`）：

```bash
python .campusclaw/skills/campus-device-ops/mock_api_server.py
```

---

## 1. 健康检查

`GET /health`

```json
{ "status": "ok", "service": "campus-device-ops-mock", "port": 18082 }
```

---

## 2. 设备查询

`GET /devices?building=&deviceType=&status=&deviceId=&withAlarms=true|false`

**Response 200**

```json
{
  "version": 1,
  "campus": "DemoChillerCampus",
  "total": 2,
  "devices": [
    {
      "deviceId": "VAV_风阀",
      "name": "A栋-101 VAV风阀",
      "building": "A栋",
      "floor": "1F",
      "room": "101",
      "deviceType": "VAV",
      "component": "风阀",
      "status": "online",
      "assigneeId": "ops_hvac_a",
      "hasAlarm": true
    }
  ]
}
```

`GET /devices/{deviceId}` → `{ "version": 1, "device": { ... } }`

数据源：device-inspection-re 的 `mock_fixtures/devices/registry.json` + 本 skill 的 `mock_fixtures/devices/assignee_map.json`（运维 overlay：`assigneeId` / `status`）

---

## 3. 巡检（rules_re.json）

`POST /inspection/run`

**Request**

```json
{
  "rulesPath": "/optional/path/rules_re.json",
  "endTs": 1777306508
}
```

**Response 200**

```json
{
  "version": 1,
  "rulesPath": ".../rules_re.json",
  "inspection": {
    "fault_devices": ["VAV_风阀"],
    "alerts_by_device": { "VAV_风阀": [ { "rule_id", "rule_name", "reason_analysis", "expert_advice" } ] },
    "end_ts": 1777306508.0
  },
  "enriched": {
    "enriched_fault_devices": [ { "deviceId", "building", "assigneeName", ... } ]
  }
}
```

内部流程（已废弃）：历史 `:18083/fetch` + 本地 judge；现用 **device-inspection-re** `:18081/fetch` + `judge_rules_re.py`。

持久化：`inspection_runs` + `inspection_alarms`（返回 `runId`）

---

## 4. 告警（读数据库）

### 4.0 查询故障列表

`GET /alarms?runId=latest&building=&deviceId=&ruleId=`

```json
{
  "version": 1,
  "runId": "insp_20260608_093045",
  "total": 3,
  "alarms": [
    {
      "deviceId": "VAV_风阀",
      "ruleId": "vav_damper_control_error_overlimit",
      "ruleName": "风阀控制误差超限",
      "reasonAnalysis": "—",
      "expertAdvice": "—",
      "building": "A栋",
      "assigneeName": "张工"
    }
  ]
}
```

`GET /inspection/runs?limit=20` — 历史巡检列表

### 4.1 活跃告警

`GET /alarms/active`

```json
{
  "version": 1,
  "endTs": 1777306508.0,
  "faultDevices": ["VAV_风阀"],
  "alertsByDevice": { ... }
}
```

### 4.2 告警统计

`GET /alarms/stats`

```json
{
  "version": 1,
  "registryDeviceCount": 6,
  "alarmDeviceCount": 2,
  "totalAlertCount": 3,
  "byBuilding": { "A栋": 1, "能源中心": 1 },
  "byDeviceType": { "VAV": 1, "PAHU": 1 },
  "byRule": { "vav_damper_control_error_overlimit": 1 },
  "byAssignee": { "ops_hvac_a": 1 },
  "faultDevices": ["VAV_001"]
}
```

---

## 5. 问答上下文

`GET /qa/context?maxAlarms=100`

供 Agent 读取，禁止编造未列出的告警。

```json
{
  "version": 1,
  "generatedAt": "2026-05-19T12:00:00+00:00",
  "summary": { "alarmDeviceCount": 2, "totalAlertCount": 3, "byBuilding": {} },
  "alerts": [ { "deviceId", "ruleName", "reasonAnalysis", "expertAdvice" } ],
  "instructions": "..."
}
```

---

## 6. 责任人

`GET /contacts/assignees`

数据源：`mock_fixtures/contacts/assignees.json`

---

## 7. AI 预警推送（mock）

`POST /notifications/push`

**Request**

```json
{
  "assigneeId": "ops_hvac_a",
  "aiMessage": "【A栋暖通】VAV_001 风阀控制误差超限，请30分钟内现场确认。"
}
```

**Response 200**

```json
{
  "version": 1,
  "digestCount": 1,
  "files": [".../notifications/outbox/digest_ops_hvac_a_1716123456.json"],
  "digests": [ { "assigneeId", "items", "aiMessage" } ]
}
```

v1 落盘到 `mock_fixtures/notifications/outbox/`；后续可对接 Gateway WS / 企微 webhook。

---

## 8. 维修工单

### 8.1 创建

`POST /work-orders`

**Request**

```json
{
  "deviceId": "VAV_风阀",
  "ruleIds": ["vav_damper_control_error_overlimit"],
  "assigneeId": "ops_hvac_a",
  "problemAnalysis": {
    "summary": "...",
    "possibleCauses": [ { "cause", "likelihood", "evidence" } ]
  },
  "disposalSuggestions": {
    "steps": ["..."],
    "expertAdviceRef": "rules_re.json"
  }
}
```

`problemAnalysis` / `disposalSuggestions` 可省略；脚本从 rules_re 的「原因分析」「专家处理建议」生成骨架，**Agent 负责润色与补充**。

**Response 201**

```json
{ "version": 1, "workOrder": { "id": "WO-20260519-001", "status": "open", ... } }
```

### 8.2 列表 / 详情

- `GET /work-orders`
- `GET /work-orders/{id}`

持久化：`mock_fixtures/work_orders/WO-*.json`

Schema：`templates/work-order.schema.json`

---

## 9. 时序取数（device-inspection-re）

巡检取数不在本 skill 实现。`run_inspection.py` / `judge_rules_re.py` 使用：

`POST http://127.0.0.1:18081/fetch`（**device-inspection-re** 的 `mock_api_server.py`）

请求体含 `endTs` + `queries[]`（由 rules_re 每条规则展开）。夹具目录：`device-inspection-re/mock_fixtures/devices/`。

历史 `:18083/fetch` 与 `fetch_api_server.py` 已移除，见 `DEPRECATED.md`。

---

## 脚本 ↔ API 对照

| 脚本 | 本地模式 | HTTP 模式 |
|------|----------|-----------|
| `query_devices.py` | 读 inspection registry + assignee_map | `--http` |
| `run_inspection.py` | 委托 device-inspection-re 写共享 DB | `--http`；`--device-type VAV` 分类型巡检 |
| `alarm_stats.py` | 读 `device_inspection_re.db` | GET `/alarms/stats` |
| `build_qa_context.py` | 读共享 DB | GET `/qa/context` |
| `push_alert_digest.py` | 写 outbox + `aiMessage`（`--write-ai-message`） | POST `/notifications/push`（`--push-http`） |
| `verify_openclaw_pipeline.py` | 一键验收巡检→统计→推送→问答 | — |
| `create_work_order.py` | 写 work_orders/ | POST `/work-orders` |
