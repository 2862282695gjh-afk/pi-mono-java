# CampusMate 客户端共享配置设计

| 属性 | 值 |
|---|---|
| 文档版本 | 1.0.0 |
| 状态 | 已实现于 `codex/campusmate-shared-config`，待评审合入 |
| 更新日期 | 2026-08-26 |
| 外部设计基线 | `/Users/z/设计`：`c250e3f07536871d3d676242e552a5eb4346b0c7` |
| 外部设计文档 | `campusmate-shared-client-configuration/README.md` 2.1.0 |
| 实施前源码基线 | `56be8eee59415a5f86658d6635a7b7e8891263d3` |
| 决策记录 | [ADR-0026](../decisions/0026-unify-campusmate-client-configuration.html) |

## 1. 结论

`mate`、CampusMate Model、受管 Runtime 与 Mate Tool 是同一个 CampusMate 服务的不同消费面，
因此统一使用一个必填 `campusmate.base-url` 和一个 `campusmate.endpoints` operation 目录。
模型本地参数归入 `campusmate.model`，Runtime 本地参数归入 `campusmate.runtime`，Tool 开关归入
`campusmate.tool`。不再保留 `campusmate.model-manager.base-url`、
`campusmate.runtime.base-url` 或顶层 `mate.*` 应用配置。

![CampusMate 共享客户端配置](campusmate-shared-config/campusmate_shared_configuration.svg)

[PlantUML 源码：`campusmate_shared_configuration`](campusmate-shared-config/diagram.puml#L1)

## 2. 源码证据

### 2.1 实施前观察行为

以下证据均来自实施前源码基线 `56be8eee59415a5f86658d6635a7b7e8891263d3`：

| 源码 | 符号或基线行 | 观察行为 |
|---|---|---|
| `modules/coding-agent-cli/src/main/resources/application.yml` | L89-L108、L129-L140 | Model、Runtime、Tool 分散在 `campusmate.model-manager`、`campusmate.runtime` 与顶层 `mate`；声明三个服务地址和七个路径键。 |
| `modules/ai/src/main/java/com/campusclaw/ai/provider/mate/MateServiceModelManagerProvider.java` | `MateServiceModelManagerProvider`，L68-L76 | Model Provider 独立注入 base URL 与 Chat path。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtime/MateServiceClient.java` | `MateServiceClient`，L37-L69 | Runtime Properties 持有自己的 base URL，并在客户端注入两条 Runtime 路径。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/config/MateToolAutoConfiguration.java` | `MateToolAutoConfiguration`，L21-L81 | Tool 使用顶层 `mate.innerGWSerive` 和四个 `mate.endpoints`；Skill query 与 Runtime 实际指向相同 operation。 |
| `mate-campusclaw/scripts/install_value.sh` | 环境变量映射 | 安装边界把 `CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL` 映射到拼写错误的 `MATE_INNERGWSERIVE`。 |

这些内容是已观察到的旧实现，不代表目标设计。

### 2.2 目标实现证据

| 源码 | 符号 | 目标职责 |
|---|---|---|
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/config/CampusMateClientProperties.java` | `CampusMateClientProperties`、`Endpoints` | 绑定并校验唯一 base URL 与六个 operation path，统一拼接完整 URI。 |
| `modules/coding-agent-cli/src/main/resources/application.yml` | `campusmate` | 提供 `base-url/endpoints/model/runtime/tool` 单一配置树。 |
| `modules/ai/src/main/java/com/campusclaw/ai/provider/mate/MateServiceModelManagerProvider.java` | 注入构造器 | 使用共享 base URL、共享 Chat endpoint 与 `campusmate.model.*`。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtime/MateServiceClient.java` | `getAgentRuntime`、`querySkillInfo` | 从共享 endpoint 目录取得 Runtime 和 Skill operation。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/config/MateToolAutoConfiguration.java` | `mateToolClient` | Tool 客户端复用同一 base URL、Agent/Skill operation 与 Tool operation。 |

## 3. 配置结构与迁移

![CampusMate 配置键迁移](campusmate-shared-config/configuration_key_migration.svg)

[PlantUML 源码：`configuration_key_migration`](campusmate-shared-config/diagram.puml#L41)

```yaml
campusmate:
  base-url: ${CAMPUSMATE_BASE_URL}
  endpoints:
    model-chat-path: /mate-service/v1/LLM/chat
    agent-info-path-template: /mate-service/v1/agents/%s
    agent-runtime-path-template: /mate-service/v1/agents/%s/runtime
    skill-info-path-template: /mate-service/v1/skill/query/%s
    tool-metadata-query-path: /mate-service/v1/runtime/tools/query
    tool-execute-path-template: /mate-service/v1/runtime/tools/%s/execute
  model:
    api: openai-completions
  runtime:
    agents-root: agent
  tool:
    enabled: true
```

迁移规则：

| 旧键或变量 | 新键或变量 | 处理 |
|---|---|---|
| `campusmate.model-manager.base-url` | `campusmate.base-url` | 删除旧键，不做应用内双读。 |
| `campusmate.runtime.base-url` | `campusmate.base-url` | 删除旧键，不做应用内双读。 |
| `mate.innerGWSerive` | `campusmate.base-url` | 修复服务身份与拼写，不保留别名。 |
| 三组旧 path | `campusmate.endpoints.*` | 按 HTTP operation 去重为六条。 |
| `campusmate.model-manager.*` 本地参数 | `campusmate.model.*` | 按用户命名决策将 `model-manager` 收敛为 `model`。 |
| `CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL` | `CAMPUSMATE_BASE_URL` | 只在安装脚本边界转换；目标应用只读取新变量。 |

## 4. 校验与安全边界

- `campusmate.base-url` 必须是带 host 的绝对 HTTP(S) URI，不允许 user-info、query、fragment
  或服务路径；尾部 `/` 统一去除。
- operation path 必须以 `/mate-service/` 开头，不允许 origin、query、fragment 或 `..`。
- Agent、Runtime、Skill、Tool execute 模板必须且只能包含一个 `%s`；非模板 operation 不得包含占位符。
- 配置在 Spring 绑定和 Provider 初始化阶段失败，避免错误地址延迟到首次调用才暴露。
- 六个条目以 `HTTP method + path` 校验唯一性；Runtime 和 Tool 共享同一个 Skill query 条目。
- 安装脚本发现旧部署变量与显式新变量值冲突时失败，不静默覆盖。

## 5. HTTP 契约边界

本次是配置架构改造，不修改任何 HTTP method、path、请求体、响应体、SSE 或认证语义。
六个 operation 的实际冻结值与实施前调用一致；只消除重复配置入口。Provider 身份
`mate-model-manager` 也保持不变，`model` 仅是配置分组名称。

## 6. 验证

- 配置绑定：缺失或非法 base URL、越界 path、占位符数量与重复 operation；
- 客户端：Model、Runtime、Tool 在自定义共享 base URL 和自定义 endpoint 下请求正确 URI；
- 兼容交付：规范 YAML、手工维护的镜像 properties 与安装脚本保持同一目标键；
- 工程规则：Spotless、Checkstyle、相关测试、镜像同步、PlantUML/SVG 和 `git diff --check`。

## 7. 版本历史

| 版本 | 日期 | 变化 |
|---|---|---|
| 1.0.0 | 2026-08-26 | 实现 CampusMate 单一 base URL、六 operation endpoint 目录、`model/runtime/tool` 配置分组与启动期校验。 |
