import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/auth_repository.dart';
import '../../models/workout_plan.dart';
import '../../providers/auth_providers.dart';
import '../../providers/plan_providers.dart';

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
      appBar: AppBar(
        title: const Text('FitTrack'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout_rounded),
            tooltip: '登出',
            onPressed: () => _signOut(context, ref),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.go('/plans/new'),
        backgroundColor: const Color(0xFFB7F34B),
        foregroundColor: const Color(0xFF11210C),
        icon: const Icon(Icons.add_rounded),
        label: const Text('新建计划'),
      ),
      body: plans.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) =>
            _PlanLoadError(onRetry: () => ref.invalidate(workoutPlansProvider)),
        data: (items) => _PlanList(plans: items),
      ),
    );
  }
}

class _PlanList extends StatelessWidget {
  const _PlanList({required this.plans});

  final List<WorkoutPlan> plans;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 104),
      children: [
        const _WelcomeBlock(),
        const SizedBox(height: 32),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              '我的计划',
              style: Theme.of(context).textTheme.titleLarge
                  ?.copyWith(fontWeight: FontWeight.w900),
            ),
            Text(
              '${plans.length} 个',
              style: const TextStyle(color: Color(0xFF667064)),
            ),
          ],
        ),
        const SizedBox(height: 14),
        if (plans.isEmpty)
          const _EmptyPlans()
        else
          ...plans.map(
            (plan) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _PlanCard(plan: plan),
            ),
          ),
      ],
    );
  }
}

class _WelcomeBlock extends StatelessWidget {
  const _WelcomeBlock();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: const Color(0xFF11210C),
        borderRadius: BorderRadius.circular(28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: const Color(0xFF27471B),
              borderRadius: BorderRadius.circular(99),
            ),
            child: const Text(
              '你的训练档案',
              style: TextStyle(
                color: Color(0xFFB7F34B),
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
          const SizedBox(height: 20),
          Text(
            '下一次进步，\n从一份计划开始。',
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
              color: const Color(0xFFF4FFE8),
              fontWeight: FontWeight.w900,
              height: 1.05,
            ),
          ),
          const SizedBox(height: 12),
          const Text(
            '先建立动作顺序与默认重量，训练时只管专注完成。',
            style: TextStyle(color: Color(0xFFBBCAB3), height: 1.5),
          ),
        ],
      ),
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
        border: Border.all(color: const Color(0xFFD6D9D0)),
        borderRadius: BorderRadius.circular(24),
        color: Colors.white,
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            Icons.auto_awesome_motion_rounded,
            size: 30,
            color: Color(0xFF477A0E),
          ),
          SizedBox(height: 18),
          Text(
            '还没有训练计划',
            style: TextStyle(fontSize: 19, fontWeight: FontWeight.w900),
          ),
          SizedBox(height: 8),
          Text('点击右下角「新建计划」，把下一次训练拆成清晰的动作顺序。'),
        ],
      ),
    );
  }
}

class _PlanCard extends StatelessWidget {
  const _PlanCard({required this.plan});

  final WorkoutPlan plan;

  @override
  Widget build(BuildContext context) {
    final preview = plan.entries
        .take(3)
        .map((entry) => entry.exercise.name)
        .join(' · ');

    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(22),
      child: InkWell(
        borderRadius: BorderRadius.circular(22),
        onTap: () => context.go('/plans/${plan.id}/edit'),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(
            children: [
              Container(
                height: 48,
                width: 48,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: const Color(0xFFE5FBC1),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: const Icon(
                  Icons.fitness_center_rounded,
                  color: Color(0xFF244315),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      plan.name,
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      '${plan.entries.length} 个动作${preview.isEmpty ? '' : ' · $preview'}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Color(0xFF667064)),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded),
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
              color: Color(0xFF667064),
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
