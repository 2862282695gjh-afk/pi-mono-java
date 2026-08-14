# FitTrack 计划 1/6：工程地基 + 账号体系（里程碑 0+1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭起 Flutter 工程与 Supabase 项目，交付可注册/登录/登出（邮箱+Google）、受 RLS 保护的账号体系，为后续训练功能打地基。

**Architecture:** 单 Flutter 工程（`code/fittrack/`），分层 UI → Riverpod Provider → Repository → supabase_flutter。Supabase 托管版承载 Auth 与 Postgres（含 RLS）。本计划只建 `profiles` 表 + 认证流；训练相关表在计划 3+ 中创建。

**Tech Stack:** Flutter stable / Dart ^3.5 · riverpod 2.x + hooks_riverpod · go_router 14.x · supabase_flutter 2.x

## Global Constraints（来自 spec，所有任务隐含遵守）

- 代码目录：`code/fittrack/`（仓库相对路径；git 命令均在仓库根 `/Users/plankton` 执行）
- 重量单位一律 kg，numeric 存储（本计划不涉及，后续计划遵守）
- UI 仅中文文案
- 全功能免费：不得引入支付/订阅代码
- Android 先行；不得引入 Apple Sign In
- 所有用户数据表必须启用 RLS；UI 层不得直接 import `package:supabase_flutter`（必须经 Repository）
- 提交信息用中文，格式 `feat|fix|docs|refactor|test: …`，结尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`

---

### Task 1: Flutter 工程脚手架

**Files:**
- Create: `code/fittrack/`（flutter create 产物）
- Create: `code/fittrack/README.md`

**Interfaces:**
- Consumes: 无
- Produces: 可编译运行的 Flutter 工程；包名 `com.fittrack.app`；后续所有任务都基于此工程

- [ ] **Step 1: 检查 Flutter 环境**

```bash
flutter --version && flutter doctor
```
Expected: Flutter stable ≥3.24；`flutter doctor` 无 blocking error（Android toolchain 就绪；若 Chrome/VS Code 缺失可忽略）。若未安装 Flutter：`brew install --cask flutter` 后重试。

- [ ] **Step 2: 创建工程**

```bash
cd /Users/plankton/code && flutter create --org com.fittrack --project-name fittrack --platforms android fittrack
```
Expected: 末尾输出 `All done!`。只建 android 平台（iOS 等后续需要时再 `flutter create --platforms ios .` 补）。

- [ ] **Step 3: 验证可编译**

```bash
cd /Users/plankton/code/fittrack && flutter pub get && flutter analyze
```
Expected: `flutter analyze` 输出 `No issues found!`。

- [ ] **Step 4: 写 README 说明工程定位**

`code/fittrack/README.md` 内容：

```markdown
# FitTrack

商用训练记录 app（对标 Strong/Hevy）。Flutter + Supabase，Android 先行，全功能免费。

- 设计 spec：`docs/superpowers/specs/2026-08-14-fittrack-commercial-refactor-design.md`
- 架构分层：UI → Riverpod → Repository → supabase_flutter（UI 不得直接 import supabase_flutter）
- 常用命令：`flutter pub get` / `flutter analyze` / `flutter test`
```

- [ ] **Step 5: 配置 .gitignore（忽略含密钥的本地配置）**

在 `code/fittrack/.gitignore` 追加：

```
# 本地密钥配置（不入库）
lib/config/supabase_env.dart
```

- [ ] **Step 6: Commit**

```bash
cd /Users/plankton && git add code/fittrack
git commit -m "feat: 创建 FitTrack Flutter 工程脚手架

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 依赖接入 + Supabase 项目创建 + 连通性验证

**Files:**
- Modify: `code/fittrack/pubspec.yaml`
- Create: `code/fittrack/lib/config/supabase_env.dart`（本地，不入库）
- Create: `code/fittrack/lib/config/supabase_config.dart`（读 env，入库）
- Create: `code/fittrack/lib/main.dart`（重写）
- Test: `code/fittrack/test/supabase_config_test.dart`

**Interfaces:**
- Consumes: Task 1 的工程
- Produces:
  - `SupabaseConfig.init()`：`Future<void> Function()`，main 里 await，初始化 supabase_flutter
  - `SupabaseConfig.hasEnv`：`bool`，env 文件是否配置（用于诊断页）
  - 依赖：`supabase_flutter ^2.5.0`、`flutter_riverpod ^2.5.1`、`riverpod_annotation`（暂不引入 generator，YAGNI）

