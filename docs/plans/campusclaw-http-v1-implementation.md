# CampusClaw HTTP V1 实施记录

> 版本：3.13.1；更新日期：2026-09-19。
>
> Java 变更前源码基线：`29ebec6a8cad99896414211cda28c6fffec71bee`。
>
> 计划模式与统一 Session 更新的实现核验提交：`17dbb20ae998c58c2b3f5ae19c254922e9f93210`，不作为后续 Mate 凭据规则的实现证据。
>
> 计划模式契约基线：`pi-mono-java-design@0233763eb846a297cd390eda1f2da05004c91dd7`。
>
> Mate 凭据透传变更前基线：`6a331d3477df23f8404582c3922b55c265530605`；目标设计基线：
> `pi-mono-java-design@9e766a030aa4c777da96586db4c7778fc84c7995`。
>
> Mate 凭据规则的实现核验提交：`db990ab3358798743ce63668035ab98fc0554a32`（2026-09-19）；
> 入口、工具、HTTP 客户端的精确符号及验证范围见[专项源码证据](../designs/mate-tool-client.md#credential-source-evidence)。
>
> 本文记录 Runtime 直接 HTTP 接口；企业 Mate 公共 Chat 的接入由外部工程维护。

## 1. 范围与权威来源

CampusClaw 默认以 Spring MVC HTTP 服务启动，提供请求范围 SSE 与持久化公共事件历史。
根消息对应内部执行；执行可包含多个审核续段。执行标识和段标识保留在服务内部，客户端按
Session、根消息事件和目标计划事件关联交互。

精确字段、错误码和完整报文以独立设计仓的
[共享接口契约](https://github.com/superheromeZzh/pi-mono-java-design/blob/0233763eb846a297cd390eda1f2da05004c91dd7/01-总体架构/01-CampusClaw多Agent运行时/README.md)
为准。计划模式代码接线、验收范围及外部实现差距见
[计划模式实施说明](campusclaw-plan-mode-implementation.md)。历史实现结论绑定原提交，不作为新版本测试证据。

## 2. 当前接口与源码

基础路径为 `/campusclaw-service/v1`。下列九个操作在当前实现中有生产 Controller 入口：

| 操作 | 方法与路径 | 核心行为 | 源码入口 |
|---:|---|---|---|
| 1 | `POST /sessions/{sessionId}/events` | 严格单事件外壳；消息、停止、工具确认、计划确认；返回 data-only SSE | `RuntimeEventController#submit`、`RuntimeEventService#submit` |
| 2 | `GET /sessions/{sessionId}/events` | 当前分支完整公共事件；数字 page/limit；与完整 SSE 同形 | `RuntimeEventController#list`、`CommittedEventQueryService#list` |
| 3 | `POST /agents/{agentId}/sessions` | 创建 Session，默认 thinking=true，mode 继承 Agent | `RuntimeSessionController#create`、`RuntimeSessionService#newSession` |
| 4 | `GET /sessions/{sessionId}` | 返回完整 Session 和同源 ETag | `RuntimeSessionController#get`、`RuntimeSessionResponseAssembler` |
| 5 | `DELETE /sessions/{sessionId}` | idle 删除，running 拒绝；保留墓碑和清理流程 | `RuntimeSessionController#delete`、`RuntimeSessionService#delete` |
| 6 | `GET /sessions/{sessionId}/models` | 当前模型和可用模型 ID 列表 | `RuntimeSessionConfigurationController#listModels` |
| 7 | `POST /sessions/{sessionId}` | displayName、modelId、thinking、mode 按提交字段原子更新 | `RuntimeSessionController#update`、`RuntimeSessionUpdateService#update` |
| 8 | `GET /sessions/{sessionId}/commands` | 按当前状态返回共享命令清单 | `RuntimeCommandCatalogController#list` |
| 9 | `POST /sessions/{sessionId}/command` | Builtin JSON 或 Skill 执行 SSE | `RuntimeCommandController#execute` |

Controller 位于 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/web/`；
应用服务分别位于同模块的 `runtimeapi/session/`、`runtimeapi/event/` 与 `runtimeapi/service/command/`。
在 `29ebec6` 上，Events 三类输入、公共历史和共享命令已存在；本次新增第四类计划确认及统一配置 POST。
旧 `/model`、`/thinking` PUT 和 `/abort`、`/steers`、`/follow-ups` 写入口不属于当前 HTTP 契约。

## 3. 状态与一致性

### Session 更新

统一 POST 允许一次提交一个或多个可更新字段，不限制前端必须联动修改。缺省字段保持不变；
空请求、显式 null、未知字段、重复字段和类型强制转换被拒绝。强 `If-Match` 必填，锁内重新核对
资源版本；实际变化只增加一次版本。提交 modelId、thinking 或 mode 时要求 idle，包括提交同值；
仅名称更新可在 running 执行。

模型可用性、最终 thinking 与模型能力共同校验。仅在实际切换至不支持 reasoning 的模型且请求
省略 thinking 时自动关闭 thinking。配置事件按 model、thinking、mode 顺序追加；名称不生成
额外消息 Entry。更新响应和 ETag 从同一事务返回的 Session 快照组装。

### 事件与审核

消息接受事务先保存回执、占用 Session 并注册内部执行，随后才启动 Agent。
完整 `agent.message`、`agent.tool_call`、`agent.tool_result` 等事件进入历史；正文和思考的 delta
只用于临时预览，不持久化、不重放。每个 SSE data frame 是公共事件 JSON，GET 复用同一投影。
具体消息与控制路径见[统一 Events HTTP 接入](../designs/runtime-events-http/README.md)。

`ExitPlanMode` 的完整方案形成 `agent.plan`，随后发送 `session.status_idle(reason=confirming)`
并结束当前 HTTP 响应。此时 Session 仍为 running，mode 仍为 plan，根执行继续等待。
客户端用该计划的 eventId 提交 `user.plan_confirmation`：approve 原子提交决定、新续段、build
模式及其事件，revise 保留 plan 并让原工具返回修订结果。决定回执总在续段响应首位。
运行时原执行仍使用最初接收的业务凭据，确认请求不会替换它们。

Events 与 Command 的四项 Mate Header 均为可选调用上下文。入口不做必填、组合或最低完整性
检查；消息或命令启动时原样捕获四项值并固定为不可变执行快照，Child 只读继承，后续停止、
确认和问答请求不替换该快照。只有构造 Mate execute 出站请求时才省略 null 或空字符串；其余
值保持原 Header 名和值。工具解析、权限、确认和取消检查通过后，即使四项全缺也向 Mate
execute 实际发送一次请求，由 Mate 判定认证和授权；发现及其他出站请求不携带这些值，execute
不重试。缺少 Host 执行上下文仍按既有错误处理，不与空凭据快照等同。

Mate 业务失败继续投影为既有工具失败，网络等异常继续使用通用工具失败，不新增凭据公共错误
码。面向模型和公共事件的失败文本不回显 Mate `resMsg` 或畸形响应正文；日志保留非敏感定位、
异常类型和原始来源栈帧，但移除可能包含 Header value 或响应正文的 message 与 cause。

批准后的正常模型、工具和答案事件与实施进度并存。后端从 `TodoUpdate` 经 after hook 处理的
最终成功结果生成 `agent.todo_updated`，与同一 Tool Result Entry 的普通结果事件原子提交。
失败结果不更新任务；前端按根消息和完整 todos 快照更新列表。

### 停止、恢复与持久化

停止与确认都先提交数据库控制信号，再由原执行实例领取。停止优先于尚未领取的确认；
执行终态成功提交后才释放 Session 占用。多实例可投递控制决定，但不自动接管已丢失的进程内
执行或重放业务工具。客户端断流只重新读取历史和 Session，不自动重发已经可能被接受的写请求。

`t_session_entries` 保存分支结构与上下文记录；`t_session_events` 和
`t_session_event_projection` 保存权威公共投影及完整性标记；`t_session_executions`、
`t_session_execution_segments` 和段事件关联表保存执行与结果段。计划决定使用
`t_session_plan_confirmations`，普通工具决定使用 `t_session_tool_confirmations`。
Usage 仍使用内部 Record 和统计表。审核与展示用 Entry 不重复加入模型历史。

数据库按首版全新安装交付，规范源为
`modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql`。
公司镜像由同步脚本生成 `campusclaw/scripts/install/initdb_gaussdbv5.sql`，保留公司数据库、
Schema、Owner 与授权头部；本仓不交付已有安装的增量升级脚本。

## 4. 集成边界与理由

| 决策 | 分类 | 理由与边界 |
|---|---|---|
| 配置统一 POST，审核继续通过 Events | 架构变更 | 会话配置归 Session，审核决定需要关联原计划并返回原执行续段 |
| Agent.mode 只初始化新 Session | 产品约束 | 元数据刷新不改变已有会话行为，Tool/Skill 元数据保持稳定 |
| 提示词区分 plan/build，保留业务工具集合 | 产品约束 | 调研和实施遵守不同提示策略，业务权限由已有工具权限机制承担 |
| 审核决定、模式与续段原子提交 | 安全加固 | 避免客户端看到批准而执行仍按旧状态继续，防止重复确认和错目标确认 |
| 后端显式投影任务快照 | 架构变更 | 前端不解析工具结果文本，实时和历史共用一份公共事件 |
| Runtime 仅保存执行内凭据快照 | 安全加固 | POST 的可选调用上下文只用于原执行及 Child 的 Mate Tool execute，不进入数据库、Prompt 或日志；控制请求不替换快照 |
| Mate 在真实 execute 判定凭据 | 架构改造 | Claw 不理解 Mate 的凭据组合；空或部分快照也调用一次，仍保留工具权限、确认、取消和上游资源授权边界 |

Runtime 通过 `ResultBeanAdapter` 保留公司 ResultBean 替换点。公司认证、租户隔离、动作授权和
公共 Chat 适配由上游 Mate 工程承担；本仓没有该工程，不以 Runtime/工作台测试代替企业集成验收。
本次复用 JDK、Jackson、Spring、MyBatis、Vue 与既有库，没有新增 Maven 或前端直接依赖。

## 5. 验证与关联记录

当前计划模式验证记录集中于[实施说明](campusclaw-plan-mode-implementation.md#验证)。
数据库事务测试、实际 JAR HTTP 测试和前端合成协议验收分别记录，避免把单元测试通过当作企业端到端联调完成。
既有能力的原始证据可查[统一 Events HTTP 接入](../designs/runtime-events-http/README.md)、
[共享命令 POST](../designs/shared-command-http/README.md)和[前端实现](../designs/frontend-events/README.md)。

## 6. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.13.1 | 2026-09-19 | 分列计划模式与 Mate 凭据规则的实现核验提交，明确专项源码与验收范围。 |
| 3.13.0 | 2026-09-19 | Events 与 Command 不再校验 Mate 凭据完整性，execute 透传非空原值并由 Mate 返回实际失败。 |
| 3.12.0 | 2026-09-15 | 统一 Session POST 配置更新，接入计划审核与实施任务事件，并按当前九个 HTTP 操作校准实施记录。 |
| 3.11.1 | 2026-09-08 | 明确首版数据库只交付全新安装与授权材料，不提供已有安装升级入口。 |
| 3.11.0 | 2026-09-08 | 接通共享POST的Builtin JSON和Skill SSE，记录方法校验、日志及错误媒体类型边界；真实跨进程验收单独交付。 |
| 3.10.0 | 2026-09-07 | 删除无运行时消费者的旧命令原型；HTTP/SSE 和公共 Session 能力不变，POST Command 仍待实现。 |
| 3.9.0 | 2026-09-07 | 发布共享命令清单 GET，记录独立契约基线、完整缓存和操作级 503；不发布 POST Command。 |
| 3.8.0 | 2026-09-07 | 公司 GaussDB 脚本使用固定库与 Schema 头部、去除事务包裹，并将每个删表语句紧邻放到对应建表语句前。 |
| 3.7.0 | 2026-09-04 | 更新资源 ID、HTTP 路径和请求限制的归属至底层 common 的 ClawConstants，外部契约不变。 |
| 3.6.0 | 2026-09-03 | 公司镜像只在 `scripts/install/` 交付单一 GaussDB 初始化 DDL，不再将数据库发布材料打入 classpath。 |
| 3.5.0 | 2026-09-01 | 对齐 CampusClaw 公司镜像的新目录、Java 包、同步入口和独立公司构建门禁；HTTP/SSE 契约不变。 |
| 3.4.1 | 2026-08-28 | 用读取时机、内存持有期限、携带请求和缺失值行为定义 Mate 工具凭据边界。 |
| 3.4.0 | 2026-08-27 | POST Events 读取 `access-token`；发现请求不携带四项值，只有 Tool execute 携带收到的值。 |
| 3.3.1 | 2026-08-25 | 处理 PR #172 审查：校准八工具与 Agent 工作区、Compaction/Usage 持久化模型及三段源码基线 |
| 3.3.0 | 2026-08-25 | 按 Runtime-only 现状删除已退役 CLI 启动描述和失效的启动类证据引用 |
| 3.2.0 | 2026-08-24 | POST Events 读取三项 Mate 凭据并在当次 Agent 执行和 Child 调用期间由内存对象持有；保持 Runtime 不做本地认证和凭据不持久化 |
| 3.1.0 | 2026-08-21 | 对齐 HTTP 1.38：Path、Query、JSON 与 SSE data 统一为 lowerCamelCase；Header 和字段值保持原样；内部 Entry payload 通过受控投影兼容已有数据 |
| 3.0.2 | 2026-08-21 | 用 Controller 标量参数 Jakarta 注解替代命令式路径 ID Validator，并统一映射 Spring MVC 方法校验错误 |
| 3.0.1 | 2026-08-21 | 将类型化资源 ID 的正则字符串和编译模式集中到领域模式类，并从 Runtime HTTP 常量中移除领域约束 |
| 3.0.0 | 2026-08-20 | 对齐 HTTP 1.37 契约：类型化资源 ID、创建默认 thinking、移除 Runtime 本地 Header 认证、删除消息体 type，并实现 thinking 事件持久化、查询过滤和游标绑定 |
| 2.2.0 | 2026-08-20 | 落地显式双 Locale 消息源、标准语言协商、HTTP/SSE 错误国际化和镜像资源迁移 |
| 2.1.0 | 2026-08-19 | 基于 `f899547d` 整改目录边界、事件职责、错误目录、异步日志和多实例执行归属错误 |
| 2.0.0 | 2026-08-18 | 以实现提交 `8691e880` 重写，修正 MVC、鉴权边界、删除语义、Agent 目录与 `file_ids` 行为 |
| 1.x | 2026-08-18 | 逐接口开发日志，包含已经失效的 WebFlux、模板快照和文件解析方案 |
