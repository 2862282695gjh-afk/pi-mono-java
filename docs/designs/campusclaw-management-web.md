# CampusClaw 公司集成管理 Web 面关闭设计

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.3 |
| 变更前源码基线 | `origin/main@5dfa22ada4c7958eff6b4de5f1c718362805b6ee` |
| 已评审实现 | `c03bd5da488ae5a1a5c80386de64a4b5e7c8d1c9` |
| 本次分析基线 | `origin/main@3f136e792c5301673ce28cd3debb1b3528dc702d` |
| 实现分支 | `codex/remove-redundant-actuator-exclusions` |
| 适用范围 | `campusclaw` 公司环境专用 Spring Boot 配置与回归测试 |
| 变更类型 | 公司集成约束、安全加固 |
| 决策状态 | ADR-0038 已被 ADR-0040 替代 |

> 公司镜像相关路径和标识按 2026-09-01 的当前仓库位置展示；历史提交 SHA 仍是对应行为证据。

## 1. Context

`campusclaw` 作为公司父项目的子模块运行。公司父 POM 会传递引入 Actuator；该父 POM 及其运行环境不在本仓库，因此这是用户提供的外部集成条件，不作为仓库源码观察结论。

变更前，公司环境专用 `application.properties` 已设置 `management.endpoints.enabled-by-default=false`，并维护 30 项 Actuator 自动配置排除项，其中包含 `WebEndpointAutoConfiguration` 和 `ManagementServerAutoConfiguration`。它没有显式关闭管理端口，也没有排除通用及 Servlet 管理上下文自动配置。直接把原清单替换成 3 项虽然更短，但会同时恢复原来被排除的健康检查、指标、审计等自动配置，风险超出本次启动问题的边界。

最初采用纯追加方式：保留已有清单，增加关闭管理端口的属性以及两个管理上下文排除项。公司环境启动验证随后出现 `NoSuchBeanDefinitionException: ParameterValueMapper`。调试确认 Spring Boot 3.4.1 的 `EndpointAutoConfiguration.endpointOperationParameterMapper` 提供该公共 Bean，而公司环境中仍启用的 Actuator 配置需要注入它。

修正后的方案删除 `EndpointAutoConfiguration` 这一项排除，保留其余历史排除项和新增的管理 Web 关闭配置。这样既恢复自动配置依赖完整性，也不开放管理 HTTP 面。通用 `modules/coding-agent-cli` 不直接依赖公司父 POM，配置保持不变。

升级到 Spring Boot 3.4.5 后，对 31 项排除逐项复核发现两类维护问题：一类是已经不存在或拼写不再匹配的类名；另一类是与 `management.server.port=-1` 或 `management.endpoints.enabled-by-default=false` 重复表达同一关闭语义的纵深保护。当前产品决策是不在这一场景保留冗余保护，而由一个配置项负责一个关闭语义；仍会独立创建 Bean 或产生运行时副作用的自动配置继续显式排除。

## 2. 关键定义与源码证据

### 2.1 变更前观察

以下观察来自变更前基线 `origin/main@5dfa22ada4c7958eff6b4de5f1c718362805b6ee`：

| 观察到的行为 | 仓库源码证据 |
|---|---|
| 公司镜像默认禁用全部 Endpoint，但未设置管理端口 | `campusclaw/src/main/resources/application.properties:1-2` |
| 公司镜像维护 30 项 Actuator 排除，已有 `WebEndpointAutoConfiguration`，但没有两个 Management Context 排除 | `campusclaw/src/main/resources/application.properties:3-33` |
| 业务 HTTP 服务监听独立的 `server.address` 和 `server.port` 配置 | `campusclaw/src/main/resources/application.properties:37-38` |
| 本仓独立镜像 POM 没有直接声明 Actuator；公司父 POM 行为无法在仓库内观察 | `campusclaw/pom.xml:25-62` |
| 仓库使用 Spring Boot 3.4.1 作为本地构建基线 | `pom.xml:30-31` |
| 同步脚本把应用配置视为公司侧手工维护资源，不从通用模块覆盖 | `scripts/sync-campusclaw.sh:8-16`、`:42-50` |

### 2.2 公司环境验证

以下行为来自公司父 POM 与最终 Classpath 下的外部集成验证，不作为本仓库源码观察结论：

| 验证结果 | 解释 |
|---|---|
| 保留 `EndpointAutoConfiguration` 排除时，启动抛出缺少 `ParameterValueMapper` 的 `NoSuchBeanDefinitionException` | 历史排除清单切断了 Actuator 公共基础 Bean 的提供方，但仍有消费者被启用 |
| 删除该项排除后，公司环境可以启动 | 恢复公共端点基础设施满足了公司自动配置依赖 |

