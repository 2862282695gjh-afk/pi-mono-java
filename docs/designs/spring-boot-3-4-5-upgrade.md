# Spring Boot 3.4.5 升级评估与实施设计

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.1 |
| 状态 | Implemented |
| 更新日期 | 2026-09-01 |
| 变更前源码基线 | `origin/main@96c5c01069cce6179d068862d94b8feddfee8fd0` |
| 已验证实现 | `994bdff13100ce363214e74abb70dfafbd1ce1b5` |
| 实现分支 | `codex/upgrade-spring-boot-3-4-5` |
| 变更类型 | 依赖维护、安全修复；无架构变更 |

> 公司镜像相关路径和标识按 2026-09-01 的当前仓库位置展示；历史提交 SHA 仍是对应行为证据。

## 1. Context

变更前，根 POM 用单一 `spring-boot.version=3.4.1` 同时管理 Spring Boot BOM 和 Maven
打包插件。四个 Reactor 模块继承该根 POM；当时的独立公司镜像也使用同一根 POM，
因此当次修改一个属性会同时改变编译、测试、运行时依赖与可执行 JAR 打包器。公司镜像现已
切换到 `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT`，这属于后续架构变化，不改写当次验证结论。

目标是在不跨越 Spring Boot 3.4 维护线的前提下升级到 3.4.5，获取上游缺陷、
安全与依赖修复，并通过两套完整构建证明当前代码无需适配。Spring 官方发布说明记录了
[3.4.5 的 62 项修复、文档改进和依赖升级](https://github.com/spring-projects/spring-boot/releases/tag/v3.4.5)；
官方安全公告确认 3.4.5 修复了影响 3.4.0–3.4.4 的
[`CVE-2025-22235`](https://spring.io/security/cve-2025-22235/)。

## 2. 关键定义与源码证据

### 2.1 变更前观察

以 `origin/main@96c5c01069cce6179d068862d94b8feddfee8fd0` 为观察基线：

| 观察到的行为 | 仓库源码证据 |
|---|---|
| Spring Boot 版本集中为 3.4.1 | `pom.xml:30-31`，`spring-boot.version` |
| 同一属性导入 BOM | `pom.xml:49-56`，`spring-boot-dependencies` |
| 同一属性管理打包插件 | `pom.xml:212-216`，`spring-boot-maven-plugin` |
| 主服务使用 Web、JDBC、Validation 和 Test Starter | `modules/coding-agent-cli/pom.xml:39-60`、`:93-104` |
| 公司镜像继承根 POM 并使用同类 Starter | `campusclaw/pom.xml:11-16`、`:36-62`、`:130-141` |
| 公司镜像维护 Actuator 自动配置排除清单 | `campusclaw/src/main/resources/application.properties:1-35` |
| 公司镜像测试锁定管理端口和关键排除项 | `campusclaw/src/test/java/com/huawei/hicampus/claw/codingagent/config/ManagementConfigurationTest.java:19-42` |

仓库中没有 `EndpointRequest`、Spring Security、`@MockBean` 或 `@SpyBean` 的使用。因此本应用不符合
`CVE-2025-22235` 公告列出的触发条件；升级的安全价值是不再使用受影响的 Spring Boot
版本，不表示仓库已观察到该漏洞的可利用路径。

### 2.2 实际依赖变化

用 Maven 分别以 3.4.1 和 3.4.5 解析 `campusclaw-coding-agent` 依赖树，主要变化如下：

| 组件 | 3.4.1 基线 | 3.4.5 目标 |
|---|---:|---:|
| Spring Framework | 6.2.1 | 6.2.6 |
| Tomcat | 10.1.34 | 10.1.40 |
| Reactor Core | 3.7.1 | 3.7.5 |
| Reactor Netty | 1.2.1 | 1.2.5 |
| Netty | 4.1.116.Final | 4.1.119.Final |
| Jackson | 2.18.2 | 2.18.3 |
| Apache HttpClient 5 | 5.4.1 | 5.4.3 |
| Apache HttpCore 5 | 5.3.1 | 5.3.4 |
| Micrometer | 1.14.2 | 1.14.6 |
| PostgreSQL JDBC | 42.7.4 | 42.7.5 |
| Lombok | 1.18.36 | 1.18.38 |
| SLF4J API | 2.0.16 | 2.0.17 |

MyBatis Spring Boot Starter 保持仓库显式指定的 3.0.4；HikariCP、Hibernate Validator 和 Log4j2
在当前有效依赖树中也没有变化。未观察到直接依赖的新增或删除。

### 2.3 Actuator 边界

对 3.4.1 和 3.4.5 的 `spring-boot-actuator-autoconfigure` JAR 做类清单对比后，31 个公司镜像
配置字符串在两个版本的匹配结果完全相同，没有由 3.4.5 引入的类名漂移。其中 3 个历史字符串在
两个 JAR 中都不对应精确类名：

- `ElasticSearchRestHealthContributorAutoConfiguration` 的实际类名为
  `ElasticsearchRestHealthContributorAutoConfiguration`；
- `autoconfigure.mongo.MongoHealthContributorAutoConfiguration` 的实际包路径为 `autoconfigure.data.mongo`；
- `web.server.ManagementServerAutoConfiguration` 在两个上游 JAR 中都不存在。

这是变更前已存在的公司集成风险，不是 3.4.5 回归。由于公司父 POM 和最终 Classpath 不在本仓库，
本次不擅自缩减或更正该清单，保持已验证的公司环境边界。

## 3. 架构与数据流

根 POM 的一个版本属性向下管理 BOM；BOM 为 Reactor 模块和公司镜像提供传递依赖版本；
同一属性还选择可执行 JAR 的 Spring Boot Maven Plugin。升级不改变模块依赖方向、运行时请求流、
数据库契约或部署配置。该变化是单一 BOM 版本传播，不新增组件、分支或状态转换，因此不增加
PlantUML 图。

## 4. 设计决策

正式决策见 [ADR-0039：将 Spring Boot 升级到 3.4.5](../decisions/0039-upgrade-spring-boot-3-4-5.html)。

### 4.1 保持 3.4 维护线

只把根 POM 属性改为 3.4.5，不同时调整显式锁定的 MyBatis、JobRunr 或 LLM SDK，也不跨越到
Spring Boot 3.5。这将归因边界限定为一次补丁级升级。

### 4.2 当次升级保持 BOM 和打包插件同版本

当次升级不为子模块或公司镜像增加局部覆盖，继续由 `spring-boot.version` 同时控制 BOM 与
打包插件，避免编译 Classpath 和打包器版本分裂。当前公司镜像已切换为由 `NativeParent` 管理
依赖和插件版本，必须在公司 Maven 环境单独验证，不再从根属性继承。

### 4.3 保留公司环境复验门槛

普通仓库环境只能验证根 Reactor 和镜像生成一致性，不能模拟公司父 POM 传递的 Actuator
最终 Classpath，也可能无法解析 `NativeParent`。因此公司 Maven 环境中的镜像 `verify` 和启动
验证是强制门禁；缺少该环境时必须明确记录未执行，不能宣称公司镜像构建通过。

## 5. 边界情况

- JDK 继续固定为 21；3.4.4 新增的 Java 24+ Tomcat APR 默认行为不影响当前运行时。
- 应用没有 Spring Security 或 `EndpointRequest.to()`，不把安全公告描述成已观察的仓库漏洞。
- 没有空 YAML map、`@MockBean` 或 `@SpyBean` 的已知使用需要适配。
- 公司镜像的 3 个历史 Actuator 字符串保持不变，需在独立公司集成任务中处理。

## 6. DFX

- **安全性：** 移除受 `CVE-2025-22235` 影响的 Spring Boot 3.4.1 组件版本。
- **可靠性：** 引入 Spring Framework、Tomcat、Reactor、Netty、Jackson 和 JDBC 的维护修复。
- **可维护性：** 继续只在根 POM 维护一个 Spring Boot 版本源。
- **性能：** 未改变线程、连接池、缓存或请求上限；本次不声明性能收益。
- **可观测性：** Log4j2 版本和日志配置保持不变。

## 7. 契约改动

无 Java API、HTTP/SSE、JSON、数据库、消息、配置键或部署入口契约改动。用户可见变化仅为构建与
运行时基线版本升至 Spring Boot 3.4.5。

## 8. 实现证据

已验证实现 `994bdff13100ce363214e74abb70dfafbd1ce1b5` 包含：

| 目标行为 | 实现证据 |
|---|---|
| BOM 与打包插件统一使用 3.4.5 | `pom.xml:30-31`、`:49-56`、`:212-216` |
| 使用者文档声明 3.4.5 | `README.md:1-4` |
| 仓库开发手册声明 3.4.5 | `CLAUDE.md:4-7` |

## 9. 测试与验证

- `./mvnw -Dspring-boot.version=3.4.5 verify`：通过，五个 Reactor 项目成功，服务模块 585 个测试零失败。
- `./mvnw -Dspring-boot.version=3.4.5 -f campusclaw/pom.xml verify`：通过，1292 个测试零失败。
- 版本落盘后执行 `./mvnw verify` 和 `./mvnw -f campusclaw/pom.xml verify`：通过。
- `dependency:tree` 基线/目标对比：通过，已记录实际传递依赖变化。
- 3.4.1/3.4.5 Actuator JAR 类清单对比：未发现升级导致的清单匹配变化。
- 镜像同步 dry-run：通过，无内容变更待同步。
- PlantUML 生成、SVG XML、Markdown 图片与源码链接、Puml ASCII、Mermaid 禁用和 `git diff --check`：通过。

## 10. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.1 | 2026-09-01 | 对齐当前公司镜像路径，并明确切换 NativeParent 是升级完成后的独立架构变化。 |
| v1.0 | 2026-08-31 | 记录 3.4.1 至 3.4.5 的版本决策、依赖变化、风险评估与验证证据 |
