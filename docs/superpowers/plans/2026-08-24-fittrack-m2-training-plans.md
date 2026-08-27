# FitTrack 计划 2/6：动作库与训练计划

## Goal

交付可创建、编辑和删除的训练计划；计划由系统预置或用户自定义动作组成，并由数据库 RLS 隔离。

## Tasks

1. 新增 `exercises`、`workout_plans`、`plan_exercises` 迁移、索引、RLS、种子动作与原子保存 RPC。
2. 实现 Dart models、Repository 抽象及 Riverpod providers；为映射和 repository 契约编写测试。
3. 将首页改为计划列表，支持新建、编辑、删除计划和创建自定义动作。
4. 执行 SQL/RLS 验证、`flutter test`、`flutter analyze`、APK 构建和 Android 冒烟。

## Completion definition

- 已登录用户可从系统或自定义动作建立计划，重启后仍可读回。
- 不能读取或修改另一用户的动作/计划；不能写系统动作。
- `flutter analyze` 无问题，所有测试和 Android 构建通过。
