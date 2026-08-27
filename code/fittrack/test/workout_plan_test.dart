import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/models/exercise.dart';
import 'package:fittrack/models/workout_plan.dart';

void main() {
  test('WorkoutPlan 会按 sort_order 解析嵌套动作', () {
    final plan = WorkoutPlan.fromJson({
      'id': 'plan-1',
      'name': '上肢力量',
      'description': '推拉组合',
      'plan_exercises': [
        {
          'id': 'entry-2',
          'sort_order': 1,
          'default_sets': 4,
          'default_reps': 8,
          'default_weight': 60,
          'exercises': {
            'id': 'exercise-2',
            'name': '杠铃划船',
            'category': '复合',
            'muscle_group': '背部',
            'is_custom': false,
          },
        },
        {
          'id': 'entry-1',
          'sort_order': 0,
          'default_sets': 3,
          'default_reps': 10,
          'default_weight': 40.5,
          'exercises': {
            'id': 'exercise-1',
            'name': '杠铃卧推',
            'category': '复合',
            'muscle_group': '胸大肌',
            'is_custom': false,
          },
        },
      ],
    });

    expect(plan.entries.map((entry) => entry.exercise.name), ['杠铃卧推', '杠铃划船']);
    expect(plan.entries.first.defaultWeight, 40.5);
  });

  test('PlanExerciseDraft 生成 RPC 契约字段', () {
    const draft = PlanExerciseDraft(
      exercise: Exercise(
        id: 'exercise-1',
        name: '杠铃卧推',
        category: '复合',
        muscleGroup: '胸大肌',
        isCustom: false,
      ),
      defaultSets: 5,
      defaultReps: 5,
      defaultWeight: 80,
    );

    expect(draft.toJson(), {
      'exercise_id': 'exercise-1',
      'default_sets': 5,
      'default_reps': 5,
      'default_weight': 80.0,
    });
  });
}
