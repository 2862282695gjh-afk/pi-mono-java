# Agent 与 Skill 运行时加载 FMEA、DFX 与安全设计评审

> 评审基线：[`agent-skill-runtime.md`](agent-skill-runtime.md) 及当前 CampusClaw 实现。
> 评审日期：2026-08-24。
> 适用形态：CampusMateService 与 CampusClaw 分 Pod 部署，调用方通过 REST/WebSocket 传入 `agentId`，CampusClaw 使用本地优先的 Agent/Skill 运行时目录。
> 本文是风险分析和验收基线，不替代接口 OpenAPI、ADR 或详细实现设计。

## 1. 结论摘要

当前设计能够完成以下主流程：

1. 根据 `agentId` 定位本地 Agent 目录；
2. 本地不可加载时调用 GetAgentRuntime；
3. 根据 Agent 直接绑定的 `(skillId, version)` 逐个调用 querySkillInfo；
4. 写入 `agentId.json`、`systemPrompt.md`、`setting.json`、`SKILL.md`、`references/tools.json`、references 和 templates；
5. 会话加载 Skill 名称、描述和本地工具快照；
6. LLM 调用 `activate_skill(skillName)` 后，在下一模型轮获得相应工具；
7. turn 结束后恢复 Agent 基础工具。

在单用户、本地 CLI、可信文件目录的试点环境中，该流程基本可用。在双 Pod、REST/WebSocket、共享存储或跨信任域生产环境中，以下问题属于上线前阻断项：

- 远端 Skill/资源字段参与路径构造，缺少完整的路径边界和符号链接防护；
- `tools.json` 保存全部工具是合理的元数据快照行为，但不能等价为全部工具已获执行授权；
- Skill 工具当前主要按名称匹配本地实现，稳定工具身份和执行时授权不足；
- 本地缓存缺少 revision/TTL/失效通知，Skill 禁用、解绑和工具撤权不能及时生效；
- 直接在目标目录重建，不能保证崩溃、多 Pod 并发和磁盘故障下的一致性；
- 设计文档中同时存在“暂缓加固”和“已完成原子、防篡改校验”两种描述，必须先收敛当前态与目标态。

## 2. 评审范围与假设

| 项目 | 本文假设 |
|---|---|
| 权威数据源 | CampusMateService 是 Agent、Skill、绑定关系和工具元数据的权威源 |
| 运行执行方 | CampusClaw 负责 Agent 会话、Skill 发现和激活 |
| 本地缓存 | `./agent/{agentId}/.campusclaw` 是运行时缓存，不应成为新的配置权威源 |
| Skill 工具快照 | querySkillInfo 返回的全部 `bindingTools` 均写入 `references/tools.json`，不因 `permission` 过滤 |
| 工具授权 | “写入全部工具元数据”与“允许执行工具”是两个独立阶段；授权必须在执行时生效 |
| 工具执行目标 | 目标架构应由 Tool Client 按稳定 `tool_id/version` 调用；本地 ToolCatalog 只负责可见性、描述和客户端适配 |
| 本地修改 | 若允许修改 `systemPrompt.md` 或 `SKILL.md`，必须把本地写权限明确纳入信任边界 |
| 部署 | 至少考虑 CampusClaw 多副本或共享 PVC，而不能只按单 JVM 锁设计 |
| 风险评分 | 严重度 S、发生度 O、检测难度 D 均为 1～10，数字越大风险越高；`RPN=S×O×D` |

## 3. 业务对象及关联

| 业务对象 | 关键标识 | 上游来源 | 本地表现 | 主要关联 |
|---|---|---|---|---|
| Agent | `agentId`、`version/revision` | GetAgentRuntime | Agent 根目录、`agentId.json` | 绑定模型、Skill、Agent 级工具、systemPrompt |
| AgentBindingSkill | `agentId + skillId + skillVersion` | GetAgentRuntime.bindingSkills | 应体现在运行时 manifest | 连接 Agent 与直接绑定 Skill |
| Skill | `skillId + version + name` | querySkillInfo | Skill 目录、`SKILL.md` | 绑定工具、依赖 Skill、references、templates |
| SkillBindingTool | `skillId + toolId + toolVersion` | querySkillInfo.bindingTools | `references/tools.json` | 连接 Skill 与工具元数据 |
| Tool | `toolId + version`，name 仅作展示 | CampusMate/Tool Client | ToolCatalog schema/客户端代理 | 被 Agent 或 Skill 授权并执行 |
| AgentRuntimeSnapshot | `agentId + revision` | CampusClaw 物化 | `.campusclaw` generation | 包含 Agent、Skill 和工具快照 |
| AgentSession | `agentId + conversationId` | REST/WebSocket/CLI | 内存状态和会话文件 | 固定使用某一 Runtime revision |
| systemPrompt | Agent revision 的组成部分 | GetAgentRuntime | `systemPrompt.md` | 参与 Agent 系统提示词 |
| Skill 指令 | Skill ID/version 的组成部分 | querySkillInfo.result.content | `SKILL.md` | 发现时暴露头信息，激活时加载正文 |
| Skill 资源 | Skill ID/version 的组成部分 | querySkillInfo | references/templates | 为 Skill 指令提供补充内容 |
| ToolPolicy | Agent/Skill/租户/审批策略 | CampusMate 和 CampusClaw | 不应只存在于 prompt | 在实际 Tool dispatch 时强制执行 |

