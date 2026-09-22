# Mate Tool Client 与 Session 发现设计

> 文档版本：3.1.0
>
> 状态：精确发现、注册与调用已实现；数据加载、同步、刷新和跨节点一致性待实现
>
> 更新日期：2026-09-22
> 决策记录：[ADR-0032](../decisions/0032-tool-execution-credential-boundary.html)、
> [ADR-0024](../decisions/0024-mate-tool-execution-credential-chain.html)、
> [ADR-0022](../decisions/0022-managed-agent-tool-system-v2.html)、
> [ADR-0026](../decisions/0026-unify-campusmate-client-configuration.html)

## 1. 源码基线与分层

精确发现、注册与调用源码核验基线为 `8e4d9914106aab8745e6cba801047af816ce5132`，变更前基线为 `ae6645caaade3b9520220a34af940210850ef36a`，设计依据为
`pi-mono-java-design@01c17dc9ee3dd1d847869066ba495ca42cfee658` 的工具主设计与内置工具契约。本次核验不扩大到数据加载、同步、
刷新、失效、跨节点一致性或版本切换设计。

以下路径相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`；标为仓库根的路径
从仓库根开始：

| 源码与符号 | 当前职责 |
|---|---|
| `tool/builtin/DefaultConfiguredToolAssembler#assemble` | 框架工具与 Agent 直接关联工具代理装配，空配置禁用全部工具 |
| `tool/skill/SkillTool#execute` | 按可信可见性索引定位 Skill，调用时校验并读取正文，抽样列出资源路径 |
| `tool/mate/MateToolsetFactory#createSession`、`MateToolSessionState` | 创建 Session 私有定义解析与执行状态 |
| `tool/mate/MateToolResolver#resolveAgentTools/#resolveSkill/#resolveDisclosableSkill` | 分别解析 Agent、直接 Skill 与模型可披露 Skill 的工具关联，缓存可信定义，拒绝名称冲突 |
| `tool/mate/SearchTools#execute` | 只接受精确技术名，返回单个完整 `name/description/inputSchema`，不注册或执行工具 |
| `tool/mate/MateToolAdapter#execute`、`CallTool#prepare`、`MateToolExecutor` | 两种执行入口共用可信解析、业务 Schema 校验、不可变准备快照和单次 Mate execute |
| （仓库根）`modules/agent-core/src/main/java/com/campusclaw/agent/tool/ToolDefinitionFormatter.java`、`ToolExecutionPipeline#invalidArgumentsResult` | 统一定义字段和超预算恢复提示边界 |
| `runtimeapi/runtime/RuntimeToolPermissionPolicy#decide` | 按实际入口的可信关联权限执行 Host 授权规则 |

关联定义候选工具范围，不等于调用授权。产品约束是保留 Mate 执行、租户及凭据边界；架构变更是
Agent 直接工具使用代理、Skill 工具按精确技术名渐进披露，不提供批量或模糊发现工具。

<a id="credential-source-evidence"></a>

### 1.1 Mate 凭据实现证据

以下路径相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`，均核对
`db990ab3358798743ce63668035ab98fc0554a32`；不将该提交扩展为其他工具和历史基线的重新验收。

| 源码与符号 | 已核验行为 |
|---|---|
| `runtimeapi/event/RuntimeEventService.java#submit` | 不拒绝空或部分凭据；null 归一化为空快照，仅消息分支传递快照，控制分支不替换原值 |
| `tool/mate/CallMateTool.java#execute` | 不检查凭据完整性；保留名称解析和取消检查，再调用 Mate |
| `common/client/HttpMateToolClient.java#invokeTool/#toHeaderInfo/#toToolResult/#readRoot` | 校验工具 ID 后按快照调用 execute；Mate 非零响应映射工具失败，解析异常不回显响应正文 |
| `common/util/MateRestUtil.java#newRequestBuilder/#logAndWrap` | 只省略 null 或空字符串 Header；敏感异常不保留原 message/cause，保留非敏感定位和来源栈 |
| `common/client/mate/MateCredentials.java`、`common/dto/RequestHeaderInfo.java#toHeaders` | 不可变四项快照不再提供完整性判断；按原 Header 名映射且诊断字符串不输出凭据值 |

同一提交的 129 项聚焦测试于 2026-09-19 通过，包括真实 HTTP 的 16 种 Header 子集、null/空值
省略、失败请求次数和日志脱敏。真实 Mate 部署联调、不同凭据并行执行与完整控制续段端到端
验证、公司父 POM 独立构建和全仓 `clean verify` 未完成，不属于该测试结论。