- [ ] **Step 1: 加依赖**

`pubspec.yaml` 的 dependencies 下加入（保持字母序）：

```yaml
dependencies:
  flutter:
    sdk: flutter
  flutter_riverpod: ^2.5.1
  go_router: ^14.2.0
  supabase_flutter: ^2.5.0
```

```bash
cd /Users/plankton/code/fittrack && flutter pub get
```
Expected: `Got dependencies!`

- [ ] **Step 2: 创建 Supabase 项目（人工步骤，按提示操作）**

在 https://supabase.com 用 GitHub 账号登录 → New project → 名称 `fittrack`，数据库密码用强密码并存入密码管理器，区域选 `Northeast Asia (Tokyo)`（离中国最近的可用区）→ 创建后等待 ~2 分钟初始化完成。

进入 Project Settings → API，记下：
- `SUPABASE_URL`（形如 `https://xxxx.supabase.co`）
- `SUPABASE_ANON_KEY`（`anon public` 那把）

anon key 是可公开的前端密钥（安全由 RLS 保证），可以进客户端代码。

- [ ] **Step 3: 写本地 env 文件**

创建 `code/fittrack/lib/config/supabase_env.dart`（已在 .gitignore，不会提交）：

```dart
// 本地配置文件，不入库。字段由 supabase_config.dart 读取。
const String kSupabaseUrl = 'https://你的项目ID.supabase.co';
const String kSupabaseAnonKey = '你的anon key';
```

同时创建入库的示例文件 `code/fittrack/lib/config/supabase_env_example.dart`：

```dart
// 复制本文件为 supabase_env.dart 并填入真实值
const String kSupabaseUrl = 'https://<project-id>.supabase.co';
const String kSupabaseAnonKey = '<anon-public-key>';
```

- [ ] **Step 4: 写 SupabaseConfig（含失败测试）**

先写测试 `code/fittrack/test/supabase_config_test.dart`：

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/config/supabase_config.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized(); // Supabase.initialize 需要平台通道

  test('init 后 hasEnv 为 true', () async {
    await SupabaseConfig.init();
    expect(SupabaseConfig.hasEnv, isTrue);
  });
}
```

- [ ] **Step 5: 跑测试验证失败**

```bash
cd /Users/plankton/code/fittrack && flutter test test/supabase_config_test.dart
```
Expected: FAIL，报 `Error: Couldn't resolve the package 'fittrack'` 或 `SupabaseConfig` 未定义。

- [ ] **Step 6: 实现 supabase_config.dart 与 main.dart**

`code/fittrack/lib/config/supabase_config.dart`：

```dart
import 'package:supabase_flutter/supabase_flutter.dart';

import 'supabase_env.dart';

/// Supabase 初始化入口。main() 中 await 调用。
class SupabaseConfig {
  static bool _initialized = false;

  /// env 是否已加载（诊断用）
  static bool get hasEnv =>
      kSupabaseUrl.startsWith('https://') && kSupabaseAnonKey.length > 20;

  static Future<void> init() async {
    await Supabase.initialize(
      url: kSupabaseUrl,
      anonKey: kSupabaseAnonKey,
    );
    _initialized = true;
  }

  static bool get isInitialized => _initialized;
}
```

`code/fittrack/lib/main.dart` 整体替换为：

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'config/supabase_config.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SupabaseConfig.init();
  runApp(const ProviderScope(child: FitTrackApp()));
}

class FitTrackApp extends StatelessWidget {
  const FitTrackApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FitTrack',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF58CC02), useMaterial3: true),
      home: const _ConnectivityPage(),
    );
  }
}

/// 里程碑 0 验收页：显示 Supabase 连通状态
class _ConnectivityPage extends StatelessWidget {
  const _ConnectivityPage();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('FitTrack')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('Supabase 连通性检查', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 8),
            Text(SupabaseConfig.hasEnv ? '✅ 已配置 URL/Key' : '❌ 未配置 supabase_env.dart'),
            Text(SupabaseConfig.isInitialized ? '✅ SDK 初始化成功' : '❌ SDK 未初始化'),
          ],
        ),
      ),
    );
  }
}
```

- [ ] **Step 7: 跑测试 + analyze**

```bash
cd /Users/plankton/code/fittrack && flutter test && flutter analyze
```
Expected: 测试 PASS（`All tests passed!`）；analyze `No issues found!`。
注意：若测试报网络/URL 错误，检查 `supabase_env.dart` 的 URL/Key 是否填对。

- [ ] **Step 8: 真机/模拟器跑通（里程碑 0 验收）**

```bash
cd /Users/plankton/code/fittrack && flutter run
```
Expected: 模拟器上显示两个 ✅（配置 + 初始化）。**里程碑 0「demo 页连通 Supabase」就此达成。**

- [ ] **Step 9: Commit**

```bash
cd /Users/plankton && git add code/fittrack
git commit -m "feat: 接入 Supabase/Riverpod，连通性验收页通过

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: profiles 表 + RLS + 触发器（数据库层）