Spring Boot 3.4.1 上游源码证据：`EndpointAutoConfiguration.endpointOperationParameterMapper` 创建 `ParameterValueMapper`；`JmxEndpointAutoConfiguration.jmxAnnotationEndpointDiscoverer` 是启用 JMX 时的一个消费者。截图中的最底层异常没有携带具体消费 Bean 名，因此 JMX 仅作为上游消费模式说明，不声明为本次公司环境中已观察到的唯一消费者。

### 2.3 目标配置语义

- **业务端口：** `server.port` 继续提供 CampusClaw HTTP/SSE API，不受本次变更影响。
- **管理端口：** `management.server.port=-1` 按 Spring Boot 管理端口契约关闭管理服务器。
- **Endpoint 默认值：** 保留 `management.endpoints.enabled-by-default=false`。
- **端点基础设施：** 不排除 `EndpointAutoConfiguration`，保留其提供的 `ParameterValueMapper` 等公共 Bean。
- **单一关闭语义：** 不再额外排除由前两项属性已经关闭的 Endpoint 和 `ManagementContextAutoConfiguration`。
- **自动配置边界：** 只保留仍会独立创建 Bean 或产生副作用的自动配置排除，包括 Health Contributor、Metrics、Observation、Web Endpoint 基础设施和 Servlet 管理上下文基础设施。
- **版本对齐：** 删除 Spring Boot 3.4.5 中不存在的旧 Mongo 和 Management Server 类名，并把 Elasticsearch 健康贡献者改为 3.4.5 的准确类名。

### 2.4 Spring Boot 3.4.5 源码证据

以下观察来自 Spring Boot `v3.4.5`：

