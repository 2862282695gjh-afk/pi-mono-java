# Windows 子进程与流式 IO 调试经验

本文档记录 CampusClaw 在 Windows 上调试子 Agent、stdio pipe 和 Reactor 流时沉淀的通用经验。README 只保留入口说明，具体定位过程集中在这里维护。

## 1. `process.destroy()` 不会自动杀掉子进程树

### 症状

调用 `process.destroy()` 后父进程退出，但由 `.cmd` wrapper 派生的 Node.js 子进程仍然存活，并继续持有 stdin/stdout pipe handle。

### 原因

Windows 上通过 `ProcessBuilder` 启动 npm CLI 时，实际链路通常是 `cmd.exe /c <name>.cmd ...`。`process.destroy()` 只终止当前父进程，不会像 POSIX 进程组信号一样终止整棵进程树。父进程退出后，`ProcessHandle.descendants()` 还可能因为父子关系已经断开而返回空集合。

### 修复原则

先 snapshot `process.descendants()`，再终止父进程和快照中的后代，最后等待所有进程退出：

1. 在销毁父进程前获取后代进程列表；
2. 对整棵树执行 `destroyForcibly`；
3. 使用 `waitFor` 和 `ProcessHandle.onExit()` 等待进程真正退出。

参考实现：`modules/agent-core/src/main/java/com/campusclaw/agent/subagent/acp/backend/ProcessAcpBackend.java` 的 `destroyTree`。

## 2. Windows 的 `PipeInputStream.close()` 可能与 `readLine()` 互相等待

### 症状

一个线程在 `BufferedReader.readLine()` 上阻塞时，另一个线程调用 pipe 的 `close()`，关闭方可能一直挂起。相同代码在 macOS/Linux 上通常立即返回。

### 原因

Windows JDK 的 pipe 关闭路径可能等待 pending read 完成；reader 等不到 EOF，close 又等不到 reader 结束，形成互相等待。

### 修复原则

先终止持有 pipe 写端的进程树，让 reader 自然收到 EOF，再按 `client.close`、`transport.close`、`input.close` 的顺序释放资源。关闭流的兜底操作可以放到守护虚拟线程中，避免调用方被平台实现阻塞。

参考实现：`modules/agent-core/src/main/java/com/campusclaw/agent/subagent/acp/AcpTransport.java` 的 `close`。

## 3. Reactor `Sinks.Many.multicast()` 不保证跨线程事件顺序

### 症状

reader 线程发送最后一个 `TextDelta`，prompt 线程同时发送 `Done`。下游使用 `.takeUntil(Done)` 时，偶发最后一段文本被截掉。

### 原因

非 serialized multicast sink 在并发发送时可能返回 `FAIL_NON_SERIALIZED`；即使重试解决了发送失败，也不能保证两个线程的事件到达顺序。`Done` 一旦先到达，下游就会关闭，reader 线程中尚未发出的文本会丢失。

### 修复原则

让同一 producer 线程按顺序发送完整的文本事件和 `Done`。如果必须跨线程共享 sink，则需要明确的序列化机制或锁；仅使用 retry 不能修复乱序。

参考实现：`modules/agent-core/src/main/java/com/campusclaw/agent/subagent/acp/AcpClient.java` 的响应处理逻辑。

## 4. 流式异步链路需要独立 trace

TUI 可能吞掉 logback console 输出，Windows shell 之间的环境变量传递也可能不稳定。排查跨进程、跨线程问题时，建议使用 `~/.campusclaw/acp-trace.jsonl` 记录：

- `>` 和 `<`：原始 JSON-RPC envelope；
- `#`：关键阶段 checkpoint，例如 `emit`、`recv`、`done`、`destroyTree` 和 `close`；
- 每条 checkpoint：带上阶段名和数据量，例如 transcript 长度或事件数量。

参考实现：`modules/agent-core/src/main/java/com/campusclaw/agent/subagent/acp/AcpTransport.java` 的 `openTrace` 和 `note`。

## 5. 按 trace 最后一行反推阻塞点

每次定位都从 trace 最后一条已确认事件开始：

1. 找到下一条按设计应该出现的 checkpoint；
2. 对照源码确认它所在的线程、进程和资源边界；
3. 如果没有出现，先补充 trace，再验证假设；
4. 不要只在原有假设上叠加 retry 或 timeout。

这种方法能够区分“事件丢失”“事件乱序”“进程未退出”和“流关闭阻塞”四类问题。

## 6. 通用检查清单

| 场景 | 检查项 |
|---|---|
| 子进程退出 | 是否在销毁父进程前 snapshot 后代进程 |
| pipe 关闭 | 是否先杀进程树，再关闭输入输出流 |
| Reactor sink | 是否只有一个 producer，或是否显式序列化 |
| 流式结束 | `Done` 是否由发送最后一个数据片段的同一线程发出 |
| 诊断信息 | 每个跨线程、跨进程和跨阶段边界是否有 trace checkpoint |