### 3.1 必须保持的业务不变量

| 编号 | 业务不变量 | 违反后的风险 |
|---|---|---|
| INV-01 | 请求中的 `agentId` 必须与响应 Agent ID、本地目录和会话键一致 | 串用其他 Agent 的 Prompt、Skill 或历史 |
| INV-02 | 每个直接绑定必须精确对应唯一 `(skillId, version, name)` | 加载错误版本或同名 Skill |
| INV-03 | 一个 Runtime revision 内的 Agent 元数据、Skill、tools 和资源必须整体一致 | 混合版本和权限漂移 |
| INV-04 | `tools.json` 可保存全部工具元数据，但不能单独授予执行权限 | 敏感工具越权执行 |
| INV-05 | 工具执行身份必须以 `toolId/version` 为准，name 只用于展示 | 同名工具替换或误调用 |
| INV-06 | 一个 AgentSession 在一个 turn 内固定 Runtime revision | 执行中读取到被替换的文件 |
| INV-07 | Skill/工具禁用或撤权必须在约定时间内失效 | 已撤权能力继续可用 |
| INV-08 | 本地缓存之外的路径永远不能被物化、清理或覆盖 | 文件系统越界写删 |
| INV-09 | 敏感操作必须经过执行时授权、风险策略和必要的用户审批 | 仅靠模型提示无法构成安全控制 |

## 4. 业务对象关联的 FMEA

### 4.1 Agent 与绑定关系

| ID | 对象/关联 | 故障模式 | 主要原因 | 业务影响 | 当前检测 | S/O/D | RPN | 建议控制 |
|---|---|---|---|---|---|---:|---:|---|
| AG-01 | 调用方→Agent | 调用方传入无权使用的 `agentId` | 只有格式校验，没有主体到 Agent 的授权 | 越权使用其他 Agent 的数据和能力 | 弱 | 10/5/9 | 450 | 在网关/CampusMate 校验调用方、租户和 Agent 授权 |
| AG-02 | Agent 请求→响应 | GetAgentRuntime 返回错误 Agent | 服务端数据或路由错误 | 加载其他 Agent 的 Prompt、Skill、工具 | 弱 | 9/4/8 | 288 | 强制响应 `id==请求agentId` |
| AG-03 | Agent 元数据 | Agent 版本变化但缓存不刷新 | 无 revision、TTL 或失效通知 | 长期使用旧 Prompt、模型和绑定 | 弱 | 9/7/8 | 504 | 引入 Runtime revision 和失效协议 |
| AG-04 | Agent→Model | 默认模型为空、禁用或不存在 | bindingModels 无校验或本地 Provider 不支持 | Agent 启动失败或错误回退 | 中 | 6/4/4 | 96 | 物化前校验模型，定义明确回退策略 |
| AB-01 | Agent→Skill | Agent 已解绑 Skill，本地仍保留 | 本地命中后不访问 CampusMate | 已解绑 Skill 继续被发现和激活 | 弱 | 9/7/8 | 504 | revision、精确绑定 manifest、刷新切代 |
| AB-02 | Agent→Skill | 新绑定 Skill 未出现在本地 | 缓存永久有效 | Agent 能力缺失 | 弱 | 7/7/7 | 343 | TTL、显式刷新或失效推送 |
| AB-03 | Agent→Skill | 重复 Skill ID/name 或大小写冲突 | 响应形状缺少唯一性校验 | 目录覆盖、激活错误 Skill | 弱 | 8/4/8 | 256 | ID/name/case-fold name 全部唯一 |
| AB-04 | Agent→Skill | 绑定版本与 querySkillInfo 返回版本不同 | 只按 ID 查询但未校验返回坐标 | 静默使用错误 Skill 版本 | 弱 | 9/5/8 | 360 | 精确校验 ID/version |
| AB-05 | Agent→Skill | Skill 数量相同但对象已被替换 | 缓存只做数量级完整性判断 | 混合快照被误判完整 | 弱 | 9/6/8 | 432 | 比较绑定 ID/version/name 精确集合与 digest |

### 4.2 Skill、Prompt 与资源

