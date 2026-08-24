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

- `profiles` **已建好**（云端+迁移SQL）；其余 7 张待建
- PR/体量/1RM **不建表**，从 session_sets 聚合查询（YAGNI）

## 3. 当前状态（截至交接时）

### 分支与代码位置

- **工作分支：`feature/fittrack-m0-m1`**（9 个提交，全部已验证）——先 `git checkout feature/fittrack-m0-m1`
- main 上没有 fittrack；工作区 `code/fittrack/` 只有一个被 ignore 的 env 文件
- 代码根：`code/fittrack/`，包名 `com.fittrack.fittrack`

### ✅ 已完成（计划 1/6 的 Task 1-5、7）

| # | 任务 | 提交 | 验证状态 |
|---|---|---|---|
| 1 | Flutter 脚手架（Android-only） | `9f51b70d` | analyze 干净 |
| 2 | Supabase/Riverpod 接入 + 连通页 | `63ab7160` + 修复 `b4bb70b5`/`9bcf0cad` | **里程碑 0 达成**：模拟器截图两绿灯，logcat 无错；测试全过 |
| 3 | profiles 表 + RLS(4策略) + 注册触发器 | `fe76ef0c`（SQL）/云端已执行 | API 验证：表✅ 策略✅ RLS✅ 触发器✅ |
| 4 | AuthRepository 抽象 + Supabase 实现 | `fc8e6288` | TDD，review 通过 |
| 5 | Android deep link + 应用名 | `c5545050` | analyze + apk build 通过（**review 未做**） |
| 7 | RLS 隔离回归脚本 | `65f531cc` | API 分步断言全过（触发器1/1、泄露0） |

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

### 立即（计划 1/6 收尾）

**Task 5 的 review 没做**（用户中断点）——对 `fc8e6288..c5545050` 的 diff 做一次代码审查（manifest intent-filter、strings.xml、label 引用）。diff 文件：`.superpowers/sdd/review-fc8e6288..c5545050.diff`。

**Task 6：登录/注册 UI + 路由守卫 + 首页骨架**（计划文档里有逐行代码，TDD）：
- `lib/providers/auth_providers.dart`：`authRepositoryProvider` + `authStateProvider`(StreamProvider<String?>)
- `lib/app/router.dart`：go_router + `RouterRefreshStream` + redirect 守卫（未登录→/login）
- `lib/features/auth/login_screen.dart` / `signup_screen.dart`：邮箱表单 + Google 按钮（AuthException→中文 SnackBar）
- `lib/features/home/home_screen.dart`：欢迎语 + 登出
- `lib/main.dart`：全局 ProviderContainer + MaterialApp.router
- 手动验收：注册→登录→首页→登出；Dashboard 看 profiles 行自动生成

**计划 1/6 完成定义**：analyze 0 问题、test 全过、模拟器全流程、里程碑 1「新用户可注册并进入空首页」。

### 之后（计划 2-6，按里程碑，spec §6）

| 里程碑 | 内容 | 验收 |
|---|---|---|
| 2 计划 | exercises 表+seed(约50动作) + 自定义动作 + workout_plans/plan_exercises CRUD | 能建/编/删计划 |
| 3 训练执行 ⭐ | workout_sessions/session_exercises/session_sets + 逐组记录 UI + 休息计时器 + **drift 本地缓冲/结束同步** | 完整记一次训练；断网不丢、联网同步 |
| 4 历史统计 | 历史列表/详情 + 动作曲线 + PR + bodyweight_logs + 体重曲线 | 统计与记录一致 |
| 5 打磨 | 空/错/加载态、图标、启动页 | Android 内测无崩溃 |

每份计划在前一份完成后**基于真实工程重写任务分解**，不要照抄旧计划的行号/结构假设。

## 5. 环境与坑（必读）

1. **本机全局代理 127.0.0.1:7890 会劫持 localhost**，导致 `flutter test` 报 `Connection closed before full header was received`。**所有 flutter 命令必须加前缀**：
   ```
   NO_PROXY="127.0.0.1,localhost" no_proxy="127.0.0.1,localhost" flutter test
   ```
2. Flutter 3.47.0 stable（brew cask 安装）；Dart 3.13.0。Android toolchain 缺 cmdline-tools（不阻塞 build/run）。licenses 已接受。
3. 模拟器：`Medium_Phone_API_36.1`（另有 Pixel_9a）。启动慢，首次 `flutter build apk --debug` 约 8 分钟（含 CMake/Gradle 下载），后续增量快。模拟器 adb 偶尔掉线：`adb kill-server && adb start-server`。
4. **supabase_flutter 的 Supabase.initialize 依赖 SharedPreferences**（原生通道），单测必须 `SharedPreferences.setMockInitialValues({})`（test/supabase_config_test.dart 已有示范）。
5. supabase_flutter 2.x 用 **`publishableKey`**（新 key 格式 `sb_publishable_...`），不是旧文档的 `anonKey`。
6. 提交规范：中文，`feat|fix|docs|test|refactor|chore: …`，结尾 `Co-Authored-By: Claude <noreply@anthropic.com>`（Codex 接手后改成自己的标识）。
7. 工作目录 `.superpowers/sdd/progress.md` 是执行 ledger（git-ignored）——每个任务完成后追加一行，防上下文丢失。

## 6. 给 Codex 的操作指引（第一小时）

```
1. git checkout feature/fittrack-m0-m1
2. cat docs/superpowers/specs/2026-08-14-fittrack-commercial-refactor-design.md   # 设计
3. cat docs/superpowers/plans/2026-08-14-fittrack-m0-m1-foundation-auth.md       # 当前计划
4. cd code/fittrack && NO_PROXY=... flutter test && flutter analyze              # 应全绿
5. 补做 Task 5 review → 执行 Task 6（计划里有逐行代码）
6. 完成后：git commit；把里程碑 1 验收截图/结论记入 ledger
```

验证凭据可用（一条命令）：
```bash
curl -s -X POST "https://api.supabase.com/v1/projects/ijvrzxuffzxjtdetulnp/database/query" \
  -H "Authorization: Bearer $(cat .superpowers/supabase_token)" -H "Content-Type: application/json" \
  -d '{"query":"select count(*) from pg_policies where tablename='"'"'profiles'"'"';"}'
# 期望返回 4 条策略
```

---

*交接文档生成于 2026-08-24，基于 feature/fittrack-m0-m1 @ c5545050 的已验证状态。*