## 2. Agent 代理与 Skill 发现

`MateToolClient` 是共享无状态 HTTP 客户端。`MateToolResolver`、`MateToolExecutor`、工具实例及
定义缓存均由 `MateToolsetFactory` 为每个 Session 创建，不作为 Spring 单例复用。

Agent 直接关联的业务工具通过 `MateToolAdapter` 将真实 `name/description/inputSchema` 加入
初始模型工具集合。仅由 Skill 关联的工具不建立代理；二者重叠时保留 Agent 直接代理。

模型先看到 Skill 名称和描述，按需调用 `Skill({name})` 取得去除 frontmatter 的正文、基础目录
与最多 10 条抽样资源绝对路径。正文必须在调用时通过工作区边界校验并读取 UTF-8 文件；缺失、
不可读、目录或符号链接均走统一工具错误路径。空资源清单仍然成功。

正文引用业务工具技术名。模型需要定义时调用固定框架工具 `SearchTools({tool})`；它只在当前
Agent 直接绑定且模型可见的 Skill 工具范围内按大小写敏感的完整名称匹配，成功只返回一个完整
`name/description/inputSchema`。它不接收 Skill 名、查询词或分页参数，不解析 Markdown 猜名，
也不注册、激活或执行工具。`Read` 只读取普通资源，不生成或读取工具定义 JSON。

未知、未绑定、不可披露、绑定或元数据无效、同名冲突和无效 Schema 均明确失败。完整定义超过
结果预算时返回 `TOOL_DEFINITION_TOO_LARGE`，不截断 Schema，也不提示重复查询。代理、
`SearchTools` 和 `CallTool` 在当前 Session 复用同一可信名称解析和定义缓存，避免同次执行看到
不同定义；这不等同于数据源同步能力。数据加载策略、同步、刷新、失效、跨节点一致性和版本切换
仍待实现。

## 3. CallTool 与统一执行

![CallTool 的权限检查与单次执行](tool-system-v2/mate_call_refresh.svg)