| ID | 对象/关联 | 故障模式 | 主要原因 | 业务影响 | 当前检测 | S/O/D | RPN | 建议控制 |
|---|---|---|---|---|---|---:|---:|---|
| SK-01 | Skill 查询 | querySkillInfo result 缺失、null 或不是对象 | 数据不存在、旧数组契约或服务异常 | Agent 冷启动失败 | 强 | 7/4/2 | 56 | 保持 fail closed，配合契约监控和告警 |
| SK-02 | Skill 内容 | result.content 为空或不是有效 SKILL.md | Skill 定义不完整 | 空 SKILL.md 已落盘但无法发现，Runtime 重复物化 | 中 | 8/4/5 | 160 | 允许原样写入；通过后置 SkillLoader 校验暴露配置问题 |
| SK-03 | Skill | Skill name 包含路径字符或保留名 | 远端字符串直接参与路径构造 | 越界写入或目录覆盖 | 弱 | 10/5/9 | 450 | 单路径段白名单、safe resolve、保留名拒绝 |
| SK-04 | Skill | 真实 Skill 指令正文缺失 | 接口仅返回 description/useCases | Skill 只能执行简化流程 | 可见 | 6/9/3 | 162 | 返回正文、签名制品或下载 URL |
| SK-05 | Skill→依赖 Skill | 依赖未加载、循环或版本不闭合 | 当前只保存依赖元数据 | Skill 行为缺失或递归失控 | 中 | 5/5/5 | 125 | 不支持时明确拒绝；支持时做有向无环闭包 |
| SP-01 | Agent→systemPrompt | CampusMate 返回恶意 systemPrompt | 服务身份或数据源被攻击 | 提示注入、诱导敏感调用 | 弱 | 9/4/9 | 324 | mTLS/签名、大小限制、内容审计 |
| SP-02 | 本地 systemPrompt | 缓存目录可被其他主体修改 | 权限过宽或共享卷 | 持久化提示注入 | 弱 | 9/5/9 | 405 | owner-only、只读 generation、租户隔离 |
| SM-01 | Skill→SKILL.md | frontmatter 缺少 name/description | 响应异常或写入中断 | Skill 无法发现 | 中 | 6/4/3 | 72 | 写后使用 SkillLoader 复验 |
| SM-02 | SKILL.md→绑定 | name 与绑定、目录不一致 | 无稳定 manifest | 激活对象与授权对象错位 | 弱 | 9/5/8 | 360 | 保存并校验 skillId/version/name/digest |
| SM-03 | 本地 SKILL.md | 正文被篡改 | 本地目录可写、无 digest | 持久化提示注入 | 弱 | 9/5/9 | 405 | 内容摘要/签名，服务模式下禁止任意修改 |
| RS-01 | Skill→资源 | 资源名或 fileType 形成越界路径 | 未做白名单和 containment | 覆盖任意可写文件 | 弱 | 10/5/9 | 资源名单段白名单，类型仅允许明确集合 |
| RS-02 | Skill→资源 | 文件数量/体积过大 | 无对象级配额 | 内存、磁盘或 inode 耗尽 | 中 | 7/5/6 | 210 | 文件数、单文件、Skill 和 Agent 总量限制 |
| RS-03 | Skill→资源 | 重复目标或大小写冲突 | 只按原始名称去重 | 内容被覆盖、跨平台行为不同 | 弱 | 8/4/7 | 224 | 规范化后按 case-fold 去重 |

### 4.3 Skill 工具、ToolCatalog 与 Tool Client

| ID | 对象/关联 | 故障模式 | 主要原因 | 业务影响 | 当前检测 | S/O/D | RPN | 建议控制 |
|---|---|---|---|---|---|---:|---:|---|
| TF-01 | Skill→tools.json | 文件缺失或 JSON 损坏 | 写入中断或本地修改 | Skill 无法激活 | 强 | 7/4/3 | 84 | 初始化阶段验证，保留 last-known-good |
| TF-02 | tools.json→Tool | 本地加入额外工具 | tools.json 被篡改、无 manifest | Skill 获得未绑定能力 | 弱 | 10/5/9 | 450 | digest/签名及执行时授权 |
| TF-03 | SkillBindingTool→权限 | 全部保存被误解为全部允许执行 | 元数据与授权边界混合 | deny/ask/敏感工具被执行 | 弱 | 10/6/8 | 480 | 全量保存，但 dispatch 重新计算有效权限 |
| TF-04 | SkillBindingTool→Tool | 只按 name 匹配，忽略 ID/version | 本地 ToolCatalog 是 name 索引 | 执行错误或同名替代工具 | 弱 | 9/5/8 | 360 | Tool Client 按 toolId/version 调用 |
| TF-05 | SkillBindingTool | 重复 toolId/name/version | 响应无重复检测 | 权限覆盖、结果不确定 | 弱 | 7/4/7 | 196 | 物化前拒绝重复工具坐标 |
| TF-06 | SkillBindingTool | 工具撤权后旧快照继续使用 | 无 revision/TTL | 已撤权能力继续执行 | 弱 | 10/7/8 | 560 | 工具策略 revision；敏感调用前在线复核 |
| TC-01 | ToolCatalog | 同名工具来自多个实现 | 多 Source 或 extension 冲突 | 执行错误来源 | 中 | 9/4/6 | 216 | 敏感工具拒绝同名；稳定 ID 映射 |
| TC-02 | ToolCatalog→Tool Client | 实际执行仍由本地 AgentTool 完成 | Tool Client 契约未闭环 | 权限、版本和审计逻辑分散 | 中 | 9/7/5 | 315 | ToolCatalog 只提供 schema/代理，执行统一走 Tool Client |
| TC-03 | Tool dispatch | 只在 schema 中隐藏工具 | 伪造或重放 tool call | 绕过模型可见性限制 | 弱 | 10/4/9 | 360 | 每次 dispatch 校验 Agent、Skill、Tool、revision |
| AU-01 | ToolPolicy→审批 | `ask` 没有用户审批协议 | 交互协议未完成 | 误放行或业务能力不可用 | 中 | 9/5/5 | 225 | 明确 allow/deny/ask 状态机和审批证据 |

### 4.4 Runtime、Session 与接口

