import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/fittrack_theme.dart';
import '../../data/auth_repository.dart';
import '../../models/workout_plan.dart';
import '../../providers/auth_providers.dart';
import '../../providers/plan_providers.dart';
import '../../providers/workout_providers.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  Future<void> _signOut(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(authRepositoryProvider).signOut();
    } on AuthException catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('登出失败：${error.message}')));
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plans = ref.watch(workoutPlansProvider);

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.go('/plans/new'),
        icon: const Icon(Icons.add_rounded),
        label: const Text('新建计划'),
      ),
      body: SafeArea(
        child: plans.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => _PlanLoadError(
            onRetry: () => ref.invalidate(workoutPlansProvider),
          ),
          data: (items) =>
              _PlanList(plans: items, onSignOut: () => _signOut(context, ref)),
        ),
      ),
    );
  }
}

class _PlanList extends ConsumerWidget {
  const _PlanList({required this.plans, required this.onSignOut});

  final List<WorkoutPlan> plans;
  final VoidCallback onSignOut;

  Future<void> _syncFinishedDrafts(BuildContext context, WidgetRef ref) async {
    final drafts = await ref.read(finishedWorkoutDraftsProvider.future);
    var synced = 0;
    for (final draft in drafts) {
      try {
        await ref
            .read(workoutBufferRepositoryProvider)
            .syncFinishedDraft(draft);
        synced += 1;
      } catch (_) {
        // Keep the local draft; a later retry must never lose this training.
      }
    }
    ref.invalidate(finishedWorkoutDraftsProvider);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            synced == drafts.length
                ? '已同步 $synced 条训练记录。'
                : '已同步 $synced 条，其余记录仍安全保存在本机。',
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final actionCount = plans.fold<int>(
      0,
      (sum, plan) => sum + plan.entries.length,
    );
    final pendingDrafts =
        ref.watch(finishedWorkoutDraftsProvider).valueOrNull ?? const [];

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 112),
      children: [
        _TopBar(onSignOut: onSignOut),
        const SizedBox(height: 18),
        _TrainingHero(planCount: plans.length, actionCount: actionCount),
        if (pendingDrafts.isNotEmpty) ...[
          const SizedBox(height: 12),
          _PendingSyncNotice(
            count: pendingDrafts.length,
            onSync: () => _syncFinishedDrafts(context, ref),
          ),
        ],
        const SizedBox(height: 32),
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('训练计划', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 3),
                const Text(
                  '把今天要做的事，安排得明明白白。',
                  style: TextStyle(color: FitTrackTheme.muted),
                ),
              ],
            ),
            Text(
              '${plans.length.toString().padLeft(2, '0')} 份',
              style: const TextStyle(
                color: FitTrackTheme.muted,
                fontSize: 13,
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        if (plans.isEmpty)
          const _EmptyPlans()
        else
          ...List.generate(
            plans.length,
            (index) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _PlanCard(plan: plans[index], index: index),
            ),
          ),
      ],
    );
  }
}

class _PendingSyncNotice extends StatelessWidget {
  const _PendingSyncNotice({required this.count, required this.onSync});

  final int count;
  final VoidCallback onSync;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 10, 12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFE0D5),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          const Icon(Icons.cloud_upload_outlined, color: FitTrackTheme.signal),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              '$count 条训练等待同步',
              style: const TextStyle(fontWeight: FontWeight.w900),
            ),
          ),
          TextButton(onPressed: onSync, child: const Text('立即同步')),
        ],
      ),
    );
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({required this.onSignOut});

  final VoidCallback onSignOut;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          height: 38,
          width: 38,
          alignment: Alignment.center,
          decoration: const BoxDecoration(
            color: FitTrackTheme.ink,
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.bolt_rounded,
            color: FitTrackTheme.lime,
            size: 23,
          ),
        ),
        const SizedBox(width: 10),
        const Expanded(
          child: Text(
            'FIT / TRACK',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w900,
              letterSpacing: 1.3,
            ),
          ),
        ),
        IconButton(
          onPressed: onSignOut,
          tooltip: '登出',
          icon: const Icon(Icons.more_horiz_rounded),
          style: IconButton.styleFrom(
            backgroundColor: Colors.white,
            foregroundColor: FitTrackTheme.ink,
            side: const BorderSide(color: FitTrackTheme.line),
          ),
        ),
      ],
    );
  }
}

class _TrainingHero extends StatelessWidget {
  const _TrainingHero({required this.planCount, required this.actionCount});