**Files:**
- Create: `code/fittrack/supabase/migrations/0001_profiles.sql`（版本管理用；实际执行在 Supabase SQL Editor）

**Interfaces:**
- Consumes: Task 2 的 Supabase 项目
- Produces: `profiles` 表（id uuid PK = auth.users.id, username text unique, display_name text, avatar_url text, created_at/updated_at timestamptz）；RLS 启用 + owner-only 策略；新用户注册自动建 profile 的触发器。后续计划的任务依赖此表存在。

- [ ] **Step 1: 写迁移 SQL**

创建 `code/fittrack/supabase/migrations/0001_profiles.sql`：

```sql
-- profiles：用户档案，与 auth.users 一一对应
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique,
  display_name text,
  avatar_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

-- 仅本人可读写
create policy "profiles_select_own" on public.profiles
  for select using (auth.uid() = id);
create policy "profiles_insert_own" on public.profiles
  for insert with check (auth.uid() = id);
create policy "profiles_update_own" on public.profiles
  for update using (auth.uid() = id);
create policy "profiles_delete_own" on public.profiles
  for delete using (auth.uid() = id);

-- 注册即自动建 profile（用户名取邮箱前缀）
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, username, display_name)
  values (
    new.id,
    split_part(coalesce(new.email, 'user' || new.id::text), '@', 1),
    null
  );
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();
```

- [ ] **Step 2: 在 Supabase SQL Editor 执行**

打开 Supabase Dashboard → SQL Editor → New query → 粘贴整个文件内容 → Run。
Expected: `Success. No rows returned`。

- [ ] **Step 3: 验证表与策略**

Dashboard → Table Editor 应能看到 `profiles` 表；Authentication → Policies 里 profiles 有 4 条 owner-only 策略且 RLS 已启用。

- [ ] **Step 4: Commit（迁移文件入库，便于将来自托管迁移）**

```bash
cd /Users/plankton && git add code/fittrack/supabase
git commit -m "feat: profiles 表迁移 SQL（RLS + 注册触发器）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: AuthRepository（认证数据访问层，TDD）

**Files:**
- Create: `code/fittrack/lib/data/auth_repository.dart`
- Test: `code/fittrack/test/auth_repository_test.dart`

**Interfaces:**
- Consumes: supabase_flutter（经 `Supabase.instance.client`）
- Produces:
  ```dart
  abstract class AuthRepository {
    Future<void> signUpWithEmail(String email, String password); // 注册；触发器自动建 profile
    Future<void> signInWithEmail(String email, String password);
    Future<void> signInWithGoogle();   // Google OAuth（Task 5 配置后才可用）
    Future<void> signOut();
    Stream<String?> authStateChanges(); // userId 流，null=未登录
    String? currentUserId();            // 同步取当前 uid，未登录为 null
  }
  ```
  所有方法失败抛 `AuthException`（supabase 自带类型，UI 层捕获转中文文案）。

- [ ] **Step 1: 写失败测试**

`code/fittrack/test/auth_repository_test.dart`：

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/data/auth_repository.dart';

void main() {
  test('SupabaseAuthRepository 实现了 AuthRepository 接口', () {
    // 接口契约测试：确保实现类存在且方法签名正确
    final AuthRepository repo = SupabaseAuthRepository();
    expect(repo, isNotNull);
    expect(repo.currentUserId(), isNull); // 未初始化 Supabase 时安全返回 null
  });
}
```

- [ ] **Step 2: 跑测试验证失败**

```bash
cd /Users/plankton/code/fittrack && flutter test test/auth_repository_test.dart
```
Expected: FAIL，`AuthRepository` / `SupabaseAuthRepository` 未定义。

- [ ] **Step 3: 实现**