| ID | 对象/关联 | 故障模式 | 主要原因 | 业务影响 | 当前检测 | S/O/D | RPN | 建议控制 |
|---|---|---|---|---|---|---:|---:|---|
| RT-01 | RuntimeSnapshot | 原地删除并重建 skills | 无 staging generation | 半成品、正在运行会话读失败 | 中 | 8/6/5 | 240 | 临时 generation、复验、原子切换 |
| RT-02 | RuntimeSnapshot | Agent/Skill/工具来自不同版本 | 写入中断或旧文件残留 | Prompt 与权限不一致 | 弱 | 9/5/8 | 360 | 单 revision manifest 和整体发布 |
| RT-03 | RuntimeSnapshot→文件系统 | 中间目录是符号链接 | 缺少逐层 NOFOLLOW 检查 | 写删 agents-root 外文件 | 弱 | 10/4/9 | 360 | 逐层拒绝 symlink 或使用安全目录句柄 |
| RT-04 | RuntimeSnapshot | 磁盘满、只读或配额不足 | 容量不足 | 冷启动失败并留下部分目录 | 中 | 7/5/4 | 140 | 配额、空间监控、清理 staging、保留旧代 |
| RT-05 | 多 Pod→Runtime | 同时物化同一 Agent | JVM 锁不能跨 Pod | 文件互删或混合快照 | 弱 | 8/4/8 | 256 | 单写者、文件锁或 Pod 独立缓存 |
| RT-06 | agentId→锁 | 大量不同 agentId 造成锁表增长 | 锁对象不回收、调用不限流 | 内存和下游请求 DoS | 弱 | 6/6/6 | 216 | 认证限流、锁清理、Agent 数量配额 |
| SE-01 | AgentSession | 相同 conversationId 跨 Agent 复用 | 会话键缺少 agentId | 数据和上下文串用 | 强 | 9/2/2 | 36 | 保持 `(agentId, conversationId)` 复合键 |
| SE-02 | Session→Runtime | turn 中 Runtime 被替换 | Session 未固定 generation | 同一任务读取混合内容 | 弱 | 8/4/8 | 256 | turn 固定 revision/generation |
| SE-03 | Skill 激活 | 工具激活失败但部分集合已更新 | 非原子更新 | 残留权限 | 强 | 9/3/3 | 81 | 先完整解析，再一次性 setTools |
| SE-04 | turn 生命周期 | Skill 工具未在异常/取消后恢复 | finally 路径不完整 | 后续任务继承 Skill 权限 | 中 | 8/3/4 | 96 | 所有完成、异常、取消路径恢复并测试 |
| API-01 | CampusMate API | 超时或不可用 | 网络或服务故障 | 未缓存 Agent 无法启动 | 中 | 7/6/3 | 126 | 有界重试、退避、熔断、最后可用缓存 |
| API-02 | Agent→N Skills | querySkillInfo N+1 顺序调用 | 每个 Skill 一个请求 | 冷启动延迟线性增加 | 中 | 6/7/4 | 168 | 批量接口或小规模有界并发 |
| API-03 | CampusMate→CampusClaw | 服务身份未认证或明文传输 | HTTP、无 mTLS/签名 | 供应链式 Prompt/工具注入 | 弱 | 10/4/9 | 360 | mTLS、JWT/服务签名、NetworkPolicy |
| API-04 | skillId→URL | 未编码/校验直接拼接 | 远端 ID 进入 URL | 请求错误路径或越权查询 | 弱 | 8/3/8 | 192 | Skill ID 单段校验和安全 URI 构造 |

## 5. 可靠性功能规范分析

下表中的指标是建议的初始验收目标，最终数值需通过容量测试确认。

| 规范 ID | 可靠性功能规范 | 当前状态 | 目标行为/失败策略 | 建议验收指标 | 必测场景 |
|---|---|---|---|---|---|
| REL-01 | Agent 身份一致性 | 部分 | 请求、响应、本地目录、会话键的 agentId 必须一致，否则 fail closed | 错配检出率 100% | 错误响应 ID、大小写、空值、穿越值 |
| REL-02 | Skill 坐标一致性 | 不足 | querySkillInfo result 必须为单对象，且 ID/version/name 与直接绑定一致 | 错配检出率 100% | null、数组、版本错、重复 ID/name |
| REL-03 | Runtime 原子发布 | 未满足 | 新 generation 写全、复验成功后才能成为 current | 故障注入后 current 永远可加载 | 每个写入步骤崩溃、磁盘满、权限失败 |
| REL-04 | Last-known-good | 未满足 | 新版本失败时继续使用上一有效 revision，除非已紧急撤权 | 回滚成功率 100% | 新快照损坏、CampusMate 5xx、超时 |
| REL-05 | 撤权新鲜度 | 未满足 | 禁用、解绑、工具撤权在约定窗口内生效 | 普通权限 ≤5 分钟；高危权限 ≤30 秒或执行前复核 | TTL、推送丢失、revision 冲突 |
| REL-06 | 冷启动幂等性 | 部分 | 同一 Agent/revision 并发准备只发布一个相同结果 | 同 JVM/多 Pod 无混合文件 | 100 并发 prepare、多 Pod 共享卷 |
| REL-07 | 会话快照固定 | 未满足 | 一个 turn 始终使用同一 revision | 混合 revision 次数为 0 | turn 中刷新、删除、Pod 重建 |
| REL-08 | Skill 激活原子性 | 基本满足 | 工具和正文全部可用才更新 AgentState；失败不改变集合 | 失败后工具集合变化次数为 0 | 工具缺失、SKILL.md 损坏、取消 |
| REL-09 | turn 权限恢复 | 部分 | 成功、异常、超时、取消后都恢复 baseTools | 恢复成功率 100% | LLM 异常、tool 异常、客户端断连 |
| REL-10 | 下游故障隔离 | 部分 | 超时、有界重试、熔断，避免请求和线程堆积 | 超时请求不超过配置；熔断可恢复 | 网络丢包、长尾、5xx、半开状态 |
| REL-11 | 容量保护 | 不足 | 限制 Agent/Skill/资源/工具数量和字节数 | 超限请求 100% fail closed 且不发布 | 大 JSON、多小文件、超大 content |
| REL-12 | 缓存恢复 | 部分 | 可诊断、可失效、可隔离坏 revision、可回滚 | 运维操作无需删除整个 agents-root | 单 Agent invalidate、quarantine、rollback |
| REL-13 | 数据持久化耐久性 | 未明确 | manifest 和 current 指针按需要 fsync，发布不跨文件系统 | 宕机后 current 指向完整 generation | kill -9、节点掉电模拟 |
| REL-14 | 双实现一致性 | 风险存在 | modules 与 mate-campusclaw 的核心契约和测试保持一致 | 关键文件/契约差异为 0 | 镜像一致性 CI、契约测试复用 |

