# 开发设置与 Build/Plan 交互实现

版本：1.0.1；日期：2026-09-16。

## Context 与源码依据

实现起点为 `pi-mono-java@1d08f98eb04f8ea6e01bcfbfd4008c1b638391c4`。
已确认设计为 `pi-mono-java-design@3e3d569651d1e5f511810f5e0a2b778b041d9236`：

- [前端主设计](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/README.md)
- [开发设置与热应用](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/runtime-workbench-alignment.md)
- [逐题问答与 Plan](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/plan-interaction.md)

基线 `DebugHeaders.vue`、`useDebugAuth.ts` 仅在组件内保存值，刷新需要重填；
`vite.config.ts`、`useRuntimeApi.ts` 在启动时捕获环境配置；`AppSidebar.vue` 的绝对定位菜单缺删除和外点关闭；
`QuestionCard.vue` 同时展开全部问题，`runtimeEventProjector.ts` 另显示 AskUserQuestion 参数和结果。
本实现沿用内部调试工作台、原始事件与现有控制事务，不新增企业公共 API 或生产身份方案。

## 关键定义

- **已应用配置**：成功保存后供新动作读取的快照，和正在编辑的草稿分开。
- **环境**：实际 API 后端身份；同源模式包含代理目标。Header 再按认证 IP 隔离，Session 索引再按 Agent/caller 隔离。
- **逐题**：客户端只呈现当前题，前后导航不发送请求；末题校验并一次提交同一问题事件的全部答案。
- **接受与完成**：问答/批准的完整回执说明已接受，原任务真实终态说明结束；回答不等于批准计划。

## 架构与数据流