`code/fittrack/lib/data/auth_repository.dart`：

```dart
import 'package:supabase_flutter/supabase_flutter.dart';

// UI 层捕获认证错误用这个类型（经本文件 re-export，UI 不必 import supabase_flutter）
export 'package:supabase_flutter/supabase_flutter.dart' show AuthException;

/// 认证数据访问。UI 层只依赖此抽象，不直接 import supabase_flutter。
abstract class AuthRepository {
  Future<void> signUpWithEmail(String email, String password);
  Future<void> signInWithEmail(String email, String password);
  Future<void> signInWithGoogle();
  Future<void> signOut();
  Stream<String?> authStateChanges();
  String? currentUserId();
}

class SupabaseAuthRepository implements AuthRepository {
  SupabaseAuthRepository();

  GoTrueClient get _auth {
    try {
      return Supabase.instance.client.auth;
    } catch (_) {
      // 测试环境未初始化 Supabase 时，走空实现分支
      throw StateError('Supabase 未初始化');
    }
  }

  @override
  Future<void> signUpWithEmail(String email, String password) =>
      _auth.signUp(email: email, password: password);

  @override
  Future<void> signInWithEmail(String email, String password) =>
      _auth.signInWithPassword(email: email, password: password);

  @override
  Future<void> signInWithGoogle() async {
    await _auth.signInWithOAuth(
      OAuthProvider.google,
      redirectTo: 'io.supabase.fittrack://login-callback',
    );
  }

  @override
  Future<void> signOut() => _auth.signOut();

  @override
  Stream<String?> authStateChanges() {
    try {
      return Supabase.instance.client.auth.onAuthStateChange
          .map((event) => event.session?.user.id);
    } catch (_) {
      return const Stream.empty();
    }
  }

  @override
  String? currentUserId() {
    try {
      return Supabase.instance.client.auth.currentUser?.id;
    } catch (_) {
      return null;
    }
  }
}
```

说明：`redirectTo` 的 scheme 在 Task 5 里于 Android manifest 配置；`_auth` getter 的 try/catch 让单元测试在无 Supabase 环境下也能构造对象（本计划内仅做契约级单测，真实登录在 Task 6 手动验收）。

- [ ] **Step 4: 跑测试验证通过**

```bash
cd /Users/plankton/code/fittrack && flutter test test/auth_repository_test.dart && flutter analyze
```
Expected: PASS + `No issues found!`

- [ ] **Step 5: Commit**

```bash
cd /Users/plankton && git add code/fittrack/lib/data code/fittrack/test
git commit -m "feat: AuthRepository 抽象与 Supabase 实现

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: Supabase 开启邮箱+Google 认证 + Android deep link 配置

**Files:**
- Create: `code/fittrack/android/app/src/main/res/values/strings.xml`
- Modify: `code/fittrack/android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 2 的 Supabase 项目、Task 4 的 `redirectTo`
- Produces: 可用的邮箱注册/登录 + Google OAuth；Android 端 `io.supabase.fittrack://login-callback` deep link 生效

- [ ] **Step 1: 开启邮箱认证（Dashboard 操作）**

Supabase Dashboard → Authentication → Providers → Email：确认已 Enable（默认开）。
→ Authentication → URL Configuration → Redirect URLs：添加 `io.supabase.fittrack://login-callback`。
为方便内测：Authentication → Sign In / Up → Email → 关闭 `Confirm email`（内测期免邮件验证；正式上架前再打开并配 SMTP）。

- [ ] **Step 2: 开启 Google 认证（Dashboard 操作）**

按官方流程：Authentication → Providers → Google → 记下界面给的 `Callback URL (for OAuth)`。
到 https://console.cloud.google.com 新建（或复用）GCP 项目 → 「APIs & Services → Credentials → Create Credentials → OAuth client ID (Web application)」→ Authorized redirect URI 填上面那个 Callback URL → 拿到 **Client ID** 和 **Client Secret**。
Supabase Google Provider 页面填入这两个值 → Save。
（iOS/Android 原生 Client ID 留空——MVP 用 Web OAuth 流程即可）

- [ ] **Step 3: Android manifest 加 deep link**

