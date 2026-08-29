# Mate CampusClaw 管理 Web 面关闭设计

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.1 |
| 变更前源码基线 | `origin/main@5dfa22ada4c7958eff6b4de5f1c718362805b6ee` |
| 已评审实现 | `c03bd5da488ae5a1a5c80386de64a4b5e7c8d1c9` |
| 实现分支 | `codex/disable-management-endpoints` |
| 适用范围 | `mate-campusclaw` 公司环境专用 Spring Boot 配置与回归测试 |
| 变更类型 | 公司集成约束、安全加固 |
| 决策状态 | Accepted |

## 1. Context

`mate-campusclaw` 作为公司父项目的子模块运行。公司父 POM 会传递引入 Actuator；该父 POM 及其运行环境不在本仓库，因此这是用户提供的外部集成条件，不作为仓库源码观察结论。

变更前，公司环境专用 `application.properties` 已设置 `management.endpoints.enabled-by-default=false`，并维护 30 项 Actuator 自动配置排除项，其中包含 `WebEndpointAutoConfiguration` 和 `ManagementServerAutoConfiguration`。它没有显式关闭管理端口，也没有排除通用及 Servlet 管理上下文自动配置。直接把原清单替换成 3 项虽然更短，但会同时恢复原来被排除的健康检查、指标、审计等自动配置，风险超出本次启动问题的边界。

最初采用纯追加方式：保留已有清单，增加关闭管理端口的属性以及两个管理上下文排除项。公司环境启动验证随后出现 `NoSuchBeanDefinitionException: ParameterValueMapper`。调试确认 Spring Boot 3.4.1 的 `EndpointAutoConfiguration.endpointOperationParameterMapper` 提供该公共 Bean，而公司环境中仍启用的 Actuator 配置需要注入它。

修正后的方案删除 `EndpointAutoConfiguration` 这一项排除，保留其余历史排除项和新增的管理 Web 关闭配置。这样既恢复自动配置依赖完整性，也不开放管理 HTTP 面。通用 `modules/coding-agent-cli` 不直接依赖公司父 POM，配置保持不变。

## 2. 关键定义与源码证据

### 2.1 变更前观察

以下观察来自变更前基线 `origin/main@5dfa22ada4c7958eff6b4de5f1c718362805b6ee`：

| 观察到的行为 | 仓库源码证据 |
|---|---|
| 公司镜像默认禁用全部 Endpoint，但未设置管理端口 | `mate-campusclaw/src/main/resources/application.properties:1-2` |
| 公司镜像维护 30 项 Actuator 排除，已有 `WebEndpointAutoConfiguration`，但没有两个 Management Context 排除 | `mate-campusclaw/src/main/resources/application.properties:3-33` |
| 业务 HTTP 服务监听独立的 `server.address` 和 `server.port` 配置 | `mate-campusclaw/src/main/resources/application.properties:37-38` |
| 本仓独立镜像 POM 没有直接声明 Actuator；公司父 POM 行为无法在仓库内观察 | `mate-campusclaw/pom.xml:25-62` |
| 仓库使用 Spring Boot 3.4.1 作为本地构建基线 | `pom.xml:30-31` |
| 同步脚本把应用配置视为公司侧手工维护资源，不从通用模块覆盖 | `scripts/sync-mate-campusclaw.sh:8-16`、`:42-50` |

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
- **自动配置边界：** 保留其余原有排除清单，并追加 `ManagementContextAutoConfiguration` 与 `ServletManagementContextAutoConfiguration`；已有的 `WebEndpointAutoConfiguration` 不重复声明。

## 3. 架构与配置流

启动时，Spring Boot 先加载 `mate-campusclaw/src/main/resources/application.properties`，再由公司父项目传入的 Actuator 自动配置读取管理属性。`EndpointAutoConfiguration` 提供公共基础 Bean，未被排除的公司自动配置可以正常注入；管理端口、Endpoint 默认开关和管理 Web 自动配置排除继续阻止管理 HTTP 面建立。业务 Servlet Web 服务仍使用 `server.*` 配置启动。

本次没有新增运行时组件、调用链或状态转换，三组静态配置之间不存在需要图示才能澄清的拓扑，因此不新增 PlantUML 图。

## 4. 设计决策

正式决策见 [ADR-0038：关闭 Mate 管理 Web 面并保留端点基础设施](../decisions/0038-disable-mate-management-web.html)。

### 4.1 保留端点基础设施并追加管理上下文保护

