# 仓库开发指引

版本：1.0.1；更新日期：2026-09-14。

## 适用范围与工作流程

- 本文件是全仓库唯一维护的开发指引，补充用户级规范。改动前阅读本文件及相关源码、设计；存在子目录 `AGENTS.md` / `AGENTS.override.md` 时遵守其适用范围。
- 保留无关工作区改动，不擅自暂存、改写、stash 或丢弃。工作区不干净或被其他任务使用时另建 Git worktree。
- 本地启动与安装仅支持 macOS、Linux；不新增或维护 Windows batch、PowerShell、`mvnw.cmd`、Task Scheduler 入口。POSIX `mvnw` 可保留上游 Cygwin/MinGW 兼容，但不承诺 Windows 支持或验证。
- 每个主题从最新 `origin/main` 创建独立 `codex/<topic>` 分支和 PR；禁止直接向 `main` / `master` 提交或推送。
- 单次开发、提交和 PR 不设新增代码行数上限，模块与镜像不设配套预算；按职责和验收边界组织交付，详见[开发规模与 CI 验证](docs/designs/commit-addition-limit.md)。
- 只暂存本次任务的明确路径，混合工作区禁止 `git add -A`。提交采用中文 Conventional Commits：`type(scope): 中文描述`。
- 提交前检查 `git status -sb`、任务 diff、staged diff，运行 `git diff --check` 和相关最小验证。Java 改动先格式化；模块改动按下文同步镜像。
- 只推送主题分支并创建面向 `main` 的 Draft PR。正文说明改动、原因、用户/开发者影响和验证；交付前核实 PR 存在并提供 URL。用户未要求时不转 ready、不合并。
- 管理员权限不是正常发布路径。禁止管理员绕过、`git push --no-verify`、强推或直接更新默认分支；例外须用户在当前任务明确指定，泛称“提交/推送/完成”不构成例外。
- `main` 推进后 merge 最新 `origin/main` 并重跑相关验证，不 rebase 已共享历史。仓库使用 Merge commit；合并后清理分支用 `git branch -d`，不以强制删除掩盖未合并状态。
- 所需验证、推送或 Draft PR 未完成时报告具体阻塞，不宣称完成。

## 项目架构

CampusClaw（`com.campusclaw`，原 `pi-mono-java`）是 JDK 21 + Spring Boot 3.4.5 的 ToB Agent Runtime，采用 Maven 多模块。`modules/coding-agent-cli` 是历史目录名，产物 `campusclaw-agent.jar` 仅提供 Spring MVC HTTP/SSE 服务，不提供 CLI/TUI/RPC 模式或另一套启动上下文。

| 模块目录 | Artifact | 职责 |
|---|---|---|
| `modules/common` | `campusclaw-common` | 共享业务常量 |
| `modules/ai` | `campusclaw-ai` | LLM 抽象、Provider、类型、模型注册 |
| `modules/agent-core` | `campusclaw-agent-core` | Agent 门面、AgentLoop、工具 Pipeline、事件 |
| `modules/cron` | `campusclaw-cron` | 进程内调度、Job 持久化、Cron 工具 |
| `modules/coding-agent-cli` | `campusclaw-coding-agent` | HTTP/SSE Host、受管目录、Session 与内置工具 |

直接依赖：ai → common；agent-core → ai；cron → agent-core；coding-agent-cli → common、ai、agent-core、cron。common 与 ai、agent、cron、codingagent 同级，不依赖业务模块；直接使用其常量的模块必须声明 common 依赖。

