# mock_fixtures 说明

| 目录/文件 | 用途 |
|------|------|
| `devices/registry.json` | 园区设备台账 + `assigneeId`（与 device-inspection-re 的 deviceId 对齐） |
| `contacts/assignees.json` | 责任人（张工 / 李工 / 王工） |
| `work_orders/` | 维修工单 JSON |
| `notifications/outbox/` | AI 推送 digest |

**巡检数据库**：默认不在此目录。campus-device-ops **读取** device-inspection-re 的 `mock_fixtures/device_inspection_re.db`（可用 `CAMPUS_OPS_DB_PATH` 覆盖）。

**deviceId 约定**：ASCII `{typeCode}_{seq}`（如 `VAV_001`、`EF_001`），与 device-inspection-re 注册表一致。
