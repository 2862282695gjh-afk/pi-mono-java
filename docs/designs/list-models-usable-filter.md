# Model 模型列表

版本：2.0.0 · 2026-09-19。实施起点：`840d04405d2bcc80a41b9d3b3f16606cc4845338`。
规范来源：`pi-mono-java-design@1fd29a535c7d1ba47bfb9c6413a10ff46f492643`，
`02-服务与接口/03-Model/README.md` 2.8.2。模块边界由 [AI 模块设计](ai.md)维护。

## 1. 唯一来源

模型列表来自当前业务 Agent 已发布目录的 `.campusclaw/settings.json`：

- `bindingModels` 必须非空，且不含空白、重复模型 ID；
- `defaultModel` 必须非空，并属于 `bindingModels`；
- 默认选择、显式选择、模型列表、压缩及 Child/Cron 使用同一次准备的快照；
- 模型 ID 不透明，大小写敏感，`/` 不表示 Provider 分隔符。

没有全局目录、凭据过滤、custom 置顶或无凭据时的备用模型。设置不合法明确失败，不自动改选。
目录缺失仍由既有 Agent 发布链重建，模型变更经发布生效，不新增模型发现 HTTP 接口。

## 2. 实现路径

以下路径相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`：

| 职责 | 路径与符号 |
|---|---|
| 设置加载、校验及发布 | `runtime/AgentRuntimeManager.java`：`prepare/requireValidRuntime/validSettings` |
| 固定同一次准备的模型列表 | `runtimeapi/agent/AgentDirectorySnapshots.java`：`from/validModels` |
| 默认值、解析及清单 | `runtimeapi/model/MateRuntimeModelManager.java`：`resolveModel/listAvailableModels` |

原全局目录的服务、Manager 和专用环境凭据解析已删除。Provider 注册表只负责调用路由，
不作为第二份模型清单。公开模型查询仍沿用已有 Runtime 接口，不恢复旧设置接口或 WebSocket。

## 3. 验收

覆盖首次目录准备，以及已发布设置缺失、空白、空列表、重复、默认值不在列表时明确失败；
两个 Agent 的设置不能互相影响。默认/切换/压缩/Child/Cron 同源，解析失败不回退。
启动不探测 classpath 或 Home 模型 JSON，不打印旧目录加载日志。

## 4. 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 2.0.0 | 2026-09-19 | 模型列表只使用业务 Agent 设置快照，删除全局目录与凭据过滤。 |
| 1.1.0 | 2026-08-18 | 可用模型列表使用凭据与白名单交集。 |
