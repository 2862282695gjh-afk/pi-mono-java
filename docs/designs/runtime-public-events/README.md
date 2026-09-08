# Runtime 公共事件数据模型

| 属性 | 值 |
|---|---|
| 版本 | 0.1.0 |
| 日期 | 2026-09-08 |
| 契约基线 | `pi-mono-java-design@2ee2a3211da68ad87b0d9cab353e691b00bdaebd` |
| 实现基线 | `pi-mono-java@2f52e9b8` |
| 子 agent 数据模型提交 | `b7034350`、`8ca53f58` 的 DTO、VO、类型和共享常量 |
| 本片范围 | 定义 v2 公共事件的数据对象和 JSON 形状；HTTP 入口尚未切换 |

## Context

Events v2 要求 POST 完整帧与 GET 历史使用同一事件对象，内部 Entry 与 Record 仍服务于上下文恢复和累计 Usage。
不能将内部持久化对象直接作为响应返回，也不能把当前旧 SSE 的 `event/id/data` 包装当成新契约。
本片先建立后续公共投影、持久化和 SSE 共用的数据类型。

## 关键定义与源码证据

以下 Java 路径相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/`。

| 分类 | 路径与符号 | 行为与理由 |
|---|---|---|
| 已有实现 | `event/RuntimeEntryCodec.java#toSseData`、`vo/RuntimeSseEventVO.java` | 旧接口从内部 Entry 转换并使用旧 SSE 包装，尚不是 v2 公共记录 |
| 本片新增 | `dto/CommittedEventDTO.java` | 内部传递 Session、事件标识、提交序号、分支锚点、形成时间和安全业务 JSON |
| 本片新增 | `vo/SessionEventResponseVO.java` | 响应只暴露公共字段；业务 VO 展开到根对象，不暴露 DTO、序号和锚点 |
| 本片新增 | `event/CommittedEventType.java` | 关闭公共完整事件的 11 种类型集合，未知类型不作为已知事件处理 |
| 已确认目标，后续实现 | 设计仓 `接口契约-v2/common.json`、POST/GET 操作文档 | 公共事件写入一次，POST 与 GET 复用；分页、执行控制和安全投影尚未由本片接入 |

pi 基线 `5cd93f688aaab89dbb6dfa4aca535f21796ae185` 的
`packages/agent/src/agent-loop.ts#runLoop` 产生内核消息和工具生命周期通知。
它没有本项目的 HTTP 公共事件表、毫秒形成时间和分支查询协议；这些是 CampusClaw 的架构变更。

## 架构与数据流

![公共事件数据对象与边界](public_event_types.svg)

[PlantUML 源码](diagram.puml#L1)

## 设计决策

见 [ADR-0074](../../decisions/0074-share-public-event-data-model.html)。持久化使用可变 `@Data` DTO；响应使用
只读字段和类型化 VO。`@JsonUnwrapped` 展开业务字段；工具结果显式声明 `isError` JSON 属性，
避免 JavaBean 布尔命名推断产生错误的 `error` 字段。可选字段为 null 时省略；delta 的 `createdAt` 省略。
参数校验仍由未来请求 VO 承担，响应对象不添加请求校验。可变集合的深复制由后续投影装配负责。

## 边界情况与性能

完整帧的形成时间、合法 phase、必需业务字段和安全错误码须在投影/写入阶段保证，数据对象本身不执行数据库或业务校验。
公共工具结果只定义文本块；思考只承载允许公开的摘要，不意味着可以公开内核所有 thinking 内容。
消息附件最多 4 个、标识为 32 位十六进制、拒绝说明最多 4096 字符；共同约束集中在 `ClawConstants.RuntimeApi`。
不新增线程、缓存、数据库访问或 Maven 依赖，使用已有 Jackson、Lombok 和 JDK 21。

## 契约与交付范围

本片提供 11 类完整事件字段及正文/摘要 delta 的复用结构，未切换 Controller、请求格式、分页或旧 SSE。
后续片必须将已提交的安全投影接到实际 GET/POST；不能根据这些数据类存在就认为完整 v2 已上线。
内部旧类型字面值保留，由公共投影转换，避免只改枚举而遗漏 SQL 白名单和历史数据。

## 测试与验证

独立序列化回归验证 `isError` 的真实 JSON 名称、delta 可选字段省略、完整帧时间与根字段展开。
JDK 21 下 `./mvnw -q -pl modules/coding-agent-cli -am spotless:apply checkstyle:check test
-Dtest=SessionEventResponseVOTest,ClawConstantsTest -Dsurefire.failIfNoSpecifiedTests=false` 通过，20 项测试无失败、错误或跳过。
Java AST 版权/方法长度检查没有 finding；已有 Unicode 正则和两个 sealed 业务接口的布局已人工补查。
测试质量缓存脚本检查 0 错误、0 提示。
企业镜像由 `sync-campusclaw.sh` 生成。本机无法解析公司 `NativeParent`，按普通本地流程使用 `--no-verify`；
企业环境编译仍未验证。PlantUML 生成、ASCII、SVG XML、Markdown 链接/锚点和 `git diff --check` 均须通过。

## 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1.0 | 2026-09-08 | 定义公共事件 DTO/VO 与共享类型，明确后续接入边界 |