不把现有 30 项清单整体替换为 3 项，因为这会恢复大量与本次目标无关的 Actuator 自动配置。只删除 `EndpointAutoConfiguration` 排除，恢复 `ParameterValueMapper` 等公共基础 Bean；其余历史排除项继续保留，并追加缺失的管理端口和管理上下文边界。

### 4.2 公司专用配置不进入通用模块

通用模块的 `application.yml` 不引入这些属性。它没有公司父 POM 约束，而且仓库约定 `mate-campusclaw/application.properties` 是手工维护的环境专用配置。该差异属于公司集成约束和管理面安全加固，不是通用产品架构变化。

### 4.3 用公司镜像专用测试锁定配置

新增测试直接加载真实 `application.properties`，断言管理端口、Endpoint 默认开关和 3 个关键管理 Web 自动配置排除项，同时断言 `EndpointAutoConfiguration` 不在排除清单中。测试文件加入同步排除清单，使后续模块镜像同步不会删除它。

## 5. 边界情况

- 仅删除 `EndpointAutoConfiguration` 排除，以修复公司环境已验证的公共 Bean 缺失；其余历史排除项不放宽。
- `WebEndpointAutoConfiguration` 已存在，只保留一次，避免重复配置。
- 业务端口仍由 `SERVER_PORT` 或默认值 `8080` 决定。
- 本次关闭的是管理服务器和管理 Web 上下文，不修改 CampusClaw 的业务 HTTP/SSE 路由。
- 公司父 POM 不在仓库内，本地测试只能验证配置加载和关键配置值，不能替代公司工程的最终启动验证。
- Windows 是用户报告的公司本地运行环境；本次修改是平台无关的 Spring Boot 配置，不新增 Windows 启动或安装支持。

## 6. DFX

- **可靠性：** 恢复公共基础 Bean，避免公司自动配置因依赖缺失而终止启动；其余历史排除项保持不变。
- **安全性：** 显式关闭管理端口和管理 Web 上下文，避免公司父 POM 传入未预期的管理 HTTP 面。
- **可维护性：** 测试同时锁定管理 Web 排除和端点基础配置保留，避免后续再次形成不完整的自动配置组合。
- **性能：** 不新增线程、连接、缓存或请求处理逻辑。
- **可观测性：** 不改变业务日志链路；管理 Endpoint 不作为 CampusClaw 观测接口暴露。

## 7. 契约改动

- 无 Java API、业务 HTTP/SSE、数据库或消息契约变化。
- 公司部署配置新增固定值 `management.server.port=-1`。
- `spring.autoconfigure.exclude` 删除 `EndpointAutoConfiguration`，并在原列表末尾新增两个管理上下文类名。

## 8. 实现证据

以下内容来自已评审实现 `c03bd5da488ae5a1a5c80386de64a4b5e7c8d1c9`：

| 目标行为 | 实现证据 |
|---|---|
| 关闭管理端口并保留 Endpoint 默认禁用 | `mate-campusclaw/src/main/resources/application.properties:1-3` |
| 恢复 Endpoint 公共基础配置并追加两个 Management Context 排除 | `mate-campusclaw/src/main/resources/application.properties:4-35` |
| 业务服务端口保持原配置 | `mate-campusclaw/src/main/resources/application.properties:39-40` |
| 真实配置加载测试覆盖管理 Web 边界和 Endpoint 基础配置保留 | `mate-campusclaw/src/test/java/com/huawei/hicampus/mate/matecampusclaw/codingagent/config/ManagementConfigurationTest.java:19-43` |
| 公司专用测试受镜像同步保护 | `scripts/sync-mate-exclude.txt:10` |

## 9. 测试与验证

- 执行 `ManagementConfigurationTest`，验证真实配置解析结果以及 `EndpointAutoConfiguration` 未被排除。
- 执行 `mate-campusclaw` 完整 Maven `verify`。
- 执行镜像同步 dry-run，确认公司专用配置和测试不会被覆盖或删除。
- 检查可执行 JAR 中的 `application.properties`，确认打包值与源码一致。
- 执行 `git diff --check`、Markdown Mermaid 禁用检查和文档链接检查。

## 10. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.1 | 2026-08-29 | 根据公司启动验证恢复 Actuator Endpoint 公共基础配置，并增加防回归断言 |
| v1.0 | 2026-08-29 | 保留原 Actuator 排除清单，追加管理端口和管理 Web 上下文关闭配置 |
