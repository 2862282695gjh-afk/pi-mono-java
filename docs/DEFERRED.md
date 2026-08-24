# Deferred Work

未完成或暂缓实现的功能项清单。**新增 `TODO/FIXME` 注释会被 Checkstyle 规则 `no_todo_fixme_in_delivery_code` 拒绝**（规则定义见 [`codecheck.xml`](../codecheck.xml)），请改在此处登记。完成后从表中移除（git 历史保留追溯）。

> 若是已上线产品的缺陷，直接在 issue tracker 立单即可，无需登记到此表。本表面向「已知尚未实现的功能 / 主动暂缓的能力 / 阶段性占位逻辑」。

| ID | 模块 | 描述 | 触发条件 / 何时需要 | 关联 issue |
|---|---|---|---|---|
| DEF-003 | agent-core (control-plane) | `NodeRegistry` 持久化到外部存储（etcd / Postgres）。当前注册表为 `ConcurrentHashMap` 内存态，进程重启即丢失。`NodeRegistry` Javadoc 已引用本条目。 | 当 control-plane 需要跨重启保持节点注册状态、或多实例 control-plane 共享注册表时 | — |
| DEF-004 | agent-core (control-plane) | 控制面端点鉴权（mTLS / bearer token / Spring Security）。当前 register / heartbeat / deregister / schedule 端点对任何能访问服务端口的调用方开放；默认服务已监听 0.0.0.0，详见 [Control Plane 设计](designs/control-plane.md)。 | 任何生产网络暴露之前；当前必须由网关或网络策略隔离 `/api/v1/*` | — |
| DEF-005 | agent-core (control-plane) | 调度策略链（least-active / weighted-round-robin / capacity-aware）。当前 `RuntimeScheduler` 仅支持 sticky affinity + round-robin 两级。 | 当 fleet 异构（节点 CPU / 内存差异大）或需要 QoS 分层调度时 | — |
| DEF-006 | agent-core (control-plane) | 剩余管理面端点（node drain / graceful shutdown / metrics aggregation / fleet-wide health）。当前仅 register / heartbeat / list / deregister / capabilities / runtimes / schedule 七个端点。 | 当运维需要主动排水节点或聚合 fleet 指标时 | — |
