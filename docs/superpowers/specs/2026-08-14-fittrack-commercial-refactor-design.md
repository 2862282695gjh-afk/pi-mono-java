# FitTrack 商用重构设计（v2，Android 先行）

- **日期**：2026-08-14
- **状态**：已与产品决策者（用户）逐节确认，待终审
- **结论**：以 Flutter 重写 FitTrack 为商用训练记录 app，Supabase 全程云端，Android 先行发布，全功能免费

---

## 1. 背景与现状

仓库中存在两个 FitTrack 实现，工作区文件均已被删除，但完整保存在 git HEAD 中：

| | Android 原型（Kotlin/Compose） | cat-cafe-ts widget（React/TS + Fastify） |
|---|---|---|
| 位置 | `code/claudecode/FitTrack/` | `code/claudecode/cat-cafe-ts/` |
| 定位 | 独立"AI 私教"app 原型 | 赤猫拉面馆项目的内嵌面板 |
| 状态 | 仅 7 个 UI 文件在库，数据层/VM 缺失，无构建配置，**不可编译** | 工程较干净（数据模型+API+UI），但只是插件 |

**评估结论**：Android 原型有产品想法（AI 总结/减载建议）但工程残缺；cat-cafe 那套是挂件。两者都不适合在其基础上改造到商用水平。

**决策**：跨平台（Flutter）重写。旧代码不恢复，仅作产品逻辑参考（需要时 `git show HEAD:<path>` 查看）。新 Flutter 工程置于 `code/fittrack/`（全新目录）。

## 2. 产品定位与核心决策

**定位：训练记录器（对标 Strong / Hevy），核心闭环 = 记录一次训练，并看到自己变强。**

| 决策项 | 结论 |
|---|---|
| 平台 | Flutter 跨平台，**Android 先行发布**；iOS 为后续构建目标，不重写 |
| 后端 | Supabase 托管版（Postgres + Auth + RLS + Realtime + Storage） |
| 数据策略 | 全程云端，**必须登录**（无游客模式） |
| 商业模式 | **全功能免费**，无订阅/无支付/无广告位预留 |
| 目标市场 | 先做出来能跑，上架合规（备案/境内存储）后置到真要国内发布时 |
| 登录方式 | 邮箱 + Google（Apple 登录推迟到 iOS 阶段） |
| 语言 | UI 仅中文 |

## 3. MVP 范围

### 必做（核心闭环）

1. **账号**：注册/登录/登出（邮箱+Google），首次登录建 profile
2. **动作库**：系统预置动作（深蹲/卧推/硬拉等约 50 个）+ 用户自定义动作
3. **训练计划**：创建/编辑/删除计划；计划 = 有序动作列表，每动作设默认组数/次数/重量
4. **训练执行**（核心）：进入训练 → 按动作顺序逐组记录「重量×次数」→ 组间休息计时器 → 完成；训练中可快速加组/调重
5. **历史**：训练列表 + 详情回看
6. **统计**：单动作重量/次数曲线、训练频率、PR（个人记录）、体重曲线
7. **体重记录**：`bodyweight_logs`（相对力量统计依赖）

### 明确不做（v2+）

- AI 功能（生成计划/训练总结/减载建议）→ 未来增值方向
- 饮食/营养记录（cat-cafe 的 `NutritionAdvice` 那套）
- 社交/好友/排行榜、可穿戴对接（Apple Health/Google Fit）、多语言
- `session_sets` 不存 RPE/心率/组间休息时长（计时器仅在 UI 层提示）
- 游客模式、数据导入（从 Strong/Hevy 迁移）

## 4. 系统架构

### 客户端分层（Flutter）

```
UI 层 (screens / widgets)          纯展示 + 交互
状态层 (Riverpod Providers)        业务状态、ViewModel
Repository 层                       数据访问抽象（AuthRepo / WorkoutRepo …）
supabase_flutter SDK + drift 本地库  云端访问 + 训练中本地缓冲
        │ HTTPS
Supabase（Postgres · Auth · RLS · Realtime · Storage）
```

### 技术选型

| 维度 | 选择 |
|---|---|
| 框架 | Flutter（stable） |
| 状态管理 | Riverpod 2.x |
| 路由 | go_router |
| 云端 SDK | supabase_flutter |
| 本地库 | drift（SQLite），仅用于训练进行中的缓冲与缓存 |
| 单位 | 重量存储一律 kg（numeric），lb 仅在 UI 层换算显示 |

### 架构原则