`code/fittrack/android/app/src/main/AndroidManifest.xml` 的 `<activity android:name=".MainActivity">` 内追加：

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="io.supabase.fittrack" android:host="login-callback" />
</intent-filter>
```

- [ ] **Step 4: 加 app 名称中文字符串**

创建 `code/fittrack/android/app/src/main/res/values/strings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">FitTrack</string>
</resources>
```

并在 AndroidManifest.xml 的 `<application>` 上确认 `android:label="@string/app_name"`。

- [ ] **Step 5: 验证编译 + 手动冒烟（模拟器）**

```bash
cd /Users/plankton/code/fittrack && flutter analyze && flutter run
```
Expected: analyze 无问题；app 可启动（此时首页还是连通性页，登录 UI 在 Task 6）。

- [ ] **Step 6: Commit**

```bash
cd /Users/plankton && git add code/fittrack/android
git commit -m "feat: Android deep link 与应用名配置

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 登录/注册 UI + 路由守卫 + 首页骨架（TDD）

**Files:**
- Create: `code/fittrack/lib/app/router.dart`
- Create: `code/fittrack/lib/providers/auth_providers.dart`
- Create: `code/fittrack/lib/features/auth/login_screen.dart`
- Create: `code/fittrack/lib/features/auth/signup_screen.dart`
- Create: `code/fittrack/lib/features/home/home_screen.dart`
- Modify: `code/fittrack/lib/main.dart`
- Test: `code/fittrack/test/auth_guard_test.dart`

**Interfaces:**
- Consumes: Task 4 的 `AuthRepository`（全部方法）、go_router、flutter_riverpod
- Produces:
  - `authRepositoryProvider: Provider<AuthRepository>`（auth_providers.dart，全局唯一 Provider）
  - `authStateProvider: StreamProvider<String?>`（监听登录态，router 守卫与 UI 共用）
  - `FitTrackRouter.config({required RouterRefreshStream refreshStream})` → go_router 配置；路由：`/login`、`/signup`、`/`（首页）
  - `LoginScreen` / `SignupScreen`：邮箱+密码表单 + Google 登录按钮；错误用 SnackBar 展示中文文案（`AuthException` → `e.message`）
  - `HomeScreen`：登录后首页骨架——显示 `profile.username` 欢迎语 + 登出按钮

- [ ] **Step 1: 写路由守卫失败测试**

`code/fittrack/test/auth_guard_test.dart`：

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:fittrack/providers/auth_providers.dart';

void main() {
  test('未登录时 authStateProvider 初始为 null userId', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    // 无 Supabase 环境下 authStateChanges 返回空流 → 初始 loading，值非 String
    final sub = container.listen(authStateProvider, (_, __) {});
    expect(sub.read().hasValue || sub.read().isLoading, isTrue);
  });
}
```

（守卫的完整跳转逻辑依赖运行中的 router，放到 Step 6 手动验收；本测试锁 Provider 契约。）

- [ ] **Step 2: 跑测试验证失败**

```bash
cd /Users/plankton/code/fittrack && flutter test test/auth_guard_test.dart
```
Expected: FAIL，`authStateProvider` 未定义。

- [ ] **Step 3: 实现 providers**

`code/fittrack/lib/providers/auth_providers.dart`：

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/auth_repository.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return SupabaseAuthRepository();
});

final authStateProvider = StreamProvider<String?>((ref) {
  return ref.watch(authRepositoryProvider).authStateChanges();
});
```

- [ ] **Step 4: 跑测试验证通过**

```bash
cd /Users/plankton/code/fittrack && flutter test test/auth_guard_test.dart && flutter analyze
```
Expected: PASS + 无问题。

- [ ] **Step 5: 实现路由 + 三个屏幕**

路由守卫要读 Riverpod 容器里的登录态，采用社区标准模式：在 `main.dart` 建全局 `ProviderContainer` 传入 router。

`code/fittrack/lib/main.dart` 整体替换：

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/router.dart';
import 'config/supabase_config.dart';
import 'providers/auth_providers.dart';

late final ProviderContainer globalContainer;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SupabaseConfig.init();
  globalContainer = ProviderContainer();
  final authStream = globalContainer.read(authStateProvider.stream);
  runApp(FitTrackApp(refresh: RouterRefreshStream(authStream)));
}

class FitTrackApp extends StatelessWidget {
  const FitTrackApp({super.key, required this.refresh});
  final RouterRefreshStream refresh;

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'FitTrack',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF58CC02), useMaterial3: true),
      routerConfig: buildRouter(refresh, container: globalContainer),
    );
  }
}
```

`code/fittrack/lib/app/router.dart`：

```dart
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/auth/login_screen.dart';
import '../features/auth/signup_screen.dart';
import '../features/home/home_screen.dart';
import '../providers/auth_providers.dart';