[PlantUML 源码](tool-system-v2/diagram.puml#L128) · [原尺寸](tool-system-v2/mate_call_refresh.svg)

`CallTool({tool,args?})` 按名称调用当前 Agent 直接绑定 Skill 所关联的工具，`args` 缺省为 `{}`。
模型不提交 scope、Skill、toolId 或 URL；仅与 Agent 直接关联的工具和框架工具不在其范围内。
同名同 ID 可合并，同名不同 ID 或与框架重名必须明确失败；文件内容不扩大可信关联范围。

两种入口均经过 Pipeline 和 Host before hook。代理使用完整业务 Schema；`CallTool` 先校验
外层 `tool/args`，再按可信名称解析定义并校验业务参数，生成不可变 `PreparedToolInvocation`。
Host 随后按解析出的真实工具检查 allow、ask、deny 和必要确认，通过后执行同一准备快照。
顺序固定为“可信解析 → 参数校验 → 权限或必要确认 → 业务执行 → 结果回传”，Mate 仍是最终
授权和执行权威。

解析器以 Session 私有状态缓存 Agent 工具及已请求的 Skill 工具定义；按可信关联查询所需
Skill 元数据，不把整个 Agent 与全部 Skill 的刷新作为 Call 的前置条件。后台元数据查询不
向模型披露所有 Schema。实际 execute 只发送一次，超时、断线和业务失败不自动重放。

参数错误返回真实 `isError=true`、字段错误和同次校验使用的完整可信定义，不确认、不执行、
不猜参也不自动重放。若整份恢复结果超预算，只有当该业务工具属于 `SearchTools` 可披露范围且
单个完整定义能独立放入预算时，才提示按精确名称调用 `SearchTools`；框架工具、Agent-only
代理或单定义仍超限时只说明定义无法返回。无效业务 Schema 的底层异常由 `MateToolExecutor`
记录一次，包含 operation、errorCode、toolId 和原始 throwable；对外仍返回稳定失败文本且
不带 cause，不记录调用参数或执行凭据。

## 4. HTTP 契约与凭据链

Agent/Skill 查询先取得有序绑定 ID，再向元数据端点批量查询。客户端按 ID 关联结果并恢复
原绑定顺序；缺失、重复或未知 ID fail closed。`listAgentTools(agentId)`、
`listSkillTools(skillId)` 和内部元数据查询均不接收 `MateCredentials`，因此 Agent binding、
Skill binding 和 tool metadata 三类发现请求都不发送执行凭据 Header。

Runtime 在接受 Events 或 Command 执行请求时读取可选的 `X-HW-ID`、`X-HW-APPKEY`、
`Authorization` 和 `access-token`。这些值只在本次 Agent 执行及其 Child 调用期间由内存中的
`MateCredentials` 持有，经 `RuntimeEventService`、`RuntimeSessionEngineRegistry`、
`ManagedAgentSessionRequest` 和 `AgentSessionFactory` 传递；不写入数据库、Agent 目录、Event、
Prompt、模型消息或日志。执行结束后 Runtime 不再主动持有这些值。实现不使用 ThreadLocal、
请求作用域 Bean 或进程级 resolver，共享 `HttpMateToolClient` 也不保存这些值；
`MateCredentials.toString()` 只显示各字段是否存在。null 参数统一归一化为不可变空快照；缺少
Host 执行上下文仍按既有错误处理，不与空快照混为一谈。消息或命令启动时固定快照，Child
只读继承，停止、确认和问答等控制请求不替换原执行快照。

Agent binding、Skill binding 和 tool metadata 查询不携带上述值。只有代理或 `CallTool` 经共享执行器最终发送
`POST /mate-service/v1/runtime/tools/{toolId}/execute` 时，才把收到的非空值放入同名 Header；
AppKey 与 JWT 同时存在时都发送。值保持调用方提供的原值；Claw 不补值、不添加 Bearer、不从
其他请求借用凭据，也不校验必填项、组合、最低完整性或 AppKey/JWT 互斥性。工具合法、权限与
确认通过且未取消时，即使四项全缺也实际调用 execute 一次；Cron 的空凭据遵循同一规则。
Mate 是最终认证、授权和参数校验权威，execute 不自动重试。该行为是架构改造；pi 没有对应
Mate 工具或凭据链。

Mate 非零业务响应沿既有 `ToolResult(isError=true)` 路径返回统一失败文本；网络、构造或解析
异常沿既有通用工具失败路径返回，不新增凭据公共错误码。统一文本避免 Mate `resMsg` 或畸形
响应正文把秘密回显给模型。日志保留非敏感 operation、toolId、异常类型和原始来源栈帧；对
可能包含 Header value 或响应正文的异常不记录原始 message、cause 或正文。

## 5. CampusMate 共享配置

自 2.2.0 起，Tool 不再使用顶层 `mate.innerGWSerive` 或私有的 `mate.endpoints`。
`HttpMateToolClient` 与 Model、受管 Runtime 共享 `campusmate.base-url`，并从
`campusmate.endpoints` 取得 Agent、Skill、Tool 元数据与 Tool execute operation。
其中 Skill query 与受管 Runtime 共用一个 `skill-info-path-template`，不再重复配置。

完整结构、源码基线、迁移规则和配置可视化见
[CampusMate 客户端共享配置设计](campusmate-shared-config.md)。本次只修改配置架构，Tool 的
HTTP method、path 和请求响应保持不变；2.3.0 只改变哪些请求会携带凭据 Header。

## 6. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.1.0 | 2026-09-22 | 实现按精确技术名披露单个可信工具定义，并固定确认前校验、预算和参数恢复边界。 |
| 3.0.0 | 2026-09-22 | 对齐 Agent 代理、Skill 文件渐进披露、CallTool 统一执行及正文读取错误契约。 |
| 2.4.1 | 2026-09-19 | 补齐 Mate 凭据已实现行为的源码提交、符号及测试验收范围。 |
| 2.4.0 | 2026-09-19 | 取消 Claw 的 Mate 凭据完整性检查，按 execute 快照透传非空原值并收敛失败响应与敏感异常日志。 |
| 2.3.1 | 2026-08-28 | 用读取时机、内存持有期限、携带请求和缺失值行为定义工具凭据边界。 |
| 2.3.0 | 2026-08-27 | POST Events 读取 `access-token`；发现请求不携带四项值，只有 Tool execute 请求携带收到的值。 |
| 2.2.0 | 2026-08-26 | Tool 复用 CampusMate 单一 base URL、共享 Endpoint 目录与 Skill query operation；移除顶层 `mate.*` 配置。 |
| 2.1.0 | 2026-08-24 | 删除部署方 resolver 占位；POST Events 读取三项凭据并供当次 List/Call 和 Child 使用，Cron 缺少值时 fail closed |
| 2.0.0 | 2026-08-24 | PascalCase 双工具；按 Agent/Skill 方法拆分客户端；稳定 JSON；Session cache miss 自动完整发现且 execute 不重放 |
| 1.x | 2026-08-22 以前 | 历史 ID/scope List 与 Call-before-List 设计，已由 ADR-0022 取代 |
