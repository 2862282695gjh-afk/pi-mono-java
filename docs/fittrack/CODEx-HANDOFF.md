# FitTrack 商用重构 · Codex 交接文档

> **文档目的**：你（Codex）将接手 FitTrack 的重构。本文档包含完整的设计决策、已完成的实现、待完成的工作，以及所有环境/工具链的坑。读完即可零上下文开工。
>
> **必读文件**（本文档的详细版）：
> - 设计 spec：`docs/superpowers/specs/2026-08-14-fittrack-commercial-refactor-design.md`
> - 当前执行计划（计划 1/6）：`docs/superpowers/plans/2026-08-14-fittrack-m0-m1-foundation-auth.md`

---

## 1. 项目是什么

**FitTrack**：商用训练记录 app（对标 Strong / Hevy），核心闭环 = **记录一次训练，并看到自己变强**。

| 决策项 | 结论 |
|---|---|
| 平台 | Flutter 跨平台，**Android 先行发布**（iOS 后续加构建目标） |
| 后端 | Supabase 托管版（Postgres + Auth + RLS + Realtime + Storage） |
| 数据策略 | 全程云端，**必须登录**（无游客模式） |
| 商业模式 | **全功能免费**，无订阅/无支付/无广告 |
| 登录 | 邮箱 + Google（Apple 登录推迟到 iOS 阶段） |
| 目标市场 | 先做出来能跑；国内备案/合规推迟到真要发布时 |
| 语言 | UI 仅中文 |

**历史包袱（不要碰）**：仓库 git HEAD 里有旧的 `code/claudecode/FitTrack/`（Kotlin 原型）和 `code/claudecode/cat-cafe-ts/`（React widget）——均已废弃，仅作产品参考，不要恢复、不要修改。

## 2. 技术栈与架构

```
UI 层 (screens / widgets)          纯展示 + 交互
状态层 (Riverpod 2.x Providers)    业务状态、ViewModel
Repository 层                       数据访问抽象（AuthRepo / WorkoutRepo …）
supabase_flutter + drift 本地库     云端访问 + 训练中本地缓冲
        │ HTTPS
Supabase（Postgres · Auth · RLS · Realtime · Storage）
```

- **状态管理**：Riverpod 2.x（无 generator，纯手写 Provider）
- **路由**：go_router 14.x
- **本地库**：drift（SQLite），训练进行中的数据先落本地、结束同步（**任何网络异常不得丢失用户的一次训练记录**——底线）
- **单位**：重量一律 kg 存储（numeric），lb 仅 UI 层换算
- **架构铁律**：
  1. **RLS 是命脉**：所有用户表启用 Row Level Security，按 owner_id 隔离，数据库层强制
  2. **UI 不得直接 import `package:supabase_flutter`**，必须经 Repository（`auth_repository.dart` 里有 `export ... show AuthException` 这条通道）
  3. **Repository 抽象**：换后端与单测（mock repo）都不痛

### 数据模型（8 张表，spec §5 有完整定义）

```
auth.users ─1:1─ profiles ─1:N─┬─ exercises (owner_id=null 系统预置 / 自定义)
                               ├─ workout_plans ─1:N─ plan_exercises ─N:1─ exercises
                               ├─ workout_sessions ─N:1─ workout_plans(可空)
                               │        └─1:N─ session_exercises ─N:1─ exercises
                               │                   └─1:N─ session_sets ⭐每组记录
                               └─ bodyweight_logs
```

- 迁移 `0001`–`0005` 已覆盖 profiles、动作/计划、训练会话、体重记录和多日计划的原子保存；均已部署到 Supabase 托管项目。云端已核验 8 张业务表与 26 条 RLS 策略。
- PR/体量/1RM **不建表**，从 session_sets 聚合查询（YAGNI）

## 3. 当前状态（2026-08-24 更新）

### 分支与代码位置

- **工作分支：`codex/fittrack-m1`**，基于最新 `origin/main`；已推送并有 Draft PR：<https://github.com/2862282695gjh-afk/pi-mono-java/pull/4>
- 继续工作前先从最新 `origin/main` 新建 `codex/<topic>` 分支；不要直接向 main 提交。
- 代码根：`code/fittrack/`，包名 `com.fittrack.fittrack`