class RouterRefreshStream extends ChangeNotifier {
  RouterRefreshStream(Stream<dynamic> stream) {
    _sub = stream.asBroadcastStream().listen((_) => notifyListeners());
  }
  late final StreamSubscription<dynamic> _sub;
  @override
  void dispose() {
    _sub.cancel();
    super.dispose();
  }
}

GoRouter buildRouter(RouterRefreshStream refresh,
    {required ProviderContainer container}) {
  return GoRouter(
    refreshListenable: refresh,
    initialLocation: '/login',
    redirect: (context, state) {
      final userId = container.read(authStateProvider).valueOrNull;
      final loggedIn = userId != null;
      final isAuthRoute = state.matchedLocation == '/login' ||
          state.matchedLocation == '/signup';
      if (!loggedIn && !isAuthRoute) return '/login';
      if (loggedIn && isAuthRoute) return '/';
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (c, s) => const LoginScreen()),
      GoRoute(path: '/signup', builder: (c, s) => const SignupScreen()),
      GoRoute(path: '/', builder: (c, s) => const HomeScreen()),
    ],
  );
}
```

`code/fittrack/lib/features/auth/login_screen.dart`：

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/auth_repository.dart';
import '../../providers/auth_providers.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _loading = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() => _loading = true);
    try {
      await ref.read(authRepositoryProvider).signInWithEmail(
            _email.text.trim(),
            _password.text,
          );
    } on AuthException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('登录失败：${e.message}')));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _google() async {
    try {
      await ref.read(authRepositoryProvider).signInWithGoogle();
    } on AuthException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Google 登录失败：${e.message}')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text('FitTrack', style: TextStyle(fontSize: 40, fontWeight: FontWeight.bold)),
                const SizedBox(height: 32),
                TextField(
                  controller: _email,
                  decoration: const InputDecoration(labelText: '邮箱', border: OutlineInputBorder()),
                  keyboardType: TextInputType.emailAddress,
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _password,
                  decoration: const InputDecoration(labelText: '密码', border: OutlineInputBorder()),
                  obscureText: true,
                ),
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: FilledButton(
                    onPressed: _loading ? null : _submit,
                    child: _loading
                        ? const SizedBox(width: 20, height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('登录'),
                  ),
                ),
                TextButton(
                  onPressed: () => context.go('/signup'),
                  child: const Text('没有账号？去注册'),
                ),
                const Divider(height: 32),
                OutlinedButton.icon(
                  onPressed: _google,
                  icon: const Text('G'),
                  label: const Text('使用 Google 登录'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
```

`code/fittrack/lib/features/auth/signup_screen.dart`：

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/auth_repository.dart';
import '../../providers/auth_providers.dart';

