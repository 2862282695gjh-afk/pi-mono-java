# 共享命令清单 HTTP 实现

> 版本：1.0.0 · 日期：2026-09-07 · 状态：Implemented

## 1. 范围与结论

本片发布 `GET /campusclaw-service/v1/sessions/{sessionId}/commands`，接入已合并的
`CommandCatalogService`。清单包含 Builtin 与直接绑定 Skill 的轻量描述，不执行任何命令。
根据本次开发协调中明确的交付授权，GET 独立交付，不等待 POST；这是实现顺序调整，
不改动设计仓的操作定义。POST Command、Skill 正文展开与公开方式、Events 新协议均不在本片范围。

规范来源是只读设计仓 `pi-mono-java-design@88f4df16bc24bbfcd28e1ec374feb2de0db8be3b`：

- `01-总体架构/01-CampusClaw多Agent运行时/接口契约/操作/12-list-session-commands.json`；
- `04-命令与技能/00-Slash-Command通用模块/README.md` v1.6.0。

本文仅记录实现映射，不建立第二份字段规范，不修改 `pi-mono-java-design`。

## 2. 源码证据与理由

合并基线为 `pi-mono-java@7d771ca802b781361e0e62e9014bafb6b0ed5e7a`。
本片实现提交为 `afee9bd333d0fc74ba0cef8b27b6df7357acdf30`；下表新增入口和错误投影以此提交为证据，
不能归为合并基线已有行为。Java 路径前缀为
`modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`。

| 类型 | 路径与符号 | 已观察行为、决定及原因 |
|---|---|---|
| 上游观察 | pi `5cd93f688aaab89dbb6dfa4aca535f21796ae185`，`packages/coding-agent/src/core/slash-commands.ts` 的 `BuiltinSlashCommand`、`BUILTIN_SLASH_COMMANDS` | 本地命令提供名称、描述、可选参数提示；不定义 Java HTTP、缓存、排序或 503 语义。 |
| Java 基线 | `runtimeapi/service/command/CommandCatalogService.java#list` | 查持久化 Session，调用一次 `prepareCached`，要求完整 Agent 元数据后解析一次 Catalog；不触发刷新。 |
| Java 基线 | `runtimeapi/service/command/CompositeCommandRegistry.java#resolveComplete`、`runtimeapi/service/command/SkillCommandSource.java#definitions` | 使用同一 PreparedAgentRuntime；完整解析路径不再次读取 Manager 或 Skill 文件，展示定义没有伪 Handler。 |
| Java 基线 | `runtimeapi/service/command/CommandDiscoveryResponseAssembler.java#assemble` | 固定 Builtin 顺序、按名称追加 Skill，按已读取 Session 状态投影轻量响应；清单不是执行准入凭证。 |
| Java 新增 | `runtimeapi/web/RuntimeCommandCatalogController.java#list` | 仅依赖应用 Service 和 ResultBeanAdapter；标量 ID 使用共享正则与 Jakarta 校验；包装响应 VO 为普通 JSON。属于架构接入，不复制 Catalog 业务。 |
| Java 新增 | `runtimeapi/web/RuntimeExceptionHandler.java#response`、`#isCommandCatalogRequest` | 依据 Spring 已匹配的 HandlerMethod，仅对清单入口的 AGENT_NOT_AVAILABLE 投影 503/Retry-After；不按任意 URI 后缀猜测操作，不全局改动错误枚举的 422。属于已确认产品契约的操作级适配。 |
| Java 新增 | `modules/common/src/main/java/com/campusclaw/common/constant/ClawConstants.java` 的 `RuntimeApi.Command.CATALOG_RETRY_AFTER_SECONDS` | 重试秒数复用共享业务常量归属，不增加转发常量或配置框架。 |

## 3. 分层与生命周期

![共享清单 HTTP 的依赖边界](command_catalog_http.svg)

