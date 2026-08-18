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
| DEF-007 | coding-agent-cli | `HttpMateToolClient` 四个 Mate RPC stub 填充真实 HTTP 调用：`queryToolIdsByAgentId` / `queryToolIdsBySkillId` / `queryToolMetaByIds` / `invokeToolById`。签名与调用点已定，仅需填方法体。四个方法均接收 `MateCredentials`（AppKey: X-HW-ID+X-HW-APPKEY; JWT: X-HW-ID+Authorization Bearer）。 | Mate 工具服务内部接口确定后 | — |
| DEF-008 | coding-agent-cli | 托管 Agent 快照九条防篡改/防漂移规则的实现与测试（原 `AgentRuntimeManager` 校验链）：① agentId 路径穿越防护与 agents-root 边界（**兜底已就位**：`prepare()/prepareCached()` 入口按 OpenAPI 同款段格式正则拒绝穿越值，2026-08-18 评审后补齐；符号链接逃逸等完整边界校验仍暂缓）；② Agent 路径符号链接拒绝；③ 本地快照完整性校验（SKILL.md/references/templates/tools.json 内容比对、多余 Skill 目录、版本漂移、setting.json 缺失即 fail closed）；④ GetAgentRuntime/querySkillInfo 响应形状校验（id 匹配、版本坐标匹配、Skill 数量上限、资源大小上限、重复 id/name 检测）；⑤ 资源文件名/fileType 白名单；⑥ SKILL.md 可重建比对（提示注入持久化防御）；⑦ 临时目录 + 原子移动发布；⑧ 物化后复验；⑨ 元数据最后写入。当前语义：快照无法加载时直接重新物化覆盖（自愈），不做漂移检测。完整规则清单与取舍见 [ADR-0013](decisions/0013-defer-snapshot-hardening.html)。 | CampusMate 跨信任域部署或共享缓存目录多租户时必须恢复③⑤⑥；①的段格式兜底已就位，agents-root 边界的完整校验（含符号链接）随②一起恢复 | — |