  final int planCount;
  final int actionCount;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 22, 18, 18),
      decoration: BoxDecoration(
        color: FitTrackTheme.ink,
        borderRadius: BorderRadius.circular(30),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.circle, color: FitTrackTheme.signal, size: 10),
              SizedBox(width: 7),
              Text(
                '准备就绪',
                style: TextStyle(
                  color: FitTrackTheme.lime,
                  fontWeight: FontWeight.w900,
                  letterSpacing: .6,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          Text(
            '下一次进步，\n从清晰的计划开始。',
            style: Theme.of(context).textTheme.headlineMedium
                ?.copyWith(color: const Color(0xFFF7FFE9), fontSize: 30),
          ),
          const SizedBox(height: 24),
          Row(
            children: [
              _Metric(value: '$planCount', label: '训练计划'),
              const SizedBox(width: 26),
              _Metric(value: '$actionCount', label: '已编排动作'),
              const Spacer(),
              Container(
                width: 52,
                height: 52,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  color: FitTrackTheme.lime,
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.arrow_outward_rounded,
                  color: FitTrackTheme.ink,
                  size: 28,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({required this.value, required this.label});

  final String value;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          style: const TextStyle(
            color: FitTrackTheme.lime,
            fontSize: 23,
            fontWeight: FontWeight.w900,
            height: 1,
          ),
        ),
        const SizedBox(height: 5),
        Text(
          label,
          style: const TextStyle(color: Color(0xFFB8C5B2), fontSize: 12),
        ),
      ],
    );
  }
}

class _EmptyPlans extends StatelessWidget {
  const _EmptyPlans();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white,
        border: Border.all(color: FitTrackTheme.line),
        borderRadius: BorderRadius.circular(24),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            Icons.auto_awesome_motion_rounded,
            size: 30,
            color: FitTrackTheme.forest,
          ),
          SizedBox(height: 18),
          Text(
            '还没有训练计划',
            style: TextStyle(fontSize: 19, fontWeight: FontWeight.w900),
          ),
          SizedBox(height: 8),
          Text(
            '点击右下角「新建计划」，把下一次训练拆成清晰的动作顺序。',
            style: TextStyle(color: FitTrackTheme.muted, height: 1.5),
          ),
        ],
      ),
    );
  }
}

class _PlanCard extends StatelessWidget {
  const _PlanCard({required this.plan, required this.index});

  final WorkoutPlan plan;
  final int index;

  @override
  Widget build(BuildContext context) {
    final preview = plan.entries
        .take(3)
        .map((entry) => entry.exercise.name)
        .join(' · ');

    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(24),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => context.go('/workouts/${plan.id}'),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 14, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    height: 42,
                    width: 42,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: index.isEven
                          ? FitTrackTheme.lime
                          : const Color(0xFFFFD6C8),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Text(
                      '${index + 1}'.padLeft(2, '0'),
                      style: const TextStyle(fontWeight: FontWeight.w900),
                    ),
                  ),
                  const Spacer(),
                  const Icon(
                    Icons.north_east_rounded,
                    color: FitTrackTheme.muted,
                    size: 20,
                  ),
                ],
              ),
              const SizedBox(height: 18),
              Text(plan.name, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 7),
              Text(
                plan.description.isEmpty ? '尚未添加训练备注' : plan.description,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: FitTrackTheme.muted),
              ),
              const SizedBox(height: 16),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: FitTrackTheme.mist,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  '${plan.entries.length} 个动作${preview.isEmpty ? '' : '  ·  $preview'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: FitTrackTheme.forest,
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  const Icon(
                    Icons.play_arrow_rounded,
                    color: FitTrackTheme.forest,
                    size: 19,
                  ),
                  const SizedBox(width: 4),
                  const Text(
                    '开始训练',
                    style: TextStyle(
                      color: FitTrackTheme.forest,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const Spacer(),
                  TextButton(
                    onPressed: () => context.go('/plans/${plan.id}/edit'),
                    child: const Text('编排'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PlanLoadError extends StatelessWidget {
  const _PlanLoadError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.cloud_off_rounded,
              size: 42,
              color: FitTrackTheme.muted,
            ),
            const SizedBox(height: 16),
            const Text(
              '暂时无法读取训练计划',
              style: TextStyle(fontWeight: FontWeight.w900),
            ),
            const SizedBox(height: 8),
            const Text('请确认网络后重试。', textAlign: TextAlign.center),
            const SizedBox(height: 16),
            OutlinedButton(onPressed: onRetry, child: const Text('重新加载')),
          ],
        ),
      ),
    );
  }
}