## 6. 安全设计确认

### 6.1 信任边界确认

| 边界 | 输入 | 当前确认 | 风险 | 必须的安全控制 |
|---|---|---|---|---|
| 调用方→CampusClaw | agentId、conversationId、用户消息 | 仅部分确认 | 越权选择 Agent、会话枚举 | 身份认证、Agent/租户授权、限流 |
| CampusMate→CampusClaw | Agent、Skill、Prompt、资源、工具元数据 | 未完成 | 供应链式注入和路径攻击 | mTLS/签名、字段白名单、revision |
| CampusClaw→本地文件系统 | 目录创建、写入、删除、切换 | 未完成 | 越界写删、符号链接逃逸、竞态 | safe resolve、NOFOLLOW、generation 发布 |
| 本地文件系统→AgentSession | systemPrompt、SKILL.md、tools.json | 未完成 | 持久化 Prompt/工具注入 | 只读快照、digest、owner-only |
| LLM→activate_skill | skillName | 部分确认 | 激活未授权或伪造 Skill | 精确绑定、结构化参数、会话级授权 |
| LLM→Tool dispatch | tool call 和参数 | 未完成 | 绕过 schema、执行敏感操作 | 执行时权限、审批、参数策略、审计 |
| CampusClaw→Tool Client | toolId、版本、参数、凭据 | 目标尚未闭环 | 工具身份错配、权限分散 | 稳定 ID、短期凭据、幂等键、审计 |

### 6.2 安全设计检查表

| 检查项 | 状态 | 确认结论 | 发布要求 |
|---|---|---|---|
| agentId 单路径段校验 | 已具备 | 可阻止直接 `../`，但不能阻止中间 symlink | 保留并补 agents-root 真实边界校验 |
| Skill/资源名白名单 | 缺失/不完整 | 远端字段不能直接作为路径 | P0 |
| 符号链接防护 | 缺失/不完整 | 必须逐级拒绝，不只检查最终文件 | P0 |
| 原子快照发布 | 缺失 | 当前自愈不能替代原子一致性 | P0 |
| CampusMate 服务认证 | 未明确 | 默认 HTTP 不适合作为跨信任域生产通道 | P0 |
| 调用方 Agent 授权 | 未明确 | 知道 agentId 不应等于有权调用 | P0 |
| tools.json 全量持久化 | 符合业务要求 | 可完整记录元数据，但不是授权凭据 | 保留 |
| Skill 工具执行权限 | 缺失/不完整 | 必须应用 Agent/租户上限和敏感策略 | P0 |
| ask/deny 审批协议 | 未完成 | ask 未获批准应 fail closed | P0/P1 |
| 稳定 Tool 身份 | 未完成 | name 不足以保证执行同一工具 | P0 |
| Prompt/Skill 防篡改 | 暂缓 | 共享目录或服务端部署风险高 | P0 |
| 租户数据隔离 | 未明确 | Agent 目录、会话和 Tool 凭据都需租户维度 | P0 |
| 密钥管理 | 未明确 | 凭据不得写入 Agent 快照或普通日志 | P0 |
| 审计完整性 | 不足 | 需关联 Agent、Skill、revision 和 Tool | P1 |
| 依赖 Skill 安全 | 未定义 | 不支持时不得静默提升为可见 Skill | P1 |

## 7. 敏感操作检查

### 7.1 CampusClaw 内部文件操作

| 操作 | 风险等级 | 风险 | 设计要求 |
|---|---|---|---|
| 创建 Agent/Skill 目录 | 中 | 路径越界、权限过宽 | safe resolve、owner-only、配额 |
| 删除旧 `skills/` | 高 | 删除错误目录、影响运行中 Session | 禁止原地递归删除；删除非 current generation |
| 写 systemPrompt/SKILL.md | 高 | 持久化 Prompt 注入 | 认证来源、摘要、原子写入 |
| 写 tools.json | 高 | 持久化能力注入 | 保留全部元数据但签名/摘要；不直接授权 |
| 写 references/templates | 中/高 | 路径穿越、磁盘耗尽、内容注入 | 名称/类型白名单、字节和数量限制 |
| 切换 Runtime revision | 高 | 会话读到混合版本 | 原子 current 指针、Session generation pinning |
| 清理 staging/旧 generation | 中 | 误删 current 或仍被 Session 使用的版本 | 引用计数/租约、保留最近 N 代 |

