# 推送消息模板（Agent 填写 aiMessage）

## 结构

```text
【{楼栋}-{责任人角色}】{告警条数}条设施告警需处理

{逐条简述，每条一行}
- {deviceName}（{deviceId}）：{ruleName}，优先级{priority}

建议：{来自 expertAdvice 的一句摘要}

请于 {时效，如30分钟} 内现场确认或创建工单跟进。
```

## 约束

- 仅使用 digest.items 中的字段
- 不编造实时点位数值
- 中文面向责任人，日志若需英文另写