[PlantUML 源码](diagram.puml#L1)

Controller、Service、Assembler、Registry、Adapter 和集中错误处理器沿用 Spring 单例，
没有请求状态字段。每次调用的 Session DTO、PreparedAgentRuntime、不可变 Catalog 与只读响应 VO
只通过方法参数或局部变量传递。Controller 不接触 Repository、Registry、Holder 或内部 DTO。
既有核心结构详见[完整命令清单实现](../builtin-command-catalog/README.md)。

读取清单不占运行容量、不安装工具、不写数据库、不追加 Entry，也不捕获 Mate 执行凭据。
Session 状态是在查询时读取的快照，不承诺返回后仍保持 idle；执行时必须由对应操作重新检查状态。
本片沿用 Runtime 不做本地身份认证、由上游保证授权的既有集成边界，不新增认证器或公司制品依赖。

## 4. HTTP 映射

成功返回 HTTP 200、`application/json`、`Cache-Control: no-store`、实际 `Content-Language`，
不返回 ETag、Retry-After 或 SSE。ResultBean 成功字段为 `resCode/resMsg/result`，
`result` 仅包含非 null 的 `commands` 数组；Controller 只包装 Service 返回的 VO。

- idle：Builtin 顺序为 help、status、name、model、thinking、compact、skills，后接名称升序的 Skill。
- running：移除 compact 和 Skill；model/thinking 保留描述但不提供 input，name 仍有 input。
- 描述符只保留 name、kind、description 与必要的 input；input 只保留 hint 和仅在 true 时出现的 acceptsFiles。
  不输出 available、unavailableCode、ID、版本、路径、正文或内部 DTO 字段。
- 完整的空绑定是成功空 Skill 列表；缓存不完整时整个清单失败，包括 running，不返回部分 Builtin。
- 路径参数由标准 MVC 校验映射为 `400 INVALID_SESSION_ID`；Session 不存在为 `404 SESSION_NOT_FOUND`；
  Agent 完整缓存不可用为 `503 AGENT_NOT_AVAILABLE` 并返回 `Retry-After: 3`。
- 所有错误只包含 `resCode/resMsg`，沿用语言响应头；错误正文不包含内部异常原因。
  其他操作上的 AGENT_NOT_AVAILABLE 仍为 422，不附加此清单重试头。

错误文本继续使用既有 `i18n/messages_en_US.properties` 与 `messages_zh_CN.properties`，
例如“指定的 Agent 当前不可用。”；设计 JSON 的示例措辞不用于重写全部消息资源。
客户端按稳定 resCode 分支，不解析 resMsg。描述文本沿用 Contributor 元数据，不引入命令描述翻译系统。

## 5. 测试与交付证据

新增测试位于
`modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/runtimeapi/web/RuntimeCommandCatalogRoutesTest.java`。
它启动真实 Spring MVC 上下文，使用实际七个 Contributor、Builtin/Skill Source、Registry、
应用 Service、Assembler、VO 和集中错误处理器，只替换 Repository、Manager 及未被调用的执行服务。
不是预先拼装 JSON 后直接断言固定返回值。

| 检查 | 证据与边界 |
|---|---|
| HTTP 契约 | 18 个用例覆盖字段精确集合、idle/running、完整空绑定、缺缓存、异常脱敏、缺 Session、非法 ID、语言协商和现有 POST 422 回归；检查新 Controller 只注册 GET。 |
| 一次解析 | 成功路径验证一次 Session 读取、一次 prepareCached、同一 PreparedAgentRuntime 的一次 resolveComplete，且无额外交互。 |
| 针对性回归 | 清单路由 18、Catalog Service 20、既有配置路由 6，共 44 项通过。 |
| 完整构建 | `./mvnw -q spotless:apply checkstyle:check verify`，393 个测试类、1860 项测试，0 失败、错误或跳过。 |
| 测试质量 | 用户指定脚本路径为失效链接；从本机原始 `java-ut-coverage-loop.skill` 归档执行同名脚本，0 错误、0 警告。 |
| Java 规范 | Controller、Handler、测试的 AST 检查通过；ClawConstants 因既有 Unicode 转义需人工复核，新增常量格式与所有方法长度逐项确认，无超过 50 行的方法。 |
| 公司镜像 | 四组 Java 源码与测试同步；布局测试及包名替换后的内容一致性检查通过。默认同步的公司编译因 NativeParent 无法解析而失败，显式 `--no-verify` 仅完成同步，不宣称公司编译通过。 |
| 规模 | 模块侧新增 500 行，镜像后 1000 行；整个 PR 以 `scripts/check-commit-additions.sh origin/main HEAD` 为准，文档不计入。 |

本片没有运行新增 GET 的真实 JAR/openGauss 跨进程测试；MVC 验证不等同于数据库部署验证，
也不证明尚未发布的 POST Command/Skill 执行。后续组合验收复用独立交付的真实后端测试辅助代码，
不在本片复制未合并测试基础代码。

文档需执行 PlantUML 生成及重生成一致性、ASCII、SVG XML、Markdown 链接与锚点、无 Mermaid、
ADR 桌面/窄屏渲染和 `git diff --check`。最终执行结果记录在 PR。

## 6. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.0.0 | 2026-09-07 | 独立发布共享清单 GET，记录精简响应、操作级 503 与验证边界。 |