### 7.2 Tool 执行敏感度

| 类别 | 示例 | 默认风险 | 最低控制 |
|---|---|---|---|
| 只读文件/查询 | read、grep、glob、业务查询 | 中 | 数据域限制、路径限制、结果脱敏 |
| 文件修改 | write、edit、EditDiff | 高 | Agent 明确授权、用户审批、目录 allowlist |
| 进程和系统操作 | bash、命令执行 | 极高 | 托管 Agent 默认拒绝；必要时强审批和隔离 |
| 子 Agent 委派 | spawn_agent | 高 | bindingAgent allowlist、委派深度≤2、预算和审计 |
| 外部业务写操作 | 创建、修改、删除业务数据 | 高/极高 | Tool Client 权限、幂等键、二次确认、补偿能力 |
| 通知和消息发送 | 邮件、IM、工单 | 高 | 收件人/渠道校验、预览确认、防批量发送 |
| 定时任务 | cron/schedule | 高 | 所有者、有效期、取消入口、审批和审计 |
| 凭据/密钥访问 | token、证书、账号信息 | 极高 | 禁止进入模型上下文；短期凭据和最小权限 |

敏感工具的授权必须在 Tool dispatch 或 Tool Client 中执行，不能仅依赖系统提示词、工具 schema 是否可见或 LLM 是否遵循约束。

## 8. 业务安全性分析

| 业务安全目标 | 主要威胁 | 当前风险 | 推荐规则 | 验收方式 |
|---|---|---|---|---|
| Agent 使用授权 | 猜测或盗用 agentId | 高 | 调用主体只能使用租户内显式授权 Agent | 跨租户和越权调用测试 |
| Skill 绑定真实性 | 本地额外 Skill 或绑定漂移 | 高 | 可选 Skill 精确等于 Agent revision 的直接绑定集合 | 注入额外目录、解绑测试 |
| 工具最小权限 | Skill 工具扩大 Agent 权限 | 极高 | 全量元数据存储，执行权限取多策略交集 | deny/ask/敏感工具测试 |
| 权限及时撤销 | 旧 tools.json 继续生效 | 极高 | 高危工具执行前复核 revision；普通权限受 TTL 约束 | 撤权并发和缓存窗口测试 |
| 业务写幂等 | LLM 重试造成重复写入 | 高 | 每次业务写带 conversation/turn/tool-call 幂等键 | 超时重试和重复请求测试 |
| 操作不可抵赖 | 无法定位谁让哪个 Skill 调了工具 | 高 | 完整审计主体、Agent、Skill、Tool、revision | 审计关联性检查 |
| 数据最小暴露 | Skill 或工具读取无关租户数据 | 高 | Tool Client 注入租户上下文，禁止模型覆盖 | 参数篡改和跨租户测试 |
| 高风险确认 | LLM 自动执行删除、支付、发送 | 极高 | 风险分类和用户显式确认 | 模拟审批拒绝、超时、重放 |
| 子 Agent 权限传递 | 委派后权限扩大 | 高 | 子 Agent 权限不超过父任务委派能力和自身策略 | 两层委派越权测试 |
| 本地缓存机密性 | Prompt/resources 含敏感信息 | 中/高 | 不落凭据；目录 owner-only；备份和日志脱敏 | 权限、备份和日志扫描 |

## 9. 可服务性分析

### 9.1 诊断与可观测性

| 类型 | 建议字段/指标 | 用途 | 告警建议 |
|---|---|---|---|
| Runtime 准备事件 | agentId、requestedRevision、source(local/remote)、result、duration | 判断缓存命中和冷启动失败 | 5 分钟失败率超过 2% |
| Skill 查询事件 | agentId、skillId、version、HTTP/业务码、duration | 定位 CampusMate 或数据问题 | 连续失败、版本错配立即告警 |
| 物化事件 | generation、Skill 数、文件数、字节数、publishResult | 诊断磁盘和一致性问题 | 发布失败、回滚立即告警 |
| 缓存指标 | hit/miss/stale/invalid/rematerialize | 判断缓存策略效果 | stale 超过 TTL 或 rematerialize 激增 |
| Skill 激活事件 | agentId、skillId/name、revision、requestedToolCount、result | 诊断发现和工具缺失 | 敏感 Skill 激活失败或异常升高 |
| Tool 授权事件 | toolId/version、policyResult、approvalId、denyReason | 安全审计 | 越权/重复重放立即告警 |
| Tool 执行事件 | correlationId、duration、result、idempotencyKey | 业务追踪和性能分析 | 高危工具失败或超时 |
| 容量指标 | Agent/Skill/文件数、缓存字节、inode、staging 数 | 防止磁盘耗尽 | 磁盘>75%、inode>70%、staging 滞留 |
| 并发指标 | prepare in-flight、session 数、tool in-flight | 容量和锁竞争 | 队列或等待时间超过目标 |

日志不得记录完整 systemPrompt、SKILL.md、业务敏感参数、凭据或 Tool 返回的隐私正文。建议使用以下关联键：

```text
tenantId / callerId / agentId / runtimeRevision
conversationId / turnId / skillId / skillVersion
toolId / toolVersion / toolCallId / approvalId / correlationId
```

### 9.2 运维操作

