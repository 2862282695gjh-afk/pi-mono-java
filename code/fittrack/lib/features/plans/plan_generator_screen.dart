import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/fittrack_theme.dart';
import '../../models/exercise.dart';
import '../../models/workout_plan.dart';
import '../../providers/plan_providers.dart';
import '../../services/plan_generator.dart';

class PlanGeneratorScreen extends ConsumerStatefulWidget {
  const PlanGeneratorScreen({super.key});

  @override
  ConsumerState<PlanGeneratorScreen> createState() =>
      _PlanGeneratorScreenState();
}

class _PlanGeneratorScreenState extends ConsumerState<PlanGeneratorScreen> {
  TrainingGoal _goal = TrainingGoal.muscle;
  TrainingExperience _experience = TrainingExperience.beginner;
  int _days = 3;
  GeneratedPlanBundle? _generated;
  bool _isSaving = false;

  void _generate(List<Exercise> exercises) {
    setState(() {
      _generated = const PlanGenerator().generateBundle(
        PlanGenerationRequest(
          goal: _goal,
          experience: _experience,
          daysPerWeek: _days,
        ),
        exercises,
      );
    });
  }

  Future<void> _save() async {
    final bundle = _generated;
    if (bundle == null) return;
    setState(() => _isSaving = true);
    try {
      await ref
          .read(workoutPlanRepositoryProvider)
          .savePlanBundle(
            bundle.plans
                .map(
                  (plan) => PlanSaveDraft(
                    name: plan.name,
                    description: plan.description,
                    entries: plan.entries,
                  ),
                )
                .toList(growable: false),
          );
      ref.invalidate(workoutPlansProvider);
      if (mounted) context.go('/');
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('生成计划暂时无法保存，请稍后重试。')));
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final exercises = ref.watch(exercisesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('智能生成计划')),
      body: exercises.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => const Center(child: Text('动作库暂时不可用')),
        data: (items) => ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          children: [
            const Text(
              '告诉我你的节奏',
              style: TextStyle(
                color: FitTrackTheme.signal,
                fontWeight: FontWeight.w900,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              '先给你一份\n能直接开始的计划。',
              style: Theme.of(context).textTheme.headlineSmall
                  ?.copyWith(fontSize: 28),
            ),
            const SizedBox(height: 28),
            _ChoiceSection<TrainingGoal>(
              label: '训练目标',
              value: _goal,
              values: const [
                TrainingGoal.strength,
                TrainingGoal.muscle,
                TrainingGoal.fitness,
              ],
              text: (value) => switch (value) {
                TrainingGoal.strength => '力量',
                TrainingGoal.muscle => '增肌',
                TrainingGoal.fitness => '体能',
              },
              onChanged: (value) => setState(() => _goal = value),
            ),
            const SizedBox(height: 20),
            _ChoiceSection<TrainingExperience>(
              label: '训练经验',
              value: _experience,
              values: const [
                TrainingExperience.beginner,
                TrainingExperience.intermediate,
                TrainingExperience.advanced,
              ],
              text: (value) => switch (value) {
                TrainingExperience.beginner => '新手',
                TrainingExperience.intermediate => '进阶',
                TrainingExperience.advanced => '熟练',
              },
              onChanged: (value) => setState(() => _experience = value),
            ),
            const SizedBox(height: 20),
            _ChoiceSection<int>(
              label: '每周训练天数',
              value: _days,
              values: const [2, 3, 4],
              text: (value) => '$value 天',
              onChanged: (value) => setState(() => _days = value),
            ),
            const SizedBox(height: 28),
            FilledButton.icon(
              onPressed: () => _generate(items),
              icon: const Icon(Icons.auto_awesome_rounded),
              label: const Text('生成我的计划'),
            ),
            if (_generated != null) ...[
              const SizedBox(height: 24),
              _GeneratedPreview(bundle: _generated!),
              const SizedBox(height: 14),
              OutlinedButton.icon(
                onPressed: _isSaving ? null : _save,
                icon: const Icon(Icons.edit_note_rounded),
                label: Text(
                  _isSaving ? '正在保存…' : '保存 ${_generated!.plans.length} 份计划',
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ChoiceSection<T> extends StatelessWidget {
  const _ChoiceSection({
    required this.label,
    required this.value,
    required this.values,
    required this.text,
    required this.onChanged,
  });
  final String label;
  final T value;
  final List<T> values;
  final String Function(T) text;
  final ValueChanged<T> onChanged;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label, style: const TextStyle(fontWeight: FontWeight.w900)),
      const SizedBox(height: 10),
      Wrap(
        spacing: 8,
        children: values
            .map(
              (item) => ChoiceChip(
                label: Text(text(item)),
                selected: item == value,
                onSelected: (_) => onChanged(item),
              ),
            )
            .toList(),
      ),
    ],
  );
}

class _GeneratedPreview extends StatelessWidget {
  const _GeneratedPreview({required this.bundle});
  final GeneratedPlanBundle bundle;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(20),
    decoration: BoxDecoration(
      color: FitTrackTheme.ink,
      borderRadius: BorderRadius.circular(24),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '已为你生成',
          style: TextStyle(
            color: FitTrackTheme.lime,
            fontWeight: FontWeight.w900,
          ),
        ),
        const SizedBox(height: 7),
        ...bundle.plans.expand(
          (plan) => [
            const SizedBox(height: 10),
            Text(
              plan.name,
              style: const TextStyle(
                color: Color(0xFFF7FFE9),
                fontSize: 18,
                fontWeight: FontWeight.w900,
              ),
            ),
            ...plan.entries.map(
              (entry) => Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  '${entry.exercise.name}  ·  ${entry.defaultSets} 组 × ${entry.defaultReps} 次',
                  style: const TextStyle(color: Color(0xFFD5E1CE)),
                ),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}
