# Skill 实际输入与普通消息执行

版本：1.0.2 · 2026-09-08。

共享 POST 已由[#250](https://github.com/superheromeZzh/pi-mono-java/pull/250)发布。
下文保留原内部实现切片的证据与边界；新增真实HTTP与新JVM验收见末节，不再等待共享入口发布。

## Context 与范围

本片把已确认 Skill 输入接入已有普通消息执行链，不发布共享 POST。确认来源为用户在协调任务
「完善 built-in command 审查流程」于 2026-09-08 对请求格式及正文公开方案回复“建议执行”。
该最新决定补充设计仓 `88f4df16bc24bbfcd28e1ec374feb2de0db8be3b` 的
`04-命令与技能/02-技能命令/README.md` §4；旧待确认标记不再是本片的阻塞。
独立设计仓保持只读，HTTP 请求 VO、路由及完整 HTTP 验收由后续共享入口切片交付。

## 关键定义

- `SkillCommandInputDTO`：已经选择 Skill 类别的内部输入，`skillName` 不含 `skill:`；不含 HTTP Header。
- `SkillCommandExecutionService`：唯一业务归一化入口。原始参数超过 262144 个 UTF-16 单元即拒绝；
  缺省、null、纯空白变为无附加说明，非空参数逐字符保留。附件缺省/null 为空，最多32项，
  非空白、唯一、保序并防御复制。不增加文件 ID 正则，不静默去重。
- 实际输入：同次 `PreparedAgentRuntime` 里已直接绑定 Skill 的完整 `content` 原文；
  有附加说明时拼接两个换行及原参数。最终文本同样不得超过262144单元，不截断。
  无附加说明也可执行。既有缓存正文包含 SKILL.md frontmatter，原样保留，不重新读取路径或添加路径提示。
- 正文公开：完整实际文本作为普通 `user.message` 保存，模型、SSE、GET历史及重启恢复共用它；
  有权限读取该 Session 的客户端可看到 Skill 说明。不创建第二份正文/版本快照或额外保留期。

## 源码证据与差异

Java 分析基线：`8d61c767d09d47ef6c8d76c05535fcbb58c48373`。下列 Java 路径根为
`modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`。

| 分类 | 相对路径与符号 | 观察或决定 |
| --- | --- | --- |
| 已有实现 | `runtime/AgentRuntimeManager.java#loadSkill` | 从完整受管缓存读取 SKILL.md，`SkillInfo.content` 是完整文本。 |
| 已有实现 | `session/AgentSessionFactory.java#create` | `prepare` 一次后先调用 `runtimeValidator`，再用同一 runtime 装配模型、工具和 Session。 |
| 已有实现 | `runtimeapi/runtime/RuntimeSessionEngineRegistry.java#register/#complete` | 操作锁、全局容量与 Holder 清理由运行层拥有，构造失败释放容量。 |
| 已有实现 | `runtimeapi/service/command/skill/SkillCommandAdmission.java#requireBoundSkill` | 在实际快照核对 Agent 身份、启用状态、精确直接绑定，不信任先前 GET。 |
| 已有实现 | `runtimeapi/event/RuntimeEntryCodec.java#userEntry/#toUserMessage/#toHistoryEvent/#toAgentMessages` | 普通输入保存、模型转换、公开投影及恢复共用消息文本。 |
| 本片实现 | `runtimeapi/service/command/skill/SkillCommandExecutionService.java#execute/#expand` | 归一化、实际绑定准入和展开，复用普通执行服务；失败只输出稳定代码。 |
| 本片实现 | `runtimeapi/event/RuntimeEventService.java#submitPreparedMessage/#acceptUserEntry` | 在同一操作锁内接受展开后的普通消息；不复制执行生命周期。 |
| 本片实现 | `runtimeapi/event/RuntimeExecutionContextFactory.java#createPreparedMessage`、`runtimeapi/dto/RuntimeExecutionContextDTO.java` | 工厂准入回调同步执行一次，生成模型消息及持久化原文，引用仅属于本次请求。 |
| 上游观察 | pi `4af9d21d3b4d664e4a29fcabfec85171077248e3`，`packages/coding-agent/src/core/agent-session.ts#_expandSkillCommand`，1320行起 | 本地读取正文、移除 frontmatter、附加参数并携带路径；未知 Skill 回退普通文本。 |

Java 差异分类：结构化内部输入、允许 name-only 与正文公开是已确认产品约束；
未知/未绑定直接拒绝、不人为添加服务器路径/凭据是安全加固；复用服务锁与领域 Entry 而非本地 CLI 是架构变更。
保留完整缓存正文避免在另一处重复解析或丢弃说明；这不是 pi 的原样行为，也不是新的公开 HTTP 协议版本。

## 架构与数据流

![同次实际快照与普通消息链](skill_message_execution.svg)

[PlantUML 源码](diagram.puml#L1)

Application 后续只将请求 VO 映射为内部 DTO，调用
`SkillCommandExecutionService.execute(sessionId, input, locale, credentials)`，返回现有 `RuntimeEventStream`。
Service 把绑定/展开函数交给 `RuntimeEventService.submitPreparedMessage`；普通消息入口仍使用原 `create`
及七参数 `register`。两个入口仅在输入准备方式上不同，接受、Projector、超时、执行和资源收尾相同。

`BiFunction<String, PreparedAgentRuntime, String>` 是消息准备回调，不是通用 Command Handler，
不会加入 Registry 或形成中央命令名称分派。它只在本次工厂调用内使用，不存入 Spring 单例或历史。
凭据沿现有受管 Session 透传，不参与正文拼接、不记录日志、不写入 DTO或数据库。

## 设计决策

[ADR-0071](../../decisions/0071-skill-command-message-execution.html)：同一实际快照展开、单份普通消息保存，
复用现有执行链；不先从旧 GET 缓存展开，再由 Session 工厂切换到另一份快照。

## 边界情况与性能（DFX）

- 错误名称/参数/附件在执行服务归一阶段拒绝。实际未绑定返回 `COMMAND_NOT_FOUND`；禁用、身份不符、
  不可用或空正文返回 `AGENT_NOT_AVAILABLE`；展开超限返回 `INVALID_COMMAND_REQUEST`。
- idle 检查、模型纠正、容量获取、实际快照准入、数据库接受按既有顺序执行。模型纠正可能先追加
  Model/Thinking 领域 Entry，因此不宣称任何准入失败都完全无数据库写入；失败不得接受新的 `user.message`
  或启动模型，已取得容量/Holder 必须释放。数据库竞争方的 running 状态不能被本请求清理。
- 字符串长度先以 long 计算，超限不拼接超大结果；附件列表至多复制32项。无新存储表、后台线程、通用锁或定时器。
- SSE detach 沿用普通消息语义，仅解除订阅，不取消已接受执行；终态由原 Coordinator 清理。
  不在本片迁移 Events v2、退役控制端点或更改压缩行为。

## 契约改动与验收边界

没有新增公共路由。本片内部能力为最终共享 POST 的真实 Skill 分支，不安装伪 Handler、占位成功响应、
公开 commandId 或 `command.started/completed/failed`。完整 HTTP Header、ResultBean/SSE分流和七Builtin一起验收
仍由后续切片负责。不会将普通消息 API 的 Slash 文本重新解释为命令。

## 测试与验证

- `SkillCommandExecutionServiceTest`：参数原值/缺省、附件复制与顺序、非法输入、展开长度边界、稳定错误与无正文泄漏。
- `SkillCommandExecutionOpenGaussIT`：真实数据库、Repository、`AgentSessionFactory`、Agent、Coordinator、Projector。
  `assembleRuntime` 替换 `CampusClawAiService`、`AgentRuntimeManager`、`RuntimeAgentPromptLoader`、
  `ConfiguredToolAssembler`、`AgentDirectoryResolver`、`RuntimeModelManager`、`SubagentExecutionService`、
  `AgentScopedCronToolFactory`，并使用空的 `MateToolsetFactory` Bean 提供者。
  验证实际快照文本进入模型、普通Entry、SSE、历史、重新建立数据库连接后的恢复；验证detach继续执行、
  绑定/禁用/超限/忙状态/容量失败。接受竞争用例在 `prepare` 同步回调中经同一 Repository 先接受另一条消息，
  再断言当前请求被拒绝且已有 Entry/running 状态保留；该用例未建立独立连接或并发线程。
  本测试不验证真实文件加载、完整工具组装、真实模型服务或跨JVM HTTP；重连恢复不等于跨连接并发接受验证。
- 原 `RuntimeEventServiceTest`、`RuntimeCompactionServiceOpenGaussIT` 与 `RuntimeSessionRepositoryOpenGaussIT` 作为回归。
- 最终 `./mvnw -q spotless:apply checkstyle:check verify` 通过：394类、1889项普通测试，0失败/错误/跳过；
  其中新增服务单测25项。新增/修改测试质量脚本0错误、0警告。
- 独立创建的 openGauss 7.0.0-RC3 容器上，新 Skill IT 8项、原压缩9项、原 Repository 27项，共44项通过，
  0失败/错误/跳过。测试专属容器已删除且无残留卷；新测试逐例清理本次创建的Session记录并关闭执行与连接。
  集成命令为 `./mvnw -q -pl modules/coding-agent-cli -am
  -Dtest=SkillCommandExecutionOpenGaussIT,RuntimeCompactionServiceOpenGaussIT,RuntimeSessionRepositoryOpenGaussIT
  -Dsurefire.failIfNoSpecifiedTests=false -Dgaussdb.it.url=<dedicated-jdbc-url>
  -Dgaussdb.it.username=<dedicated-user> -Dgaussdb.it.password=<dedicated-password> test`。
- 模块新增850行，生成镜像后1700行。普通镜像同步、内容核对、`git diff --check` 通过；
  公司 `NativeParent:26.0.0-SNAPSHOT` 不可用，常规同步明确失败后采用 `--no-verify`，企业编译/JAR未验证。
- AST检查确认所涉及类全部方法/构造器不超过50非空行；两项record布局由人工复核。
  PlantUML生成、ASCII、SVG XML、Markdown链接/锚点与无Mermaid检查通过；ADR在1280/360宽度渲染无横向溢出，
  生成图和两张ADR显示已人工查看。这些检查不代替后续公开HTTP验收。

## 真实 HTTP 与新 JVM 同源验收

本验收切片从实际main `d9a207779911eb286e1579119fb45a972092efe4` 独立创建，
新增 `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/runtimeapi/web/RuntimeSkillCommandOpenGaussIT.java`，
并修复真实验收发现的 Entry 时间精度问题，增加对应 Repository 单测。
复用main已有 `RuntimeHttpProcessFixture`，不复制启动工具类，不修改辅助类，也不依赖尚在审查的Builtin测试PR #255。
沿用[ADR-0068](../../decisions/0068-reuse-runtime-http-process-fixture.html)的测试架构及ADR-0071的单份消息决定，
无新产品契约、Maven依赖、数据库结构或独立设计仓修改。

依据上述main源码：`SkillCommandExecutionService#expand`取同次实际缓存完整正文；
`RuntimeEntryCodec#userEntry/#appendUserPayload/#toAgentPrompt`分别保存文本和file_ids、公开文本和fileIds、
恢复为正文及已有`[File IDs]`文本引用；`modules/ai/src/main/java/com/campusclaw/ai/provider/mate/MateChatRequestMapper.java#appendUser`
将该文本写入实际模型请求的user.content。测试独立从准备的SKILL.md原文计算期望值，不调用生产展开/恢复方法生成期望。

- 两项参数化成功场景：name-only请求精确只含name，无arguments/fileIds；另一项携带保留首尾空白及换行的
  中文说明和有序附件`file_b/file_a`。包含frontmatter的正文必须完整进入真实模型HTTP请求、普通SSE、数据库Entry及GET历史。
  模型附件采用原文本引用，历史附件采用JSON列表，分别断言各自准确形状，不要求不同边界的表示字面相同。
- `assertStreamMatchesHistory`逐帧解析普通SSE，核对user和assistant完成帧与GET历史对应载荷、顺序及终态；
  验证Skill即使只Accept JSON仍使用SSE，无ResultBean成功包装、commandId或通用命令生命周期。
- 第一JVM真正退出后替换测试目录中的正文，启动第二JVM：完整Session/ETag、历史及数据库Entry不变；
  再发普通Events时实际模型历史仍含旧展开文本及原附件，不重新读取新正文替换历史。
  随后新Skill调用才使用新正文，先前消息仍保留原值；每次模型请求和全部Entry类型均精确校验。
- 一项拒绝/恢复场景：将已绑定目录暂时移出使完整缓存变为空绑定，返回404；重复附件返回400；
  两者不接受用户Entry或发起模型请求，随后同Session仍能正常执行。
  在真实Skill请求已接受、模型响应被控制时再发Skill返回409，不增加Entry或模型调用；放行后正常结束、Session回到idle。

真实组件是新打包JAR和子JVM、Spring Web、应用及Runtime、实际完整缓存加载、公共Session/工具装配、
MyBatis/JDBC与自有openGauss。外部Mate模型Chat由本地确定性HTTP/SSE服务替代，Agent/Skill由测试目录提供。
没有调用真实Mate、真实大模型或真正执行工具/下载附件；附件验收范围是既定ID引用在模型/消息中的保序传递。
本片不测试Skill真实断线、Compact摘要/实际Socket断线，也不迁移Events v2或增加私有Skill快照。

### 真实验收发现的时间精度修复

修复分析基线为 #253 合入后的 `de5a784bef2416391b96927f7c6ab902fd16e2ee`。
已有 `runtimeapi/persistence/MyBatisRuntimeSessionRepository.java#appendLocked` 将原 Entry DTO 直接入库，
`runtimeapi/event/RuntimeEntryCodec.java#toSseData/#toHistoryEvent` 分别从该 DTO 和数据库重读 DTO 生成时间。
`modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql` 的
`t_session_entries.timestamp` 是非空 `TIMESTAMPTZ(3)`，两者精度可能不同。

实际 name-only 和附加说明/附件两项均复现：同一Entry的SSE时间
`2026-09-08T02:52:44.375344Z`、`2026-09-08T02:52:50.882857Z`，历史分别变为
`.375Z`、`.883Z`。正文、附件及序号一致，但整帧一致性断言失败；保留该断言，不忽略或放宽时间比较。

修复在现有行锁内、调用 `insertEntry` 前将时间统一为UTC并截断至毫秒，SSE使用同一已保存值。
Entry与主线已有CommittedEvent共用 `normalizeTimestamp`，不在Codec或各命令新增重复策略。
null时间仍为非法内部状态，在写入和推进序号前失败；已有CommittedEvent的缺失时间错误消息不变。
这属于既有单份持久化消息决定的一致性修复，不是新产品契约或Events v2接入。

影响路径包括用户接受、Thinking、assistant、排队用户、工具结果、Compaction及Model/Thinking配置Entry；
它们继续经同一 `appendLocked`，保留原事务边界、领域顺序、正文和附件。Usage Record没有公开SSE时间，
本次不修改其存储策略，也不回写历史数据。新增单测覆盖非UTC、亚毫秒、整毫秒、整秒及null，
在Mapper调用时断言已归一，并核对返回DTO及原有锁/写入顺序；既有公共事件真实库测试作为回归。

按[进程测试说明](../runtime-http-test-fixture/README.md#验证与运行)先生成当前JAR、准备自有一次性数据库，
显式选择`RuntimeSkillCommandOpenGaussIT`及既有进程回归并提供JAR绝对路径和连接参数。
缺参跳过不算验收通过；新测试不执行DDL、全表清理或修改用户配置，资源在try-with-resources和JUnit临时目录内管理。

发布前正常同步 #253 的main `de5a784b`，重新生成JAR与初始化专用数据库；该主线增加公共事件事务重载，
本片保持当前Skill/普通Events接口，不负责接入Events v2。
同步后的修复前普通测试为399类2044项通过；真实HTTP验收随后发现上述两项失败。
最终交付以修复后重新生成的JAR和全部验证结果为准，不以修复前的普通测试代替真实验收。
修复后de5a基线的399类2049项普通测试及153项定向测试均零失败/错误/跳过。
首次补充Repository回归因连接URL未指定schema失败；补齐`currentSchema=campusclaw_session`后重跑全部153项通过，
未修改数据库表或放宽断言。联合运行旧Repository IT会清理专用schema，因此必须使用自有一次性数据库。

随后正常同步 #256 的main `744c8717`：该片仅新增尚未接入Controller的请求类型及校验常量，
没有更改本片Repository、共享进程辅助类、SQL或当前Command入口。同步后再打包并复跑，不把de5a结果充作新基线证据。
最终 `./mvnw -q spotless:apply checkstyle:check clean verify` 通过400类2067项普通测试；
新JAR的SHA-256为 `49042a9217f85430a12eb10efdb9c4b5951dba157e5905ebe642950cde11fe1c`。
该JAR及自有openGauss上的10类153项定向测试全部通过，零失败/错误/跳过：本片Skill IT3、Repository单测11、
既有进程辅助5、Catalog IT3、Events IT2、Skill服务32、MVC27及错误41、Repository IT27、公共事件原子IT2。

测试质量检查0错误；2项警告来自本次未修改的旧Repository测试方法名称，新测试无警告。
三个所涉及类的完整AST方法长度检查通过，最大50非空行（该检查还计入注解，比仓库要求严格）；
私有 `SavedSkillRunDTO` 的空record体、数据职责及注释语言经人工核对。
镜像同步、布局、内容一致、文档PlantUML生成/ASCII/SVG XML/链接及`git diff --check`通过。
公司 `NativeParent:26.0.0-SNAPSHOT` 无法解析，常规同步失败后显式采用`--no-verify`同步；公司环境编译仍未验证。

## 版本历史

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| 1.0.2 | 2026-09-08 | 共享POST合入后增加Skill真实HTTP、附件引用、完整正文同源及新JVM恢复验收；修复Entry保存前时间精度不一致。 |
| 1.0.1 | 2026-09-08 | 准确列明集成测试的真实与替代组件，区分重连恢复和同步模拟的接受竞争；生产与测试代码不变。 |
| 1.0.0 | 2026-09-08 | 根据用户确认接入真实 Skill 输入、实际绑定与普通消息执行；共享 HTTP 单独交付。 |