1. **RLS 是命脉**：所有用户数据表启用 Row Level Security，按 `owner_id` 隔离，数据库层强制，不信任客户端。
2. **Repository 抽象**：UI 不直接 import supabase_flutter；换后端与单元测试（mock repo）都不痛。
3. **离线安全**：训练进行中的每组记录先写本地 drift，训练结束整体同步云端；同步成功前本地数据不删除。**任何网络异常不得丢失用户的一次训练记录**。

## 5. 数据模型

### 关系

```
auth.users ─1:1─ profiles ─1:N─┬─ exercises (owner_id=null 系统预置 / 自定义)
                               ├─ workout_plans ─1:N─ plan_exercises ─N:1─ exercises
                               ├─ workout_sessions ─N:1─ workout_plans(可空)
                               │        └─1:N─ session_exercises ─N:1─ exercises
                               │                   └─1:N─ session_sets
                               └─ bodyweight_logs
```

### 表结构（8 张，均含 created_at/updated_at，重量单位 kg）

| 表 | 关键字段 | RLS |
|---|---|---|
| profiles | id(FK auth.users), username, display_name, avatar_url | 仅本人读写 |
| exercises | name, category, muscle_group, is_custom, owner_id | 预置全员可读；自定义仅 owner |
| workout_plans | owner_id, name, description | 仅 owner |
| plan_exercises | plan_id, exercise_id, sort_order, default_sets, default_reps, default_weight | 跟随 plan 联查 |
| workout_sessions | owner_id, plan_id(可空), name, started_at, ended_at, duration_sec, note | 仅 owner |
| session_exercises | session_id, exercise_id, sort_order | 跟随 session 联查 |
| session_sets | session_exercise_id, set_index, weight, reps, completed_at | 跟随 session 联查 |
| bodyweight_logs | owner_id, date, weight | 仅 owner |

### 设计决策

- **PR / 体量 / 渐进超负荷不建表**，从 `session_sets` 聚合查询（`max(weight)`、`Σ(weight×reps)`、估算 1RM 用 Epley 公式）。数据量大了再考虑缓存，YAGNI。
- 动作库双轨：系统预置（owner_id=null，seed 脚本写入）+ 用户自定义（is_custom=true）。

## 6. 发布路径（里程碑，每个有验收标准）

| 阶段 | 产出 | 验收 |
|---|---|---|
| 0 地基 | Flutter 工程 + Supabase 项目 + 主题/基础组件 | demo 页连通 Supabase |
| 1 账号 | 注册/登录/登出 + profiles + 全表 RLS | 新用户可注册并进入空首页 |
| 2 计划 | 动作库（seed）+ 自定义动作 + 计划 CRUD | 可创建/编辑/删除计划 |
| 3 训练执行 ⭐ | 逐组记录 + 休息计时器 + 本地缓冲同步 | 完整记完一次训练；中途断网数据不丢，联网后同步成功 |
| 4 历史统计 | 历史/详情 + 曲线 + PR + 体重 | 统计页数据与记录一致 |
| 5 打磨 | 空/错/加载态、图标、内测包 | Android 真机内测（APK 直装或 Google Play 封闭测试）无崩溃 |

## 7. 测试策略

- **单元**：统计算法（PR/体量/1RM）、kg↔lb 换算、Repository（mock Supabase）
- **集成**：训练执行端到端（建计划 → 训练 → 历史可见）必须覆盖
- **RLS 隔离测试**：两个测试账号互验读不到对方数据，上线前必跑

## 8. 风险与应对

| 风险 | 应对 |
|---|---|
| RLS 配错导致数据泄露 | 两账号隔离测试 + 上线前 SQL 自检脚本 |
| 训练中断网/进程被杀 | drift 本地缓冲先行；同步成功前不清理本地 |
| 多设备同步冲突 | MVP 用 updated_at 后写覆盖；成熟方案（CRDT/操作日志）v2 评估 |
| 国内访问 Supabase 不稳 | 接受；真要国内上架时自托管或迁境内 |
| Supabase 免费额度（500MB/50k MAU） | MVP 阶段足够；超限再付费 |
| Google Play 上架 $25 一次性费用 | 接受；内测阶段可先走 APK 直装 |

## 9. 明确推迟的决策（非未决）

- 正式产品名与商标（FitTrack 为工作名，上架前定）
- iOS 构建与 Apple 开发者账号（$99/年，Android 验证后）
- 国内合规（备案、境内存储、微信登录）
- 变现方式（免费积累用户，未来评估）
