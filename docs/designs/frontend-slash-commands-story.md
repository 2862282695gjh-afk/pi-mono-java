# Story 实施拆解：Web 前端 Slash Commands

> Story：CampusClaw Web 工作台支持 `/` 斜杠命令（发现、补全、执行）
>
> **本文档定位**：纯实施拆解（工作切分、场景验收、验证方式）。所有 HTTP 契约、错误码、
> `/compact` 状态机与前端交互语义**一律以 [frontend-slash-commands.md](frontend-slash-commands.md)
> （下称"主文档"）为唯一有效依据**，本文按小节引用，不另立第二套契约。
>
> 关联：[frontend-slash-commands-gap-analysis.md](frontend-slash-commands-gap-analysis.md)（代码现状与复审过程）、
> [frontend-slash-command-low-fidelity.html](frontend-slash-command-low-fidelity.html)（低保真交互稿）
> 源码基线：`adee3c8d`（合并冲突标记已清除，全模块 JDK 21 构建通过）
> 状态：Ready for implementation

---

## 0. 开工前置

| # | 前置项 | 核查结论 | 承接 |
|---|---|---|---|
| B1 | 可构建基线 | 已完成：本地 main 曾残留 37 个文件的 Git 冲突标记，已恢复为 upstream 干净版本并提交 `adee3c8d`，构建通过（对应主文档 §7.1 交付 0） | 无需再动 |
| B2 | 认证授权底座 | 当前 runtimeapi 全部端点无鉴权、无用户身份概念。主文档 §2.2 固定 Spring Security OAuth2 Resource Server（JWT issuer/签名/过期/audience 校验 + `RuntimePrincipalResolver` claim 映射），并要求 owner subject + agent scope 双重校验与 USE/DELETE/RUNTIME_OPERATIONS 权限划分 | **必须最先实施**（下文 FS-00）；所有命令端点的授权语义都建立在其上 |

---

## 1. 需求背景和价值

背景与价值论证见主文档 §1 与 gap-analysis 的代码现状部分。要点摘录：

- TUI 移除后 `command/` 包遗留宿主无关命令骨架，从未被任何 Web 宿主接线；
- Web 工作台对 `/` 输入一律作为普通 prompt 发给模型——既产生无效 token 消耗，也让用户的会话操作意图（切模型、压缩历史）落空；
- Skill 的 SKILL.md 流程目前只能靠用户手工转述触发。

本 Story 交付后的直接价值：`/` 输入由服务端确定性路由（已识别命令**绝不**进入模型上下文）、Skill 一句话直达、Extension 获得部署期注册通道、并为多用户化补齐认证授权底座。

## 2. 范围

与主文档 §1"首版范围"一致，不重复抄录。边界提醒（防实现跑偏）：

- Extension 是**部署期** SPI，不是运行期安装/热加载（主文档 §5.2.2）；
- `/compact` 首版忽略 `arguments`（主文档 §5.3.1）、不提供浏览器侧取消端点、禁止态只能运维清除；
- Skill 只有服务端一条执行路径，禁止"先 commands 再 events"的双提交（主文档 §5.2）。

---

## 3. 功能场景（Feature Scenario）

编号沿用本系列评审记录，便于回溯批注对应关系。每条 Scenario 标注其权威条款位置。

### FS-00 认证、授权与数据归属（P0 前置）

**权威条款**：主文档 §2.2（JWT 认证、owner subject + agent scope、USE/DELETE/RUNTIME_OPERATIONS 权限划分）。

```gherkin
Given 已完成的存量数据 owner 回填迁移（owner_subject_id 无 NULL）
When 用户携带有效 JWT 访问任意 session 资源
Then principal 由 sub→subjectId、campusclaw_agents→scope 构建,
    非 owner 访问统一返回 SESSION_NOT_FOUND(404),不区分存在性;
And 创建/查询/事件/命令需要 USE,删除需要 DELETE;
And agentId 只能作筛选条件,不能作为授权凭据;
And 即使是无会话的 GET /commands?scope=static 与 POST /commands/help 也要求已认证调用者。
```

验收：缺失/伪造/类型错误的必需 claim 返回 401；从网关 header、body、query、callerId、Mate 凭据推导身份的路径均不存在（测试断言拒绝行为）。

### FS-01 `/` 触发目录发现（两层作用域）