目标结构由设计仓唯一维护，复用[全局组件图](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/target_frontend_components.svg)
与[工作台图](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/target_runtime_workbench.svg)。
[全局组件 PlantUML 图源](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/diagram.puml#L210) ·
[工作台 PlantUML 图源](https://github.com/superheromeZzh/pi-mono-java-design/blob/3e3d569/01-总体架构/04-CampusClaw前端体验/diagram.puml#L395)。
本文件记录落地和验证，避免维护第二套组件类图。

开发者设置统一组织连接默认项、认证、Headers 和 Session 诊断。公开设置及 IP/Header 存在独立浏览器命名空间；
登录账号密码只由本地 Vite 服务保存，读取不回显。每个请求捕获地址、caller、Header、配置版本与目标，
切换环境清理当前视图并从新作用域只读恢复，旧执行与旧环境的待对账标记保留。

侧栏与 Composer 菜单由根浮层定位，脱离滚动容器裁切。时间线与 Composer 占独立布局行；
问题卡在原时间线逐题展示，既有普通消息、Slash 草稿和停止操作继续保留。
JSON 树只是原始参数/文本的安全展示，不修改 Runtime 记录或模型上下文。

## 设计决策

[ADR-0110](../decisions/0110-developer-settings-and-plan-interaction.md)集中记录取舍：

- 产品约束：Build/Plan 命名、短状态文案、一个当前问题、整批提交和独立计划审核。
- 架构变更：浏览器设置与本地服务秘密配置分开、配置热应用与环境代次、统一根浮层。
- 安全加固：代理版本守卫、失败保存不假报成功、迟到 Token 不覆盖新环境、JSON 仅生成文本节点。

## 边界情况与状态

| 情况 | 行为 |
|---|---|
| 普通设置或 Header 编辑 | 显式保存后生效；关闭面板不自动提交 |
| 全部清空 Header | 立即停用当前值并取消旧回填；落盘失败仍保持当前页清空，并明确刷新风险 |
| 获取 Token | 仅使用已应用认证设置，成功后只保存对应 Token 行，不隐式保存其他草稿 |
| 环境或本地配置版本变化 | 旧请求不进入新目标；刷新配置与历史，不重放副作用 |
| 删除非当前会话 | 绑定菜单所在行，先核对状态、显式确认；成功后移除该行，不改变当前会话 |
| 删除运行中/失败/回执不确定 | 要求先停止；失败保留；未知结果只读核对，404 可移除失效索引 |
| 删除当前会话 | 返回新会话入口，不自动创建后台 Session |
| 题间导航 | 保存全部页内草稿与位置，不调用 HTTP；末题整批校验，错误定位对应题 |
| 问答调用及结果 | 与权威问题/回答关联后由题卡吸收；原始事件保留；缺页只读，失败简短可见 |
| JSON 不完整或过大 | 保留原文；数字按词法原貌展示，不执行 HTML、链接，不递归解释 JSON 字符串 |
| Plan 澄清 | 先查可获得事实，只询问影响方案的未知；优先一题，无待决信息不强行提问 |

## 性能与 DFX

结构化 JSON 有深度、节点和输入大小预算；超限回退完整原文，防止展开大对象阻塞页面。
默认只展开浅层，工具完成后收起。UI/JSON 各自统一字体和字号。浮层监听随组件关闭/卸载清理，
不通过增大 z-index 掩盖祖先 overflow 裁切。

开发者设置采用同一界面字体：面板标题 16px/600、分组标题 14px/600、表单和文字按钮 13px/400、标签与提示 12px，统一 1.5 行高。`DeveloperSettings.vue` 和 `DebugHeaders.vue` 明确设置字体基准，避免 Headers 子组件回到浏览器默认字号；图标按钮保留独立图标尺寸。该样式修正基线为 `16d3d8f9f1fcb9b1ca07486ed6ee97c514dcfaba`，实测原 summary 和 Header 输入为 16px、普通连接输入为 13px；字体族相同，问题是字号和字重层级不一致。组件结构与上述架构结论不变。

普通 Vue/CSS 更新使用既有 Vite HMR。面板管理的配置保存后直接应用，不改写 `.env.local`。
手工修改启动配置、端口、依赖或 Node CA 仍遵循开发进程重启边界；不承诺所有未保存草稿跨 HMR 存活。
不新增 npm 或 Maven 依赖；本地配置使用现有 Node/Vite 能力。

## 契约与兼容

内部 Runtime 的 Session、五种 user.* 输入、问题批次及整批事务保持既有契约；
显示 Build/Plan 不改变 `build`/`plan` wire 值。持久 Header 继续只用于既有消息、命令、中断和确认 POST，
不扩散到历史 GET 或 Session 配置。新的配置端点只属于本地 Vite DEV，不进入 Java、build 或 preview。

固定 Plan 提示词仍属于提示词策略，不能据此宣称有强制只读沙箱。企业 Mate 桥接、真实认证中心、
真实模型遵循率及生产部署分别验收。

## 测试与验证

字体修正验证（1.0.1）：独立 Vite dev 真实启动，检查 Chrome 的计算字号、折叠标题和展开表单；1440×900、1024×768、768×900、390×844、768×320 下无面板/Headers 横向溢出，关闭区保持可达。`npm run build`（含 TypeScript）通过，两个开发设置相关测试文件共 9 项通过，`git diff --check` 通过；未新增依赖。本次不验证真实认证登录、浏览器 200% 缩放或移动端软键盘。以下为 1.0.0 功能落地时的验证记录。

- `npm test`：32 个测试文件、242 项通过；`npm run build` 的 TypeScript 检查和生产构建通过。
- `PlanPolicyPromptComposerTest`：5 项通过；同时编译涉及的 Java 测试源码。
- `spotless:apply checkstyle:check` 和独立 `spotless:check`：通过。新增提示词测试断言实际组合资源及策略差异，不 stub 被测对象；修改类的方法/构造器人工核对均在 50 个非空物理行内。
- `npm audit --audit-level=high`：0 vulnerabilities；没有新增依赖。
- 配置代理专项：版本/目标身份不匹配在转发前拒绝，已转发的流保持原目标，内部守卫不透传到后端。
- 浏览器已验证非秘密默认配置、IP、两个模拟 Header 的刷新恢复与默认遮罩，以及清空后刷新保持零行。代理在两个本地 fixture 间热切换后，Session/认证作用域隔离；切回恢复原列表、选择和 IP。
- 浏览器已跑通三题前后导航、整批提交、独立审核计划、批准切换 Build、结构化结果和停止。侧栏外点关闭、非当前会话删除确认及取消通过；真正删除、冲突和未知结果由独立删除测试覆盖。
- 1440、1024、768、390px 视口及 768×320 矮窗口已检查；矮窗口中时间线下边缘与 dock 上边缘均为 183px，dock 下边缘为 320px，无横向溢出。未单独验证浏览器 200% 缩放。
- Vite 的私有目录原路径与编码 `/@fs` 均返回 403，普通静态请求没有泄露测试标记。
- 生产产物不包含开发配置控制器或端点实现；preview 的配置 GET/PUT 与认证 POST 均返回 404。
- `sync-campusclaw.sh --no-verify`：已同步四个提示词/测试镜像文件。完整脚本因本机无法解析 `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` 退出，未完成公司镜像编译。
- 个人环境没有 `java-ut-coverage-loop/scripts/check_test_quality.py`，脚本质量检查未执行，已人工检查修改测试的断言、被测边界和方法长度。

真实认证中心、真实模型行为和 openGauss 集成场景没有在本次本地模拟验收中执行。

## 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0.1 | 2026-09-16 | 统一开发者设置、Headers 的字号、字重和行高，补充真实浏览器布局验证。 |
| 1.0.0 | 2026-09-16 | 落地统一开发设置、环境隔离、侧栏删除、工具结构展示与逐题 Plan 交互。 |