| 运维能力 | 当前缺口 | 建议行为 | 安全要求 |
|---|---|---|---|
| 查看 Agent Runtime 状态 | 缺统一状态接口 | 展示 current revision、来源、文件数、最后刷新和错误 | 不返回 Prompt/凭据正文 |
| 刷新单 Agent | 缺明确入口 | 拉取新 revision，验证后原子切换 | 管理员鉴权、审计 |
| 失效单 Agent 缓存 | 可能依赖手工删目录 | 标记失效，下一安全边界刷新 | 不直接递归删除 current |
| 隔离坏快照 | 缺少 quarantine | 将坏 generation 移入隔离区并保留诊断信息 | 限制容量和保留期 |
| 回滚上一 revision | 未提供 | 原子切回 last-known-good | 紧急撤权版本禁止回滚 |
| 清理旧 generation | 未提供 | 保留当前、上一有效和仍被 Session 引用版本 | 防误删和竞态 |
| 校验本地缓存 | 校验不足 | 离线验证 manifest、digest、权限和路径 | 只读执行，不触发物化 |
| 导出诊断包 | 未提供 | 导出元数据、指标、错误，不含敏感正文 | 脱敏、访问审计 |

### 9.3 建议故障码

| 故障码 | 含义 | 是否重试 | 运维动作 |
|---|---|---|---|
| `AGENT_NOT_AUTHORIZED` | 调用方无 Agent 权限 | 否 | 检查授权关系 |
| `RUNTIME_NOT_FOUND` | CampusMate 不存在该 Agent | 否 | 检查 Agent 配置 |
| `RUNTIME_STALE` | 本地 revision 已过期 | 可 | 触发刷新 |
| `RUNTIME_INTEGRITY_FAILED` | manifest/digest/路径校验失败 | 否 | 隔离快照并告警 |
| `RUNTIME_PUBLISH_FAILED` | 原子发布失败 | 可 | 保留旧版，检查磁盘和权限 |
| `SKILL_COORDINATE_MISMATCH` | Skill ID/version/name 不匹配 | 否 | 修复 CampusMate 数据 |
| `SKILL_CONTENT_INVALID` | SKILL.md/resources 不合法 | 否 | 修复 Skill 定义 |
| `SKILL_TOOL_NOT_AVAILABLE` | 本地/Tool Client 没有工具 | 可选 | 检查部署和工具注册 |
| `TOOL_NOT_AUTHORIZED` | 工具不满足策略 | 否 | 检查绑定、审批和租户策略 |
| `TOOL_APPROVAL_REQUIRED` | 敏感操作需要审批 | 用户决定 | 返回审批请求 |
| `CAMPUSMATE_UNAVAILABLE` | CampusMate 超时或熔断 | 可 | 使用 LKG 或稍后重试 |
| `RUNTIME_CAPACITY_EXCEEDED` | 数量、字节或 inode 超限 | 否 | 调整定义或配额 |

## 10. 性能设计分析

### 10.1 关键路径

| 场景 | 当前复杂度 | 主要瓶颈 | 建议初始目标 | 优化方向 |
|---|---|---|---|---|
| 本地缓存命中 prepare | `O(S+F)` 目录/文件检查 | 文件系统元数据和解析 | P95 ≤50ms | revision manifest，避免全目录扫描 |
| 冷启动 Agent | `1 + N` 个 HTTP 请求 | querySkillInfo N+1 和串行延迟 | P95 ≤2s（常规 Agent） | 批量接口或并发度 4 的有界并发 |
| Runtime 物化 | `O(F+B)` | 小文件数、JSON 序列化、磁盘同步 | P95 ≤500ms，不含网络 | generation、批量写、合理 fsync |
| Session 初始化 | `O(S+T)` | SKILL/frontmatter/tools 解析 | P95 ≤100ms | 复用不可变 Prepared snapshot |
| activate_skill | `O(T)` | 工具身份解析和 SKILL.md 读取 | P95 ≤50ms | 会话内缓存正文和 Tool schema |
| Tool 授权 | `O(1)` 或小集合交集 | 策略和审批查询 | P95 ≤20ms（无审批） | toolId 索引和 revision 策略缓存 |
| Tool Client 调用 | 取决于业务工具 | 网络和下游业务系统 | 按工具独立 SLO | 超时、幂等、熔断、隔离线程池 |

### 10.2 容量与资源预算

以下是建议默认上限，不代表当前实现已具备：

| 资源 | 建议默认上限 | 超限行为 |
|---|---:|---|
| 单 Agent 直接绑定 Skill | 128 | 拒绝新 revision，不替换 current |
| 单 Skill 工具数 | 128 | fail closed，并指出 Skill ID |
| 单 Skill references/templates 文件数 | 每类 256 | fail closed |
| 单资源正文 | 1 MiB UTF-8 | fail closed |
| 单 Skill 总资源 | 8 MiB | fail closed |
| 单 Agent Runtime 总量 | 64 MiB | fail closed |
| CampusMate 单响应体 | 4 MiB，按接口分别配置 | 读取超过上限立即终止 |
| 同一 Agent 冷启动并发 | 单飞 single-flight | 复用同一准备结果 |
| querySkillInfo 并发 | 4 | 有界排队，禁止无界并发 |
| 保留 Runtime generation | 当前+上一有效+被 Session 引用版本 | 后台安全清理 |

### 10.3 性能风险和测试

