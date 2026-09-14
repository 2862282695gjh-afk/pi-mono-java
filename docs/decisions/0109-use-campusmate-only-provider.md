# ADR-0109：模型调用收敛为 CampusMate Provider

| 项目 | 内容 |
|---|---|
| Status | Accepted |
| Date | 2026-09-15 |
| Source baseline | `29ebec6a8cad99896414211cda28c6fffec71bee` |
| Approved design | `pi-mono-java-design@0a74625e0dd4ea6387b6765c7cb0f9105970d05c`，模型管理 Provider 契约 2.7.0 |
| Reference baselines | Java `f61906b9`；pi `5cd93f688aaab89dbb6dfa4aca535f21796ae185` |
| Change class | 产品约束、架构变化、依赖收敛 |
| Supersedes in part | [ADR-0025](0025-mate-chat-provider-and-usage-records.html)、[ADR-0026](0026-unify-campusmate-client-configuration.html) 中的旧 Provider 身份；其余 Chat、Usage 与共享配置决策继续有效 |

## Context

基线同时装配 CampusMate 通用 Provider 和 Anthropic、OpenAI Chat、OpenAI Responses、Mistral
第三方适配器，并在 `ModelRegistry` 内置多家模型。产品模型调用已由 CampusMate 管理上游路由、
模型替换和凭据，继续保留厂商 SDK、默认模型与多条出站路径会扩大依赖和配置边界。

旧通用 Provider 身份 `mate-model-manager` 描述内部组件来源，没有直接表达产品服务身份。
Runtime Entry 会保存 Assistant 的 Provider 原字符串，压缩、Child 与 Cron 又依赖 Provider 和模型
ID 的严格比较，因此身份切换必须明确历史边界。

## Decision

产品默认只装配 `CampusMateProvider`，Provider ID 为 `campusmate`，`Provider` 枚举成员为
`CAMPUSMATE`。Runtime 继续使用同一受管 Agent 快照中的 Mate 模型 ID，并以该身份创建后续
Assistant 和内部历史记录。

删除 Anthropic、OpenAI Chat、OpenAI Responses 和 Mistral 内置适配器、专属测试、全部内置
第三方模型以及 `anthropic-java`、`openai-java` 直接依赖。`ModelRegistry` 保留注册、查询、清理、
成本计算和 classpath/用户 JSON 顺序加载；没有外部 JSON 时为空，不创建 CampusMate 模型。

保留 `AiProvider`、`AiProviderRegistry`、`ApiProvider` 和 `ApiProviderRegistry`。调用优先按
Provider 身份路由，未命中时才查询显式注册的 API 适配器；默认 API 注册表可为空。没有适配器
时明确失败，不外联，也不自动转到 CampusMate。

这是直接切换：不提供旧 ID 别名、枚举兼容解析、旧类转发或 SQL/JSONL 迁移。既有 `_provider`
按原字符串恢复，`mate-model-manager` 与 `campusmate` 不视为同一模型身份。

CampusMate 的 `campusmate.*` 配置、`POST /mate-service/v1/LLM/chat`、内部
`openai-completions` API、请求映射、SSE、reasoning 签名、Usage、错误码和取消语义保持不变。
辅助类 `MateChatRequestMapper`、`MateChatSseParser` 与 `MateReasoningSignature` 保留现名。

## 选项与理由

| 选项 | 收益 | 代价与结论 |
|---|---|---|
| 保留全部厂商适配器和内置模型 | 可直接访问第三方端点 | 重复 CampusMate 的上游职责并保留两套 SDK；不采用 |
| 删除适配器但保留旧身份别名 | 旧字符串仍可路由 | 新旧身份会被误认为等价，削弱历史和选择边界；不采用 |
| 单一 CampusMate Provider 直接切换 | 一个产品身份、一条默认出站路径、较小依赖图 | 旧身份不再可调用，外部模型需显式 JSON 和适配器；采用 |

## Consequences

默认 Spring 装配只有 CampusMate `AiProvider`，API 注册表与模型目录都允许为空。第三方 SDK 的
专属传递依赖退出 AI 模块；Jackson、Reactor、Netty、JSON Schema、日志和 MockWebServer 等共享
依赖继续保留。本决策没有新增依赖。

历史记录仍可按原值读取和写回，但不会触发新 Provider，也不会满足新身份的模型一致性检查。
需要第三方协议的嵌入方必须自行注册对应 `ApiProvider` 并提供模型定义。

## Related

- [AI 模块设计](../designs/ai.md)
- [CampusMate Chat Provider](../designs/model-manager-chat-v2.md)
- [CampusMate 共享配置](../designs/campusmate-shared-config.md)
- [ADR-0025：原 Chat 与 Usage 决策](0025-mate-chat-provider-and-usage-records.html)
- [ADR-0026：原共享配置决策](0026-unify-campusmate-client-configuration.html)