**权威条款**：主文档 §4.1。

```gherkin
When composer 输入以 "/" 开头且菜单激活条件满足(!hasSeparator)
Then 目录来自两个端点形态之一:
    GET /commands?scope=static     → 仅静态命令(内置 + Extension);
    GET /commands?sessionId={id}   → 静态命令 + 该 agent 可见 Skill descriptor;
And 有会话形态先做会话授权校验再返回;
And 静态目录不含 TUI-only 命令,也不含任何 Skill(Skill 是会话动态解析结果);
A/B agent 的 Skill 集互相隔离。
```

### FS-02 补全与两段式确认

**权威条款**：主文档 §5.1（`ParsedSlashInput.name/arguments/hasSeparator`；菜单仅在 `!hasSeparator` 展示）、§6（前端接线）。

```gherkin
When 菜单激活
Then ArrowDown/Up 移动高亮; Tab 或 Enter 仅补全为 "/name "(带尾空格,菜单收起);
And Cmd/Ctrl+Enter 补全并立即执行; Escape 关闭菜单、输入保留、焦点回 textarea;
And 补全写入尾空格后 hasSeparator=true,菜单正确收起——这是锁定用例,不是实现细节。
```

### FS-03 SERVER 同步命令与 model/thinking 别名

**权威条款**：主文档 §5 命令行为表、§4.3、§6.1 错误信封。

```gherkin
When 提交 "/model"(空参)、"/settings" 等同步 SERVER 命令
Then 走 POST /sessions/{id}/commands/{name} 返回 CommandResultDTO(kind/output/effects),
    output 作为系统消息进时间线(不持久化,不进 Agent 历史);
When 提交 "/model <value>" 写操作
Then 不走命令端点——前端直调既有 PUT /sessions/{id}/model(If-Match),
    成功后刷新模型显示;/thinking 同理。
```

### FS-04 CLIENT_LOCAL 命令（/new、/resume）

**权威条款**：主文档 §5 行为表、§4.2（`matches/hasMore` 语义）、§6 与 §7.4（App.vue 分流顺序）、§7.4 交付说明。

```gherkin
Given 无论是否已有会话
When 提交 "/new"
Then 仅清空当前视图并显示系统反馈,零网络请求,不删服务端 session;
When 提交 "/resume"
Then 无参数列出已授权会话;有 prefix 时 GET /sessions?sessionIdPrefix=&limit=20,
    按 matches/hasMore 决定:仅一条直接恢复,否则展示候选列表;
    恢复成功后重拉 session-aware 命令目录(Skill 随新 agent 变化);
And 分流顺序是:submit() 最前面用原始 draft 过 Slash parser → 命中 CLIENT_LOCAL 直接处理
    → 非命中才检查 hasSession / 调 runtime.sendMessage;
And 判空只用 draft.trim(),trim 后文本不得作为命令参数传递。
```

### FS-05 异步压缩 /compact（P0，最复杂场景）

**权威条款**：主文档 §5.3 全节（HTTP 形态 / 互斥 / 准入原子操作 / 状态机 / 超时边界 / 验收）。此处只列实现检查点：

```gherkin
When 提交 "/compact"(首版忽略 arguments)
Then 准入使用 admitCompaction(sessionId, now):session 行锁同一事务内
    判定 SUSPENDED / IN_PROGRESS / TIMEOUT_OBSERVED / ADMITTED(operationId, expectedLeafId);
After ADMITTED 事务提交后(afterCommit)才登记内存单飞标记并启动 future;
When SessionCompactor 完成
Then 由独立投影器 RuntimeCompactionHistoryProjector 写入持久化状态机
    (STARTED/COMPLETED/FAILED/SUSPENDED/SUSPENDED_CLEARED 五种 entry,
     除 COMPLETED 进 Agent 上下文外其余恢复 Agent 历史时一律 null);
And COMPLETED 终态用 tryAppendTerminalIfOpen 校验 operation open 且 leaf 未变,
    leaf 冲突写 FAILED(CANCELLED_BY_NEW_MESSAGES);
And 超时在同事务写入 FAILED(TIMEOUT_OBSERVED)+SUSPENDED 并拒绝本次请求;
    之后只能单实例可验证终止或运维显式清除,绝不按时间自动放行。
```

