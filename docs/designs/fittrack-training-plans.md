# FitTrack 动作库与训练计划 MVP

| 属性 | 值 |
| --- | --- |
| 状态 | Implementing |
| 对应里程碑 | 2：计划 |
| 设计依据 | `docs/superpowers/specs/2026-08-14-fittrack-commercial-refactor-design.md` |
| ADR | [0022](../decisions/0022-fittrack-training-plan-data-boundary.html) |

## Context

用户已可进入 FitTrack 首页，但还不能定义一次训练。里程碑 2 提供动作选择和可持久化训练计划，作为后续训练执行的唯一计划数据来源。

## Definitions

- 系统动作：`owner_id is null`、`is_custom = false` 的预置动作；所有已认证用户可读，任何客户端不可写。
- 自定义动作：`owner_id = auth.uid()`、`is_custom = true`；仅创建者可读写。
- 训练计划：归当前用户所有的有序动作列表。`plan_exercises.sort_order` 是一张计划内的稳定顺序，从零开始。

## Architecture and flow

```text
PlanScreen → Riverpod providers → ExerciseRepository / WorkoutPlanRepository
                                   → Supabase RLS tables
```

UI 只依赖 repository 抽象；数据库策略负责系统动作只读和用户数据隔离。创建或编辑计划时，客户端先保存计划元数据，再以一次受事务约束的 RPC 替换计划动作，避免短暂的半成品计划。

## Decisions

1. 系统动作和用户动作共用 `exercises`，以 `owner_id` 区分，详见 ADR-0022。
2. 计划动作通过 `save_workout_plan` RPC 原子替换；不让客户端先删后逐条写入。
3. 本里程碑只提供计划名、描述和动作顺序/默认组次数重量；训练实例与离线缓冲留给里程碑 3。

## Boundary cases

- 空计划、空动作名、重复动作或不连续排序在数据库函数中拒绝。
- 删除计划级联删除 `plan_exercises`，不删除共享或自定义动作。
- 计划的外键和 RPC 均验证当前用户拥有计划及所有自定义动作。

## Tests and verification

- SQL：RLS 各角色策略与 `save_workout_plan` 的所有权检查。
- Dart：数据模型 JSON 转换、repository 调用映射及计划编辑状态。
- Android：创建自定义动作 → 创建计划 → 编辑动作顺序 → 删除计划。