class SignupScreen extends ConsumerStatefulWidget {
  const SignupScreen({super.key});
  @override
  ConsumerState<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends ConsumerState<SignupScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _loading = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_password.text.length < 6) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('密码至少 6 位')));
      return;
    }
    setState(() => _loading = true);
    try {
      await ref.read(authRepositoryProvider).signUpWithEmail(
            _email.text.trim(),
            _password.text,
          );
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('注册成功')));
        context.go('/login');
      }
    } on AuthException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('注册失败：${e.message}')));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('注册')),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                TextField(
                  controller: _email,
                  decoration: const InputDecoration(labelText: '邮箱', border: OutlineInputBorder()),
                  keyboardType: TextInputType.emailAddress,
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _password,
                  decoration: const InputDecoration(labelText: '密码（至少 6 位）', border: OutlineInputBorder()),
                  obscureText: true,
                ),
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: FilledButton(
                    onPressed: _loading ? null : _submit,
                    child: _loading
                        ? const SizedBox(width: 20, height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('注册'),
                  ),
                ),
                TextButton(
                  onPressed: () => context.go('/login'),
                  child: const Text('已有账号？去登录'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
```

`code/fittrack/lib/features/home/home_screen.dart`：

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/auth_providers.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final userId = ref.watch(authStateProvider).valueOrNull;
    return Scaffold(
      appBar: AppBar(
        title: const Text('FitTrack'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: '登出',
            onPressed: () => ref.read(authRepositoryProvider).signOut(),
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('🎉 登录成功', style: TextStyle(fontSize: 22)),
            const SizedBox(height: 8),
            Text('用户ID：${userId ?? '未知'}', style: const TextStyle(fontSize: 12)),
            const SizedBox(height: 8),
            const Text('训练功能即将上线（计划 2/6）'),
          ],
        ),
      ),
    );
  }
}
```

- [ ] **Step 6: 手动验收（里程碑 1 验收）**

```bash
cd /Users/plankton/code/fittrack && flutter analyze && flutter test && flutter run
```

在模拟器依次操作并确认：
1. 首启落在 `/login`
2. 「没有账号？去注册」→ 填邮箱密码 → 注册成功提示 → 回登录页
3. 登录 → 自动跳 `/` 首页，显示用户ID
4. Supabase Dashboard → Authentication → Users：出现该用户；Table Editor → profiles：出现对应行（触发器生效）
5. 点登出 → 回到 `/login`
6. **RLS 抽查**：SQL Editor 跑 `select * from public.profiles;` 用 anon key 场景（Apply as anonymous）→ 只能看到 0 行或仅自己（无数据则 0 行），证明匿名读不到

- [ ] **Step 7: Commit**

```bash
cd /Users/plankton && git add code/fittrack
git commit -m "feat: 登录/注册 UI、路由守卫与首页骨架（里程碑1验收通过）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: RLS 隔离回归测试（两账号互验）

**Files:**
- Create: `code/fittrack/supabase/tests/rls_profiles_test.sql`

**Interfaces:**
- Consumes: Task 3 的 profiles 表与 RLS 策略
- Produces: 可重复执行的 RLS 隔离验证脚本（上线前回归跑），结果为「2 断言全过」

- [ ] **Step 1: 写 SQL 测试脚本**

`code/fittrack/supabase/tests/rls_profiles_test.sql`：

```sql
-- RLS 隔离测试：两个测试用户互验读不到对方 profile
-- 在 Supabase SQL Editor 以 postgres 角色运行（脚本内部切换身份模拟）

-- 造两个临时用户（若已存在则跳过错误）
insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-0000000000a1', 'rls_test_a@test.local', '{"provider":"email"}'::jsonb, '{}'::jsonb),
  ('00000000-0000-0000-0000-0000000000b2', 'rls_test_b@test.local', '{"provider":"email"}'::jsonb, '{}'::jsonb)
on conflict (id) do nothing;

-- 触发器应已为二者建 profile
do $$
declare a_count int; b_count int; leaked int;
begin
  select count(*) into a_count from public.profiles where id = '00000000-0000-0000-0000-0000000000a1';
  select count(*) into b_count from public.profiles where id = '00000000-0000-0000-0000-0000000000b2';
  if a_count <> 1 or b_count <> 1 then
    raise exception 'FAIL: 触发器未正确建 profile (a=%, b=%)', a_count, b_count;
  end if;

  -- 以 a 的身份查表：只能看到自己
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', json_build_object(
    'role','authenticated',
    'sub','00000000-0000-0000-0000-0000000000a1')::text, true);
  select count(*) into leaked from public.profiles
    where id <> '00000000-0000-0000-0000-0000000000a1';
  if leaked <> 0 then
    raise exception 'FAIL: 用户 a 读到了他人 profile（泄露 % 行）', leaked;
  end if;

  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  raise notice 'PASS: profiles RLS 隔离测试 2 项断言全部通过';
end $$;

-- 清理
delete from auth.users where id in
  ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b2');
```

- [ ] **Step 2: 在 SQL Editor 执行**

粘贴运行。
Expected: 输出 `PASS: profiles RLS 隔离测试 2 项断言全部通过`。
若 FAIL：检查 Task 3 的策略是否都创建成功（Authentication → Policies）。

- [ ] **Step 3: Commit**

```bash
cd /Users/plankton && git add code/fittrack/supabase
git commit -m "test: profiles 表 RLS 隔离回归脚本（两账号互验）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 完成定义（本计划）

- [ ] `flutter analyze` 0 问题、`flutter test` 全过
- [ ] 模拟器：注册→登录→看到首页→登出 全流程通过
- [ ] profiles 触发器与 RLS 隔离脚本 PASS
- [ ] 里程碑 1 验收达成：「新用户可注册并进入空首页」

## 下一步

计划 2/6（里程碑 2：动作库 + 训练计划 CRUD）在本计划完成后再行编写——届时将基于真实工程结构写更精确的任务分解。