**现状差距（实现者必读）**：现有 `RuntimeEventProjector` 只服务于普通 `/events` 请求期且仅持久化 completed 类结果，started/failed 目前仅是 SSE 瞬时事件——所以五种 entry 类型、`RuntimeEntryCodec` 恢复规则、独立投影器和 `CompactionReconcileWorker` 全部是**新增交付物**，不是既有能力的搬运。文件清单见主文档 §7.6。

验收：主文档 §5.3.6 四条全部落地（并发准入单胜 / leaf 冲突 summary 不覆盖新消息 / 超时后旧 future 不产生第二终态 + 重启禁止态保持 / 多实例不自动放行）。

### FS-06 `/skill:<name>`（SSE）

**权威条款**：主文档 §5.2、§5.2.1（RuntimeSkillResolver）。四类稳定错误的 HTTP 映射：NOT_FOUND / NOT_VISIBLE → 404，INVOCATION_DISABLED / PATH_INVALID → 422。

```gherkin
When 会话内提交 "/skill:<name> [args]"
Then 服务端经已授权 session 对应 agent 的 runtime 目录取 SKILL.md
    (server 侧 16 KB 截断并写入注入 message),
    以 Skill 内容+arguments 组合一次 user message 走既有 submit+SSE 链路;
And 单个 POST 连接承载完整流(content-type=text/event-stream),
    流开始前的校验失败返回普通 JSON 错误信封——前端按 content-type 分流;
And 只发起这一次执行,严禁 commands POST 后再 POST /events。
```

**关键细节**：resolver 复用 `RuntimeAgentPromptLoader` 的 real-path/符号链接/扫描深度/大小保护，但**不复用**其"过滤 disabled 后只返回可见列表"的结果——执行路径要能区分四类错误原因返回稳定错误码。

### FS-07 Extension 命令（部署期 SPI）

**权威条款**：主文档 §5.2.2。

```gherkin
Given 部署方以 auto-configuration 或 @Bean 提供 SlashCommandExtension
When 应用启动
Then WebCommandCatalog 收集全部 Extension 命令并校验:
    extensionId/grammar/namespace 所有权/保留字 skill/重名/SERVER-only;
    任一冲突或出现 CLIENT_LOCAL Extension 命令 → 应用启动失败;
When 执行 Extension streaming 命令
Then 与内置命令共用同一授权、8 KB 参数上限、错误信封和 SSE 协议,
    handler 只能从 CommandExecutionContext 获得已授权 session。
```

### FS-08 `/events` Slash 守卫

**权威条款**：主文档 §5.1。

```gherkin
When 直接 POST /events body {"message":"<slash 文本>"}
Then 静态命令名命中 WebCommandCatalog.isStaticRegistered → 400 COMMAND_NOT_ROUTED;
And "skill:" 且名称符合 grammar → 一律路由当前 session 的 SkillResolver,
    未知/不可见/禁用的 Skill 返回稳定错误码,同样绝不退化为普通 prompt;
And 其余 "/xxx"(未注册、grammar 不符)保持普通 prompt 透传。
```

注意最后一条与 FS-06 的差异：未知 **静态名**（如 `/modle`）透传，未知 **skill 名**（`/skill:xyz`）报 `SKILL_NOT_FOUND`——两种处置都出自主文档 §5.1，不许互相"修齐"。

### FS-09 无会话命令入口

**权威条款**：主文档 §4.1、§4.3、§2.2。

```gherkin
When 未建会话调用 /help 或 GET /commands?scope=static
Then 只要 JWT 有效即可(不需要某个 session 的 USE),
    返回静态命令目录 / help 结果;
When CLIENT_LOCAL 的 /new、/resume 在无会话时提交
Then 前端本地处理(App.vue 分流顺序保证),不发 commands 请求。
```

---

## 4. 实施顺序与依赖

```
B1 构建基线(已完成)
 └─ ① 认证授权底座            = 主文档 §7.2 交付 1(P0)
     └─ ② Catalog+parser+HTTP骨架 = §7.3 交付 2(P0)   ←events 守卫与本层共用 parser 用例
         ├─ ③ resume/内置命令/前端分流 = §7.4 交付 3(P0/P1)
         ├─ ④ Skill+Extension   = §7.5 交付 4(P1)        ←依赖②的 catalog 与①的授权
         └─ ⑤ compact+历史投影  = §7.6 交付 5(P0)        ←依赖②的 DTO/信封,可与③④并行
```

