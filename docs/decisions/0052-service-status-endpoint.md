# ADR-0052：服务存活接口返回固定文本

| 字段 | 内容 |
|---|---|
| Status | Accepted |
| Date | 2026-09-04 |
| Version | 1.0.0 |
| 变更前基线 | `0300541fbbe3db8b05ffa1ac953f15f01df74b3e` |
| 已核对实现基线 | `e8b861f2878ff07769a0e1c15ac5e45ab04316b3` |

## Context

用户要求新增截图中的 `StatusController`：`GET /api/v1/status` 返回 HTTP 200 和字符串 `Success`。
既有控制面使用 Spring MVC；变更前基线没有该接口。本决策确认用户指定的接口契约。

## Decision

在 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/controlplane/api/StatusController.java`
新增无依赖的 `status()` 方法，直接返回 `ResponseEntity.ok("Success")`。
企业包名版本通过 `scripts/sync-campusclaw.sh` 生成。
上述行为已存在于已核对实现基线，不属于尚未实现的目标设计，也不以 pi 源码为设计基线。

## 选项与理由

| 选项 | 优点 | 代价与取舍 |
|---|---|---|
| 固定文本接口（采用） | 与用户提供的路径及响应完全一致，处理无外部 I/O | 只能表示 HTTP 存活 |
| JSON/ResultBean 响应 | 与普通业务 JSON 接口结构相近 | 改变用户指定的字符串契约，因此不采用 |
| 检查数据库、MateService 或模型服务 | 能提供依赖健康信息 | 引入调用、耗时和故障语义，超出本次要求 |

固定路径和纯文本响应属于**产品约束**。本次没有引入独立安全加固或其他架构变更。

## Consequences

调用方不需要构造业务对象或提供业务凭据。响应 `Success` 仅说明服务能够处理此请求，不能据此判定数据库或模型调用可用。
源码同步已完成；企业镜像的完整编译验证仍受公司 `NativeParent` 依赖不可解析的环境限制。

## Related

- [Control Plane 设计 2.2.0](../designs/control-plane.md)：源码证据、调用关系、边界与验证结果。