### ✅ 已完成

| # | 任务 | 提交 | 验证状态 |
|---|---|---|---|
| 1 | Flutter 脚手架（Android-only） | `9f51b70d` | analyze 干净 |
| 2 | Supabase/Riverpod 接入 + 连通页 | `63ab7160` + 修复 `b4bb70b5`/`9bcf0cad` | **里程碑 0 达成**：模拟器截图两绿灯，logcat 无错；测试全过 |
| 3 | profiles 表 + RLS(4策略) + 注册触发器 | `fe76ef0c`（SQL）/云端已执行 | API 验证：表✅ 策略✅ RLS✅ 触发器✅ |
| 4 | AuthRepository 抽象 + Supabase 实现 | `fc8e6288` | TDD，review 通过 |
| 5 | Android deep link + 应用名 | `c5545050` | analyze + apk build 通过 |
| 7 | RLS 隔离回归脚本 | `65f531cc` | API 分步断言全过（触发器1/1、泄露0） |
| 6 | 登录/注册 UI、路由守卫、首页 | `1474acde` | 单测、analyze、模拟器启动通过；真实注册受 DNS 阻塞 |
| M2 | 动作库、计划 CRUD、迁移 `0002` | `36629b9b` | 云端真实账号完成动作读取与计划保存 |
| M3 基础 | Drift 草稿、迁移 `0003`、同步仓储 | `bc447e50`、`14b25806` | SQL 语法与草稿持久化单测通过 |
| M3 UI | 训练执行页、逐组本地落盘、休息计时器、离线记录重试同步 | `63497543`、`df7ebac5` | `flutter test` 9/9、analyze 0、debug APK 通过 |
| 视觉系统 | 统一主题、首页与计划编辑重设计 | `42dd34ea` | 模拟器登录页实机截图通过 |
| M4 | 历史/详情、体量与估算 1RM、体重趋势、迁移 `0004` | `986a7d2a` | `flutter test` 11/11、analyze 0、全迁移与 debug APK 通过 |
| M5（部分） | Android 品牌图标、启动页和窗口底色 | `877d2444` | APK 构建、安装、模拟器启动通过 |
| M5 发布验收 | release APK 构建与模拟器启动 | 工作区验证 | `app-release.apk`（61.8 MB）构建、安装、启动通过 |
| 计划生成 | 本地规则生成器、目标/经验/频率选择、多日预览 | `d226ee71`、`106b19ae` | 基于真实动作库生成 2–4 份计划 |
| 多日计划保存 | 通过单个 RPC 原子保存整组计划，避免半保存 | `dfa9c069`、迁移 `0005` | 云端正向写入返回 2 个 ID；故障批次返回 400 且保留 0 份计划 |

### Supabase 云端现状（已配置好，别重复配）

- 项目：`https://ijvrzxuffzxjtdetulnp.supabase.co`
- `mailer_autoconfirm=true`（内测免邮件验证，**正式上架前要改回 false 并配 SMTP**）
- `uri_allow_list` 含 `io.supabase.fittrack://login-callback`
- **Google OAuth 未配置**（需要用户 GCP 账号，被搁置）——不阻塞邮箱登录

### 🔑 凭据（不入库）

- `code/fittrack/lib/config/supabase_env.dart`（git-ignored）：本地 env，含 URL + publishable key。**工作区现在只有这个文件**——切换分支后此文件在，其余代码要从分支恢复
- `.superpowers/supabase_token`（git-ignored）：Management API access token（`sbp_70b3...`），可执行 SQL / 改配置。用法：`curl -H "Authorization: Bearer $(cat .superpowers/supabase_token)" https://api.supabase.com/v1/projects/ijvrzxuffzxjtdetulnp/database/query -d '{"query":"..."}'`
  - 注意 Management API 执行多语句 SQL 返回 `[]`（HTTP 201），NOTICE 输出不返回——分步断言式验证（见 Task 7 做法）

## 4. 待完成工作（按优先级）

### 立即