类清单、包名约束与职责边界**以主文档 §7 各交付表为准**（如 `WebCommandCatalog`、`RuntimeSkillResolver`、`SlashCommandExtension`、`RuntimeCommandService`、`RuntimeCompactionCommandService`、`RuntimeCompactionHistoryProjector`、`CompactionReconcileWorker` 及 repository 三原子操作），本文不再复制以免漂移。

关键不变量提示（引用条款）：单飞标记只在 afterCommit 登记、CAS 按 operationId 清理（§5.3.4 第 2 条）；COMPLETED 条件追加 / FAILED open 校验与晚到回调单终态（§5.3.4 第 1/3 条）；events 守卫与 commands 路径调用同一个 parser 和同一个 Skill resolver（§5.1、§5.2.1）。

## 5. 前端设计

视觉基线：[frontend-slash-command-low-fidelity.html](frontend-slash-command-low-fidelity.html)。组件与接线职责对照主文档 §6、§7.4：

| 关注点 | 定稿语义 | 出处 |
|---|---|---|
| `useSlashCommands` | 加载目录(scope/sessionId 两形态)、parser、执行分流、effects 与 SSE 处理；App.vue 以 `useSlashCommands.submit(draft)` 为第一道闸 | §6、§7.4 |
| `CommandMenu.vue` | 按 descriptor 的 category/argsHint/streaming/requiresSession 渲染与禁用 | §6 |
| 菜单激活条件 | `!running && parsed!=null && !parsed.hasSeparator`；加载失败静默降级为无菜单 | §5.1 + gap-analysis 决议 |
| 键盘序 | Cmd/Ctrl+Enter(补全即执行)判定必须在普通 Enter(仅补全)之前——颠倒是已踩过的缺陷 | 本系列评审⑥ |
| `/model`、`/thinking` 写 | 直调既有 If-Match 配置 API,不走命令端点;空参查询才走 commands API | §5 行为表 |
| `/resume` | `matches/hasMore`:仅一条直接恢复;恢复后重拉 session-aware 目录 | §4.2、§7.4 |
| 流式消费 | 复用既有 consumeSse;JSON 错误信封按 content-type 分流进统一 friendlyError | §6.1 上文 |
| 时间线投影 | compact 状态按 operationId 关联展示五态,刷新/重连一致;不用文案推断关联 | §5.3.3 下注 |

可达性（aria-expanded/listbox 语义）沿用低保真稿与本系列评审④⑤决议，主文档未收紧处不额外加码。

## 6. 测试映射

各场景的后端/前端用例矩阵以主文档 §8 验收清单为准；补充两条工程性要求：

1. **共享 parser 用例集**：前后端共读同一份 JSON 锁定用例（空白、换行、仅 `/`、`hasSeparator`），CI 双侧同时跑——这是防止"两套 parse 语义"回潮的唯一机械手段（主文档 §8 第 3 条）。
2. **冲突标记回归门**：构建基线曾因合并解析不完整损坏 37 个文件；PR 检查保留启动期 marker 扫描（gap-analysis 横向复查㉛引入的措施），防同类事故复发。

## 7. 验证命令

```bash
# 后端（前提：JAVA_HOME 已指向 JDK 21）
./mvnw -pl :campusclaw-coding-agent -am test
./mvnw spotless:check checkstyle:check
# 前端
cd frontend && npm test && npm run typecheck
```

## 版本历史

| 日期 | 说明 |
|---|---|
| 2026-08-27 | 初版。按 codex 审查意见修正七处与主文档的矛盾：目录发现改回 `scope=static` 两形态并移除 all=true/webCapable；Skill 改为 session 动态解析非静态成员；/compact 明确首版忽略参数；补齐 OAuth2/JWT 认证前提；错误码收敛到主文档 §6.1（替换 SESSION_REQUIRED / ARGUMENTS_TYPE_INVALID / COMMAND_NOT_AVAILABLE_ON_WEB 等）；基线更新至 adee3c8d；修正"started/failed 已持久化"的错误表述（现状仅 completed 持久化，独立投影器为新增交付物）。全文改为逐条引用主文档的实施拆解体例 |