- `BuiltInToolName` 仅允许 `Read`、`Find`、`Grep`、`Ls`、`Cron`、`ListMateTools`、`CallMateTool`、`Agent`。Runtime、Cron、Child profile 严格校验；`ToolExecutionMode` 只控制顺序/并行执行。
- Runtime、Cron、Child 统一使用 `AgentSessionFactory`；持久化及生命周期由各 Host 管理。`ai` / `agent-core` 使用 Reactor `Mono/Flux`，事件流路径禁止 `.block()`。
- 元数据默认在 `agent/{agentId}/.campusclaw/`，由 `AgentRuntimeManager` 准备。Skill 只读受管目录；不恢复 PackageManager 或本地 Skill 安装/导入。Mate 工具实时发现并按名称调用。
- 模型凭据来自部署配置，不写入 Agent 目录。Provider 与模型以源码及 [README](README.md#模型供应商) 为准，不复制数量清单。

## 构建运行

必须使用 **JDK 21**；保留 records、sealed types、模式匹配等 Java 21 特性。`./mvnw` 使用当前 `JAVA_HOME`，不要假设个人 shell 已配置。Spotless 使用 palantirJavaFormat 2.66.0，勿换成不兼容的 JDK。

| 命令 | 用途 |
|---|---|
| `./mvnw package -pl :campusclaw-coding-agent -am -DskipTests` | 构建服务，不能代替测试 |
| `java -jar modules/coding-agent-cli/target/campusclaw-agent.jar` | 启动服务 |
| `./mvnw test` | 全部测试 |
| `./mvnw -pl :campusclaw-agent-core -am test -Dtest=AgentLoopTest -Dsurefire.failIfNoSpecifiedTests=false` | 单类测试并带上依赖模块；单方法用 `-Dtest=AgentLoopTest#runCompletesPromptToolAndSteeringCycle` |
| `./mvnw spotless:apply` | 格式化并清理 imports |
| `./mvnw spotless:check` | 独立检查格式 |
| `./mvnw validate` / `./mvnw checkstyle:check` | Checkstyle 检查 |
| `./mvnw verify` | 完整构建与测试，仍须单独检查 Spotless |

主模块服务配置是 [application.yml](modules/coding-agent-cli/src/main/resources/application.yml)；修改后重新构建或重启，不恢复根目录副本。Surefire 已配置 `-XX:+EnableDynamicAgentLoading` 供 JDK 21 上的 Mockito 使用。

## 编码规范

[pom.xml](pom.xml) 和 [codecheck.xml](codecheck.xml) 定义自动检查；下列规范还包含人工要求，构建通过不代表全部满足。适用的用户级 Java、XML、DTO/VO、SQL 和依赖规范同样有效。

### 文件、注释与命名

- Java 文件以 Huawei 版权头开头，放在 `package` 前；年份为 `YYYY-YYYY`，单年重复，如 `2026-2026`：

  ```java
  /*
   * Copyright (c) Huawei Technologies Co., Ltd. {YYYY}-{YYYY}. All rights reserved.
   */
  ```

- Java 注释、Javadoc 描述用中文；标识符、固定字面量、产品名和版权头保留原文。私有字段/常量简短说明用 `// ...`，不用一行 Javadoc。
- 新顶层 public class/interface/enum/record/annotation 必须有中文功能 Javadoc、`@version [br_eCampusCore 26.0.0, YYYY/MM/DD]` 和 `@since [br_eCampusCore 26.0.0]`。
- 方法不一律要求 Javadoc；写了就须覆盖全部 `@param`、非 void 的 `@return`、签名声明的受检异常 `@throws`，标签与签名一致，所有可见性均适用。
- main/test Java 不得新增 TODO/FIXME 或对应豁免。待办写入 [DEFERRED](docs/DEFERRED.md)，已发布产品缺陷进入 issue tracker。
- 注释符与内容间留空白（`///`、`/**`、空注释除外）；行尾 `//` 前后留空格。独立段落注释/Javadoc 若位于以 `;` 或 `}` 结束的代码之后，其前空一行；前行带尾注释也适用。紧接 `{`、`(`、`,` 的块首/参数注释除外。
- 非常量字段与局部变量（含 final 局部）用 lowerCamelCase：`^[a-z][a-zA-Z0-9]*$`；不用下划线、美元符或 Hungarian 前缀。常量用 UPPER_SNAKE_CASE，循环可用单字符变量。
- 不用同名局部、参数或嵌套字段遮蔽无关概念。构造器/setter/builder、record 构造器、`with*` 的同概念赋值可同名；纯迭代同名可保留，但优先改名。

### 结构、复杂度与常量

- K&R 大括号，`else/catch/finally/while` 与前一个 `}` 同行，单行守卫展开。每次声明一个变量，惯用 `for` 多变量初始化除外。
- imports 顺序 `java,javax,com,org,*`；禁止 wildcard import，移除未使用项。版权头、package、imports、类之间空行分隔。
- long 变量使用带大写 `L` 的整数字面量，不用小写 `l`；float 字面量带 `f` / `F`。
- 方法/构造器最多 **50 个非空物理行**，从声明行至匹配的结束括号计数，内部注释计入，前置注解/Javadoc 不计。修改类时一并拆分其他超限方法，按职责提取，不压缩语句凑行数、不仅为缩短方法引入参数对象；保留事务、异常、顺序及副作用，不新增超限豁免。
- Checkstyle `MethodLength(countEmpty=false)` 不等于上述计数：方法体前签名及纯注释未被完整统计，仍须人工核对。
- 圈复杂度 ≤20，整段 switch 计一个决策点；禁止新增 `huge_cyclomatic_complexity` 豁免，历史豁免不是范例。布尔表达式 `&&/||/&/|/^` 合计 ≤5，长条件提取谓词或命名集合。
- 禁止在控制条件等表达式内嵌赋值；`for` init/update 与 try-with-resources 资源声明除外。
- 共享业务常量集中到 [ClawConstants](modules/common/src/main/java/com/campusclaw/common/constant/ClawConstants.java)，按 `Skill`、`RuntimeApi` 等嵌套领域分组；regex 字符串与编译后的 `Pattern` 同组，消费者复用，不保留转发别名或独立领域常量/Pattern 文件。私有算法细节、typed enum、单例留在所属类，环境变化值通过配置注入。

### 数据、异常与线程

- 大小写转换、数字格式化显式传 Locale，机器可读值默认 `Locale.ROOT`，用户语言场景才用其他 Locale。
- 字符/字节互转显式传 Charset，默认 `StandardCharsets.UTF_8`；协议要求其他编码时注明依据。包括 getBytes、流 reader/writer、`new String(byte[])`、PrintWriter、Scanner、Files.readString 等；`char[]` 与 String 互转及不涉及编码的重载除外。部分重载依靠评审；regex 换行误报时调整布局，不省略编码。
- 日志统一 SLF4J，禁止用 `System.out/err` 记日志；禁止 `printStackTrace`。仅外部契约要求 stdout/stderr 时允许出口类用 `@SuppressWarnings("checkstyle:no_system_out_err")` 明示，不能以已退役 CLI/TUI/RPC 为理由。
- 日志模板用英文，不含中文及中文标点；占位符变量值不受限，用户文案走 i18n/资源束。不得新增 `no_chinese_in_log` 豁免。
- catch 必须真实处理：带上下文记录、恢复中断位、保留 cause 重抛或修复状态/返回合理默认值；空体、仅注释、变量名 `ignored/expected` 均非豁免。捕获 InterruptedException 后须恢复中断语义。
- 异常匹配抽象层次：I/O 用 UncheckedIOException，状态/参数用对应异常，领域错误用现有领域异常。禁止 `throw new RuntimeException(...)`；也不以根 Exception/Throwable/Error 代替具体错误。测试断言失败可用 AssertionError。
- 直接 `new Thread(...)`（含 ThreadFactory）须在 start 前安装 UncaughtExceptionHandler，默认 `com.campusclaw.agent.util.LoggingUncaughtExceptionHandler.INSTANCE`，有定制恢复需求才另写 handler。禁止裸链式 `new Thread(...).start()`；默认线程池工厂、CompletableFuture/ForkJoinPool 共享池不在该直接创建规则范围。

### Spring 与边界校验

- 必需依赖用构造器注入，字段 `private final`；单构造器省略 `@Autowired`，多构造器必须且只能有一个 Spring 入口标注它。测试/便捷构造器不标注，并取所需最小可见性。
- 不为规避构造器选择改用字段/setter 注入；仅确有按名称查找的外部契约时用 `@Resource(name = "...")` 并就地说明。多构造器 Bean 的直接实例化测试不能代替 Spring 装配验证。
- 标准注解可表达的 request VO 与 Controller 标量参数（含 path variable）约束必须使用 Jakarta Bean Validation，嵌套对象用 `@Valid`。启用方法校验，由集中异常处理映射稳定 API 错误；不得以逐 Controller 调用命令式 Validator 代替标准参数约束，不为单个标量校验新建 VO。
- Controller 接收/返回 VO，Service 负责 VO/DTO 转换和业务校验，Mapper 使用 DTO。必填/格式校验放 VO/标量参数，跨记录/状态转换/唯一性校验放 Service，数据库约束兜底。

## 测试验证

- 使用 JUnit 5、Mockito，Provider 集成测试可用 OkHttp MockWebServer。按变更运行最小相关检查；缺工具/依赖时报告未执行项，不把跳过当通过。
- 每个 `@Test` / 参数化 / 重复测试必须有真实断言：JUnit assert/fail、AssertJ、Mockito verify（含无交互/有序验证）、Reactor StepVerifier 均可；“跑通即可”须写成 assertDoesNotThrow 等显式断言。
- 禁止常量断言、相同表达式或被测代码副产物自比；不能只验证非 null，删除已被值断言覆盖的冗余非空检查。Checkstyle 只能发现部分虚假断言，不能证明测试质量。
- 补测试后须检查无断言、仅非空、恒真、stub 被测对象、反射测私有实现等质量问题。环境提供 `java-ut-coverage-loop` 的 `check_test_quality.py` 时，对新增/修改测试运行它，errors 必须为 0；缺失时报告脚本检查未执行并逐项人工核对，不假定个人目录脚本存在。
- Checkstyle 绑定 `validate` 并覆盖 main/test Java；Spotless 未绑定生命周期，verify 不代替 spotless:check。Java 改动可运行 `./mvnw -q spotless:apply checkstyle:check`，再执行相关测试。
- [.claude/settings.json](.claude/settings.json) 的 [Stop hook](scripts/claude-hooks/checkstyle-on-stop.sh) 仅在 Claude Code 生效：相对 HEAD 的工作区差异或未跟踪文件含 `.java` 时，运行根 Reactor 的 Spotless/Checkstyle；无 Java 变更则跳过。其他工具须自行检查。
- 纯文档/提示改动检查链接、语法与 `git diff --check`，不运行无关业务全量测试；XML 仅注释变化时解析并比较去注释后的结构，shell 提示变更运行 `bash -n`。

## 公司镜像

`campusclaw/` 是 `modules/*` 的生成单模块镜像，不加入根 Reactor；源码在 modules 修改后同步，不双份维护。包名由 `com.campusclaw` 改为 `com.huawei.hicampus.claw`。镜像从公司 Maven 仓库解析 `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT`，GAV 为 `com.huawei.campus:claw:1.0-SNAPSHOT`，产物为 `campusclaw/target/claw-1.0-SNAPSHOT.jar`。镜像 POM 独立声明 SLF4J API 和 Log4j2 starter，版本由公司父 POM 管理。

| 命令 | 行为 |
|---|---|
| `./scripts/sync-campusclaw.sh` | 解析公司父 POM，stage、apply、编译镜像 |
| `./scripts/sync-campusclaw.sh --dry-run` | 重建 build 暂存目录并预览，不覆盖镜像、不编译 |
| `./scripts/sync-campusclaw.sh --no-apply` | 仅 stage 到 build/campusclaw，不覆盖镜像、不编译 |
| `./scripts/sync-campusclaw.sh --no-verify` | 普通本地环境显式跳过公司构建，可与 dry-run 组合，不代表编译通过 |

- 脚本从 Homebrew、macOS java_home、SDKMAN、常见 Linux 路径寻找 JDK 21。默认先检查 JDK/公司父 POM，再 stage 五模块并改写包名、`rsync --delete` apply、编译。父 POM 不可解析时失败，不静默绕过。
- 镜像独有文件须加入 [exclude](scripts/sync-campusclaw-exclude.txt) 的相应同步范围，否则可能被 apply 删除；当前保护公司 Skill 树与 CampusMateConfigurationTest。
- application.properties 手工维护且不被覆盖；campusmate.base-url 默认 `https://localhost:8591`，由 CAMPUSMATE_BASE_URL 覆盖，不混入 standalone Actuator 配置。classpath 白名单包含 mapper、schema.sql、i18n、Spring AutoConfiguration.imports，以同步脚本精确路径为准。
- 模块侧 GaussDB 发布材料保留；镜像仅输出 `campusclaw/scripts/install/initdb_gaussdbv5.sql`，由公司 header 模板与 canonical `install/session_schema.sql` 表 DDL 组合。公司 database/schema/owner/grant 头须精确保留，不含 BEGIN/COMMIT，每个 CREATE TABLE 前紧邻对应 DROP TABLE IF EXISTS。
- 镜像不保留旧 classpath GaussDB 目录，不发布空初始数据、权限占位或 upgrade README。scripts/install 全部生成，不放手工文件；外置 SQL 不受 `--skip-resources` 影响。
- 每个 clone 用 `git config core.hooksPath scripts/git-hooks` 启用 [pre-push](scripts/git-hooks/pre-push)。push range 触及 modules/、campusclaw/ 或 scripts/sync-campusclaw* 时检查镜像一致性，其他变更跳过。hook 用 dry-run + no-verify 检查 rsync 内容差异及受管资源更新/删除，不替代编译验证。
- 本手册与 docs/ 不在镜像范围；模块改动交付前必须同步并说明镜像编译验证结果。

## 设计文档与参考入口

- 特性/模块设计在 `docs/designs/<feature-slug>.md` / `<module>.md`，采用 Markdown，涵盖背景、定义、架构/数据流、理由、边界、性能/DFX、契约、测试与验证；已有文档优先维护，普通指引修订不新增架构记录。
- 先读相关源码和设计全文；记录分析 commit、仓库相对路径及符号/基线行号。区分现状、目标与理由；pi 为基线时必须有 pi 证据，未实现方案标 target-only。主动差异分类为产品约束、安全加固或架构变化。
- 正文只保留当前有效设计、理由、证据、缺口、验收标准。规则/行为变化更新版本；已有版本历史每项一句，详细过程交给 Git，不堆积审批和对话记录。
- 图统一 PlantUML，禁止 Mermaid；每主题一个 diagram.puml，稳定命名 @startuml block，全内容 English/ASCII。代码派生图写源码路径/符号及 Observed behavior、Design reason 或 Java divergence 注释，不把 Java 目标当 pi 现状。
- 主题目录运行 `plantuml -tsvg diagram.puml`；提交生成 SVG，Markdown 引图并在图下链接 `diagram.puml#L...`，不复制图定义或手改 SVG。验证无 Mermaid、PUML ASCII、生成成功、SVG XML、引用/锚点存在、图源同步及 diff-check；工具缺失明确报告。
- 独立 ADR 仅用于须长期解释的重大取舍；新记录为 `docs/decisions/NNNN-<slug>.md`，全局递增编号不复用。包含 Status、Date、Context、Decision、选项取舍、选择理由、Consequences、Related。取代旧 ADR 时标 Superseded 并双向关联；已有 HTML 和历史证据保留，设计正文链接适用 ADR。
- 优先复用 JDK/已有库；新 Maven 直接依赖在设计阶段说明必要性、用途、主要传递依赖、替代方案、不添加的影响及体积/维护成本，未核验依赖图则注明。移除能力时检查其他消费者后决定依赖去留。

| 入口 | 用途 |
|---|---|
| [README](README.md)、[前端 README](frontend/README.md) | 快速开始、部署/Provider、调试客户端 |
| [模块架构](docs/module-architecture.md) | 模块、包、依赖及源码证据 |
| [设计目录](docs/designs)、[ADR 目录](docs/decisions) | 模块/特性设计与重大架构决策 |
| [工具系统](docs/designs/tool-system-v2.md)、[Mate 客户端](docs/designs/mate-tool-client.md) | 三入口/八工具、远端工具契约 |
| [Sandbox 清理](docs/designs/sandbox-cleanup.md) | 本地 Sandbox 退役边界 |
| [HTTP V1 映射](docs/plans/campusclaw-http-v1-implementation.md) | 落地与验证；字段契约在独立设计仓库维护，本仓不复制 OpenAPI 或公共 WebSocket 契约 |
| [公司镜像](docs/designs/campusclaw-corporate-module.md)、[同步脚本](scripts/sync-campusclaw.sh) | 构建、资源白名单、SQL 与排除规则 |

项目事实核对基线：`9dd553db83b855a6fbe8c1b46103b27efc178e54`。源码入口：`pom.xml` / `modules/*/pom.xml` 的模块与依赖、`codecheck.xml` 的检查配置、`scripts/sync-campusclaw.sh` 的 MODULES/SRC_PKG/DST_PKG/validate_staged_tree，以及模块架构列明的运行时符号。后续以适用源码和有效设计为准。