| 观察到的行为 | 上游源码证据 |
|---|---|
| 负数 `management.server.port` 被解析为 `ManagementPortType.DISABLED` | `spring-boot-project/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/web/server/ManagementPortType.java#get` |
| `ManagementContextAutoConfiguration` 只为 `SAME` 和 `DIFFERENT` 端口类型启用内部配置 | `spring-boot-project/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/web/server/ManagementContextAutoConfiguration.java` 的两个内部配置类 |
| `management.endpoints.enabled-by-default=false` 把默认 Endpoint access 解析为 `Access.NONE` | `spring-boot-project/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/PropertiesEndpointAccessResolver.java#determineDefaultAccess` |
| `WebEndpointAutoConfiguration` 和 `ServletManagementContextAutoConfiguration` 不受上述两个属性统一关闭，仍可创建基础 Bean | 两个同名自动配置类的 `@Bean` 方法 |
| 3.4.5 使用 `ElasticsearchRestHealthContributorAutoConfiguration`，旧 `ElasticSearch...` 名称不在自动配置清单 | `spring-boot-project/spring-boot-actuator-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 与 `spring-boot-project/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/elasticsearch/ElasticsearchRestHealthContributorAutoConfiguration.java` |

## 3. 架构与配置流

启动时，Spring Boot 先加载 `campusclaw/src/main/resources/application.properties`，再由公司父项目传入的 Actuator 自动配置读取管理属性。`EndpointAutoConfiguration` 提供公共基础 Bean，未被排除的公司自动配置可以正常注入。`management.server.port=-1` 是关闭管理 HTTP 的唯一配置，`management.endpoints.enabled-by-default=false` 是默认关闭 Endpoint 的唯一配置；排除清单只负责阻止不受这两个属性控制的独立基础 Bean 和运行时副作用。业务 Servlet Web 服务仍使用 `server.*` 配置启动。

本次没有新增运行时组件、调用链或状态转换，三组静态配置之间不存在需要图示才能澄清的拓扑，因此不新增 PlantUML 图。

## 4. 设计决策

当前决策见 [ADR-0040：删除 CampusClaw 公司集成的 Actuator 冗余自动配置排除](../decisions/0040-remove-redundant-campusclaw-corporate-actuator-exclusions.html)。原 [ADR-0038](../decisions/0038-disable-campusclaw-corporate-management-web.html) 保留历史并标记为被替代。

### 4.1 用属性单独承担管理面关闭语义

保留 `management.server.port=-1` 和 `management.endpoints.enabled-by-default=false`，删除 10 个 Endpoint 自动配置排除以及 `ManagementContextAutoConfiguration` 排除。产品不依靠多个机制重复表达相同关闭意图，避免升级时维护长清单和已经失效的类名。

### 4.2 保留有独立运行时作用的排除

Health Contributor、Audit、Metrics、Observation、HttpExchanges、`WebEndpointAutoConfiguration` 和 `ServletManagementContextAutoConfiguration` 不由管理端口或 Endpoint 默认 access 统一关闭。它们可能创建注册表、过滤器、发现器、健康检查或管理上下文基础 Bean，因此继续排除。Elasticsearch 条目改用 3.4.5 的准确类名；旧 Mongo 包名和不存在的 `ManagementServerAutoConfiguration` 删除。

### 4.3 公司专用配置不进入通用模块

通用模块的 `application.yml` 不引入这些属性。它没有公司父 POM 约束，而且仓库约定 `campusclaw/application.properties` 是手工维护的环境专用配置。该差异属于公司集成约束和管理面安全加固，不是通用产品架构变化。

### 4.4 用公司镜像专用测试锁定配置

测试直接加载真实 `application.properties`，断言管理端口和 Endpoint 默认开关，并按顺序精确校验只剩 18 个有独立作用的排除项。精确集合同时防止冗余 Endpoint、失效旧类名和 `ManagementContextAutoConfiguration` 回流。测试文件加入同步排除清单，使后续模块镜像同步不会删除它。

## 5. 边界情况

- 不允许用单个 Endpoint 的 enabled/access 配置覆盖默认关闭策略；若产品以后需要开放 Endpoint，应形成新的显式决策，而不是恢复冗余排除。
- `WebEndpointAutoConfiguration` 和 `ServletManagementContextAutoConfiguration` 仍有独立 Bean 创建行为，继续排除。
- `ManagementContextAutoConfiguration` 在端口为 `-1` 时没有匹配的 `SAME` 或 `DIFFERENT` 内部配置，不再排除。
- 类路径中没有 Actuator 时，Spring Boot 会忽略不存在的排除类名；因此测试必须精确锁定目标列表，不能把“配置可以加载”当作类名有效性的证明。
- 业务端口仍由 `SERVER_PORT` 或默认值 `8080` 决定。
- 本次关闭的是管理服务器和管理 Web 上下文，不修改 CampusClaw 的业务 HTTP/SSE 路由。
- 公司父 POM 不在仓库内，本地测试只能验证配置加载和关键配置值，不能替代公司工程的最终启动验证。
- Windows 是用户报告的公司本地运行环境；本次修改是平台无关的 Spring Boot 配置，不新增 Windows 启动或安装支持。

## 6. DFX

- **可靠性：** 保留 `EndpointAutoConfiguration` 公共基础 Bean，避免公司自动配置因依赖缺失而终止启动。
- **安全性：** 管理 HTTP 由负数管理端口关闭，Endpoint 由默认 access 关闭；本场景不增加重复保护。
- **可维护性：** 排除项从 31 项收敛为 18 项，删除无效类名，并通过精确集合测试避免清单再次漂移。
- **性能：** 不新增线程、连接、缓存或请求处理逻辑。
- **可观测性：** 不改变业务日志链路；管理 Endpoint 不作为 CampusClaw 观测接口暴露。

## 7. 契约改动

- 无 Java API、业务 HTTP/SSE、数据库或消息契约变化。
- 公司部署继续固定 `management.server.port=-1` 和 `management.endpoints.enabled-by-default=false`。
- `spring.autoconfigure.exclude` 删除 10 个重复的 Endpoint 排除、`ManagementContextAutoConfiguration` 和 2 个已不存在的旧类名，并修正 Elasticsearch 自动配置类名。

## 8. 实现证据

初始关闭行为来自已评审实现 `c03bd5da488ae5a1a5c80386de64a4b5e7c8d1c9`；本次目标实现基于 `origin/main@3f136e792c5301673ce28cd3debb1b3528dc702d`：

| 目标行为 | 实现证据 |
|---|---|
| 关闭管理端口并保留 Endpoint 默认禁用 | `campusclaw/src/main/resources/application.properties:1-4` |
| 只保留有独立运行时作用的 18 个自动配置排除 | `campusclaw/src/main/resources/application.properties:5-23` |
| 业务服务端口保持原配置 | `campusclaw/src/main/resources/application.properties:27-28` |
| 真实配置加载测试精确覆盖属性和目标排除集合 | `campusclaw/src/test/java/com/huawei/hicampus/claw/codingagent/config/ManagementConfigurationTest.java:19-56` |
| 公司专用测试受镜像同步保护 | `scripts/sync-campusclaw-exclude.txt:10` |

## 9. 测试与验证

- 执行 `ManagementConfigurationTest`，验证真实配置解析结果和 18 项目标排除集合。
- 对照 Spring Boot 3.4.5 `AutoConfiguration.imports`，验证所有保留类名存在且可排除。
- 执行 `campusclaw` 完整 Maven `verify`。
- 执行镜像同步 dry-run，确认公司专用配置和测试不会被覆盖或删除。
- 检查可执行 JAR 中的 `application.properties`，确认打包值与源码一致。
- 执行 `git diff --check`、Markdown Mermaid 禁用检查和文档链接检查。

## 10. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.3 | 2026-09-01 | 更名为 CampusClaw 公司集成管理 Web，并对齐公司镜像的新目录、Java 包和同步排除清单。 |
| v1.2 | 2026-08-31 | 删除与管理端口及 Endpoint 默认 access 重复的纵深保护，清理失效类名并精确锁定剩余排除集合 |
| v1.1 | 2026-08-29 | 根据公司启动验证恢复 Actuator Endpoint 公共基础配置，并增加防回归断言 |
| v1.0 | 2026-08-29 | 保留原 Actuator 排除清单，追加管理端口和管理 Web 上下文关闭配置 |
