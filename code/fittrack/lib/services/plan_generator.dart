import '../models/exercise.dart';
import '../models/workout_plan.dart';

enum TrainingGoal { strength, muscle, fitness }

enum TrainingExperience { beginner, intermediate, advanced }

class PlanGenerationRequest {
  const PlanGenerationRequest({
    required this.goal,
    required this.experience,
    required this.daysPerWeek,
  });

  final TrainingGoal goal;
  final TrainingExperience experience;
  final int daysPerWeek;
}

class GeneratedWorkoutPlan {
  const GeneratedWorkoutPlan({
    required this.name,
    required this.description,
    required this.entries,
  });

  final String name;
  final String description;
  final List<PlanExerciseDraft> entries;
}

class GeneratedPlanBundle {
  const GeneratedPlanBundle({required this.plans});
  final List<GeneratedWorkoutPlan> plans;
}

/// Deterministic offline plan generator. It only recommends exercises available
/// in the user's library, so its output can be saved through the normal plan RPC.
class PlanGenerator {
  const PlanGenerator();

  GeneratedWorkoutPlan generate(
    PlanGenerationRequest request,
    List<Exercise> available,
  ) {
    return generateBundle(request, available).plans.first;
  }

  GeneratedPlanBundle generateBundle(
    PlanGenerationRequest request,
    List<Exercise> available,
  ) {
    if (available.isEmpty) throw StateError('动作库为空，无法生成计划');
    final names = switch (request.goal) {
      TrainingGoal.strength => ['杠铃深蹲', '杠铃卧推', '传统硬拉', '杠铃划船', '杠铃肩推'],
      TrainingGoal.muscle => ['高杠深蹲', '上斜哑铃卧推', '高位下拉', '腿屈伸', '哑铃侧平举', '绳索下压'],
      TrainingGoal.fitness => ['腿举', '俯卧撑', '坐姿划船', '箭步蹲', '跑步机跑步'],
    };
    final targetCount = request.daysPerWeek * 3;
    final sets = switch (request.experience) {
      TrainingExperience.beginner => 3,
      TrainingExperience.intermediate => 4,
      TrainingExperience.advanced => 5,
    };
    final reps = request.goal == TrainingGoal.strength ? 5 : 10;
    final byName = {for (final exercise in available) exercise.name: exercise};
    final selected = <Exercise>[];
    for (final name in names) {
      final exercise = byName[name];
      if (exercise != null && !selected.contains(exercise)) {
        selected.add(exercise);
      }
      if (selected.length == targetCount) {
        break;
      }
    }
    for (final exercise in available) {
      if (!selected.contains(exercise)) {
        selected.add(exercise);
      }
      if (selected.length == targetCount) {
        break;
      }
    }
    final label = switch (request.goal) {
      TrainingGoal.strength => '力量',
      TrainingGoal.muscle => '增肌',
      TrainingGoal.fitness => '体能',
    };
    return GeneratedPlanBundle(
      plans: List.generate(request.daysPerWeek, (dayIndex) {
        final entries = <PlanExerciseDraft>[];
        for (
          var index = dayIndex;
          index < selected.length;
          index += request.daysPerWeek
        ) {
          entries.add(
            PlanExerciseDraft(
              exercise: selected[index],
              defaultSets: sets,
              defaultReps: reps,
            ),
          );
        }
        return GeneratedWorkoutPlan(
          name: '$label基础 · 第 ${dayIndex + 1} 天',
          description: '自动生成 · 每周 ${request.daysPerWeek} 天 · 可按实际训练继续调整',
          entries: entries,
        );
      }),
    );
  }
}
