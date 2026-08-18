# Deferred Work

未完成或暂缓实现的功能项清单。**新增 `TODO/FIXME` 注释会被 Checkstyle 规则 `no_todo_fixme_in_delivery_code` 拒绝**（规则定义见 [`codecheck.xml`](../codecheck.xml)），请改在此处登记。完成后从表中移除（git 历史保留追溯）。

> 若是已上线产品的缺陷，直接在 issue tracker 立单即可，无需登记到此表。本表面向「已知尚未实现的功能 / 主动暂缓的能力 / 阶段性占位逻辑」。

| ID | 模块 | 描述 | 触发条件 / 何时需要 | 关联 issue |
|---|---|---|---|---|
| DEF-001 | coding-agent-cli | 将剪贴板粘贴的图片真正附到 LLM 消息上。当前 `InteractiveMode#pasteImage` 仅保存到 tmp 文件并通过状态栏告知用户路径。 | 当多模态输入接入 InteractiveMode、`Agent` 支持携带 image 内容块时 | — |
| DEF-002 | coding-agent-cli | `pi install <source>` 真正执行 npm / git clone 安装。当前 `CampusClawCommand` 的 install 分支只打印提示，要求用户手动在 `settings.json` 的 `packages` 数组里追加。 | Skill 包远程安装能力（HTTP/git 拉取 + 校验）上线时 | — |
| DEF-003 | agent-core (control-plane) | `NodeRegistry` 持久化到外部存储（etcd / Postgres）。当前注册表为 `ConcurrentHashMap` 内存态，进程重启即丢失。`NodeRegistry` Javadoc 已引用本条目。 | 当 control-plane 需要跨重启保持节点注册状态、或多实例 control-plane 共享注册表时 | — |
| DEF-004 | agent-core (control-plane) | 控制面端点鉴权（mTLS / bearer token / Spring Security）。当前 register / heartbeat / deregister / schedule 端点对任何能访问该端口的调用方开放。默认 localhost 绑定下风险有限，见 [ADR-0010](decisions/0010-defer-control-plane-auth.html)。 | 当 control-plane 暴露到非 localhost 网络（0.0.0.0 绑定 / Kubernetes Service / 反向代理）时 | — |
| DEF-005 | agent-core (control-plane) | 调度策略链（least-active / weighted-round-robin / capacity-aware）。当前 `RuntimeScheduler` 仅支持 sticky affinity + round-robin 两级。 | 当 fleet 异构（节点 CPU / 内存差异大）或需要 QoS 分层调度时 | — |
| DEF-006 | agent-core (control-plane) | 剩余管理面端点（node drain / graceful shutdown / metrics aggregation / fleet-wide health）。当前仅 register / heartbeat / list / deregister / capabilities / runtimes / schedule 七个端点。 | 当运维需要主动排水节点或聚合 fleet 指标时 | — |
| DEF-007 | coding-agent-cli | `HttpMateToolClient.invokeTool(tool, args, credentials)` 填充真实 Mate 调用（工具执行 RPC）。PR #144 已实现两步 listTools（`queryToolIdsByAgentId` / `queryToolIdsBySkillId` / `queryToolMetaByIds`，经 `MateRestUtil` 调网关），仅剩 invoke：POST 网关执行接口并透传 `MateCredentials`（agent 下发）。 | Mate 工具执行接口（路径/入参/凭据放法）确定后 | — |