1. **训练执行验收**：首页已展示并支持重试未同步的已结束草稿；真机完成一次含多动作训练，断网后退出重进确认 Drift 草稿存在，恢复网络后确认云端 session、exercise、set 三层数据一致。
2. **M4 真机验收**：确认历史、详情、体重写入与计算结果同云端事实记录一致。
3. **发布前人工验收**：release APK 已能安装启动；在真实 Android 设备手动验证邮箱登录、生成计划、离线训练、恢复网络同步与历史回看后，可进入内测分发。
4. **正式环境切换**：将 `mailer_autoconfirm` 改回 false 并配置 SMTP，再开放真实用户注册。
5. **可选 AI 增强**：当前生成器是确定性本地规则（无需 key）；如接 Qwen/其他模型，须新建服务端代理，绝不能把模型密钥放进客户端。

### 之后（计划 2-6，按里程碑，spec §6）

| 里程碑 | 内容 | 验收 |
|---|---|---|
| 2 计划 | exercises 表+seed(约50动作) + 自定义动作 + workout_plans/plan_exercises CRUD | 代码、云端迁移与真实 API 验收完成 |
| 3 训练执行 ⭐ | workout_sessions/session_exercises/session_sets + 逐组记录 UI + 休息计时器 + **drift 本地缓冲/结束同步** | 主流程代码完成；待断网/联网真机验收与草稿重试入口 |
| 4 历史统计 | 历史列表/详情 + 体量/估算 1RM + bodyweight_logs + 体重趋势 | 代码与云端迁移完成；待真实记录验收 |
| 5 打磨 | 空/错/加载态、图标、启动页 | 图标/启动页代码完成；待真实离线验收和 Android 内测无崩溃 |

每份计划在前一份完成后**基于真实工程重写任务分解**，不要照抄旧计划的行号/结构假设。

## 5. 环境与坑（必读）

1. **本机全局代理 127.0.0.1:7890 会劫持 localhost**，导致 `flutter test` 报 `Connection closed before full header was received`。Flutter 命令使用：
   ```
   NO_PROXY="127.0.0.1,localhost" no_proxy="127.0.0.1,localhost" flutter test
   ```
2. Flutter 3.47.0 stable（brew cask 安装）；Dart 3.13.0。Android toolchain 缺 cmdline-tools（不阻塞 build/run）。licenses 已接受。
3. 模拟器：`Medium_Phone_API_36.1`（另有 Pixel_9a）。启动慢，首次 `flutter build apk --debug` 约 8 分钟（含 CMake/Gradle 下载），后续增量快。模拟器 adb 偶尔掉线：`adb kill-server && adb start-server`。
4. **supabase_flutter 的 Supabase.initialize 依赖 SharedPreferences**（原生通道），单测必须 `SharedPreferences.setMockInitialValues({})`（test/supabase_config_test.dart 已有示范）。
5. supabase_flutter 2.x 用 **`publishableKey`**（新 key 格式 `sb_publishable_...`），不是旧文档的 `anonKey`。
6. 推送代理：`git -c http.proxy=http://127.0.0.1:7890 push origin <branch>`。提交遵循 `feat|fix|docs|test|refactor|chore: …`；仅暂存任务文件。
7. 工作目录 `.superpowers/sdd/progress.md` 是执行 ledger（git-ignored）——每个任务完成后追加一行，防上下文丢失。

## 6. 给 Codex 的操作指引（第一小时）

```
1. git switch codex/fittrack-m1
2. cat docs/superpowers/specs/2026-08-14-fittrack-commercial-refactor-design.md
3. cat docs/superpowers/plans/2026-08-24-fittrack-m2-training-plans.md
4. cd code/fittrack && NO_PROXY=... flutter test && flutter analyze
5. 从 M5 启动页、图标、无网验收和 Android 内测包开始
6. 完成后：commit、push topic branch，并更新 Draft PR 与 ledger
```

验证凭据可用（一条命令）：
```bash
curl -s -X POST "https://api.supabase.com/v1/projects/ijvrzxuffzxjtdetulnp/database/query" \
  -H "Authorization: Bearer $(cat .superpowers/supabase_token)" -H "Content-Type: application/json" \
  -d '{"query":"select count(*) from pg_policies where tablename='"'"'profiles'"'"';"}'
# 期望返回 4 条策略
```

---

*本交接文档更新于 2026-08-24，基于 `codex/fittrack-m1` @ `dfa9c069` 的状态。*
