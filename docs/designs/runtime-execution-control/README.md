# 固定执行控制存储基础

| 属性 | 值 |
|---|---|
| 版本 | 0.1.0 |
| 日期 | 2026-09-08 |
| 设计契约 | `pi-mono-java-design@2ee2a3211da68ad87b0d9cab353e691b00bdaebd` |
| 变更前 Java 源码基线 | `pi-mono-java@fd556dce3cfa12e5e834b6e9b8f835f10e7d67c8` |
| 本片实现提交 | `8ec383dd0ee6a5a8fe7e72b9e97bba8f71c8f8da` |
| pi 源码基线 | `pi@4af9d21d3b4d664e4a29fcabfec85171077248e3` |
| 范围 | 固定执行、结果段和完整事件关联的低层数据库存储；不包含 Events v2 HTTP 受理和跨实例轮询 |

## Context

设计仓的 `chat-events-v2-design.md` §4.4～§4.5 要求在执行开始前固定内部执行身份，
把原 `user.message.eventId` 作为根事件，并为初始响应和每次确认续跑建立不同结果段。
这样迟到的中断不能只凭 Session 当前状态误停下一轮，确认续跑也不会把新输出写回已经关闭的响应。

变更前 Java 仅在 `RuntimeActiveExecution.runId` 保存进程内标识；另一个服务实例不能据此绑定控制目标。
本片增加低层持久化边界，为后续原子受理、停止和确认竞争提供数据库依据。

## 源码证据与设计理由

| 分类 | 路径与符号 | 观察与理由 |
|---|---|---|
| 变更前 Java | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/runtime/RuntimeActiveExecution.java#beginRun/runId` | 标识随 JVM 内对象存在，不能用于跨实例事务校验 |
| 变更前 Java | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeSessionRepository.java#acceptUserEvent/finishExecution` | Session 已有 idle/running 准入事务，但没有根执行、结果段和控制事件关联 |
| 本片实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/RuntimeExecutionControlRepository.java` | 定义登记、精确读取、完整事件关联、confirming 关段和真实终态 CAS |
| 本片实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeExecutionControlRepository.java` | 统一先锁 Session，再锁固定执行；拒绝过期 segment 和重复终态 |
| 本片实现 | `modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql` | 三张 `t_` 表保存根执行、结果段和段内完整事件顺序；toolCallId 使用 TEXT |
| 本片实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeSessionRepository.java#completeCleanup` | Session 清理先删除段事件、段和根执行，避免控制数据永久残留 |

pi `packages/agent/src/agent-loop.ts#runAgentLoop` 使用调用方提供的 `AbortSignal` 驱动一轮循环，
`prepareToolCall` 在工具执行前再次检查该信号。pi 没有 CampusClaw 的共享数据库、HTTP Session 资源或
跨实例路由。本片的固定数据库身份属于 CampusClaw 架构变化，不把它描述为 pi 已有能力。

## 存储关系

![固定执行、结果段和事件关联](runtime_execution_control_store.svg)

[PlantUML 源码](diagram.puml#L1)

`t_session_executions` 为一个根消息保存不可复用的 `execution_id`、公开 `root_event_id`、当前
`segment_id` 和控制状态。同一 Session 只允许一条非 TERMINAL 执行。
`t_session_execution_segments` 按序保存初始段和确认续跑段；关闭段时固定唯一 idle 事件及原因。
`t_session_execution_segment_events` 只关联实际属于该 HTTP 结果段的完整事件，后续中断回执不会
因此自动进入原消息段。

## 事务与锁顺序

本片 Repository 的状态迁移使用短事务，先锁 `t_sessions`，再锁固定执行；登记要求 Session 已经是
running。后续应用片会在同一外层事务中继续分配 Session 统一序号、写 Entry 和公共完整事件，最后写
段关联。该组合事务不在本片，当前低层 API 不能单独证明 Events v2 已完成原子受理。

`markConfirming` 只关闭当前结果段并把根执行保留为 CONFIRMING；它不会把 Session 改为 idle。
`markTerminal` 同时校验 executionId、rootEventId 和当前 segmentId，只允许 done、failed、terminated
三个真实结束原因。状态与原因使用 typed enum；数据库保存既定大小写字面值。

## 边界与后续

本片没有实现 `user.interrupt` 或 `user.tool_confirmation` 的 HTTP 联合输入、公共事件原子组合写、
确认决定一次消费、本地提交后唤醒、批量控制检查、有界等待登记或结果补读，也没有退役旧控制接口。
这些能力必须在后续应用片接入本存储后单独验收。原执行凭据不会写入三张表。

没有新增 Maven 依赖。SQL 只进入首次发布的全量安装脚本；表名为小写蛇形 `t_` 前缀，所有
`COMMENT ON` 描述使用中文。`pending_tool_call_id` 使用 TEXT，不对提供商 Tool Call ID 增加未确认上限。

## 验证

2026-09-08 在 Docker amd64 仿真的 openGauss 7.0.0-RC3 build01b7e318 上验证：

- 固定根执行与初始段能登记和读取，300 字符 Tool Call ID 可进入 confirming；Session 仍保持 running。
- 过期 segment 和重复终态不能覆盖已提交终态。
- 删除完成的 Session 后，段事件、段和根执行三张控制表全部清空，tombstone 继续保留。
- 独立审查执行完整 `RuntimeSessionRepositoryOpenGaussIT`：29 项通过，无失败、错误或跳过。
- `spotless:apply`、`checkstyle:check`、`test-compile`、聚焦真实数据库测试和 `git diff --check` 通过。

企业镜像通过生成同步检查；本地无法解析企业 `NativeParent:26.0.0-SNAPSHOT`，企业镜像编译尚未验证。

该数据库环境证明 SQL、MyBatis 映射和事务行为可运行，不构成企业生产性能或延迟承诺。

## 设计决策

见 [ADR-0075](../../decisions/0075-persist-runtime-execution-control.html)。

## 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1.0 | 2026-09-08 | 增加固定根执行、结果段、段内事件关联、typed 状态和 Session 清理边界 |