| 风险 | 表现 | 测试方法 | 通过标准 |
|---|---|---|---|
| N+1 长尾 | Skill 数增加后冷启动线性变慢 | 1/10/50/128 Skill 阶梯压测 | 延迟满足目标，无线程堆积 |
| 小文件风暴 | inode 和目录扫描变慢 | 每 Skill 256 资源压力测试 | 超限被拒，正常规模稳定 |
| 重复物化风暴 | CampusMate 故障或半成品导致循环拉取 | 故障注入+并发请求 | single-flight、退避、无请求放大 |
| 多 Pod 竞争 | 同一 Agent 同时写入 | 多实例共享卷压力测试 | current 永远完整，无混合版本 |
| 工具 schema 膨胀 | Skill 工具过多增加 token 和延迟 | 1/32/128 工具模型调用测试 | 上下文大小受控，超限有明确错误 |
| 缓存持续增长 | Agent/revision 不清理 | 长稳 7 天模拟 | 磁盘、inode 和内存均在预算内 |
| 慢 Tool Client | 会话和执行线程堆积 | 下游延迟/超时注入 | 隔离、超时和熔断有效 |

## 11. 验证与测试矩阵

| 类别 | 必测用例 | 预期结果 |
|---|---|---|
| 身份和授权 | 非法 agentId、跨租户 agentId、响应 ID 不一致 | 请求被拒绝且不创建目录 |
| 路径安全 | Skill/resource 的 `../`、绝对路径、反斜杠、控制字符、保留名 | 100% fail closed |
| 符号链接 | agentRoot、`.campusclaw`、skills、Skill、references、文件 symlink | 不读取、不写入、不删除边界外内容 |
| 响应形状 | result 为 null/数组、content 为空、重复 ID/name、版本错、未知 fileType | 空 content 先落盘，后置加载失败；其他非法响应不发布新 Runtime |
| 原子性 | 在每个写入点 kill -9、磁盘满、IOException | current 保持上一完整版本 |
| 多实例 | 两个 Pod 同时 prepare 同一 Agent | 仅一个有效 generation，无混合文件 |
| 缓存失效 | 禁用/解绑/工具撤权、revision 推送丢失 | 在规定窗口内失效 |
| 本地篡改 | 修改 systemPrompt、SKILL.md、tools.json、资源 | 服务模式检测并隔离；本地可编辑模式有明确策略 |
| Tool 身份 | 同名不同 ID/version、重复名称、工具缺失 | 不执行错误工具 |
| 权限 | Skill tools 包含 allow/ask/deny 和敏感工具 | 全量保存，但执行符合策略和审批 |
| Session 生命周期 | 激活后成功、失败、异常、取消、断连 | 工具集合始终恢复 baseTools |
| 性能 | 热命中、128 Skill、资源上限、并发冷启动 | 达到 §10 的目标 |
| 可恢复性 | 新 revision 失败、隔离、回滚、清理旧代 | LKG 可用且操作有审计 |
| 可观测性 | 每类失败触发一次 | 日志、指标、告警可关联到业务对象 |

## 12. 发布门槛与整改优先级

| 优先级 | 发布门槛 | 完成标准 |
|---|---|---|
| P0 | 收敛文档、ADR 和实际实现 | 当前态、目标态和暂缓项无互相矛盾描述 |
| P0 | 文件系统安全 | 全部远端路径字段白名单、containment、symlink 测试通过 |
| P0 | 原子 Runtime 发布 | staging+复验+原子切换+LKG 故障注入通过 |
| P0 | 工具执行授权 | 全量 tools.json 不直接授权；dispatch 强制策略交集 |
| P0 | 稳定工具身份 | Tool Client 按 toolId/version 调用，拒绝名称冲突 |
| P0 | 身份和租户授权 | 调用方→Agent、CampusClaw→CampusMate 均有认证授权 |
| P0 | 高风险操作治理 | bash/write/edit/删除/发送/业务写等有明确 deny/ask/allow 策略 |
| P1 | revision 与撤权 | Skill/工具变更可在目标窗口内失效 |
| P1 | 多 Pod 一致性 | 共享卷或独立缓存策略经并发验证 |
| P1 | 容量和故障隔离 | 配额、限流、超时、重试、熔断和 single-flight 完成 |
| P1 | 可服务性 | 指标、审计、刷新、隔离、回滚和清理入口完成 |
| P2 | Skill 正文和依赖 | 接口提供真实正文；依赖闭包规则明确 |
| P2 | 性能优化 | 批量 Skill 查询、Prepared snapshot 复用达到目标 SLO |

## 13. 最终判定

| 部署模式 | 当前建议 | 说明 |
|---|---|---|
| 单用户本地 CLI、目录仅用户本人可写 | 可受控试点 | 需要接受缓存漂移和本地修改风险 |
| 单 Pod 服务端、可信 CampusMate、独占磁盘 | 有条件试运行 | 至少完成路径安全、工具执行授权和审计 |
| 多 Pod、共享 PVC | 暂不建议生产放行 | 必须完成 generation、跨 Pod 一致性和 Session revision 固定 |
| 多租户或 CampusMate 跨信任域 | 不满足生产安全基线 | 必须完成 P0 全部项目及撤权时效控制 |

最关键的设计原则是：

```text
本地 Skill 文件是可验证缓存，不是新的配置权威源；
tools.json 是完整工具元数据快照，不是执行授权凭据；
模型可见工具集合不是安全边界，真正授权必须在 Tool dispatch/Tool Client 执行。
```
