import 'package:fittrack/models/exercise.dart';
import 'package:fittrack/services/plan_generator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('力量新手生成器优先选择复合动作并给出保守训练量', () {
    const exercises = [
      Exercise(
        id: '1',
        name: '杠铃深蹲',
        category: '复合',
        muscleGroup: '股四头肌',
        isCustom: false,
      ),
      Exercise(
        id: '2',
        name: '杠铃卧推',
        category: '复合',
        muscleGroup: '胸大肌',
        isCustom: false,
      ),
      Exercise(
        id: '3',
        name: '传统硬拉',
        category: '复合',
        muscleGroup: '背部',
        isCustom: false,
      ),
      Exercise(
        id: '4',
        name: '杠铃划船',
        category: '复合',
        muscleGroup: '背部',
        isCustom: false,
      ),
    ];
    final plan = const PlanGenerator().generate(
      const PlanGenerationRequest(
        goal: TrainingGoal.strength,
        experience: TrainingExperience.beginner,
        daysPerWeek: 2,
      ),
      exercises,
    );
    expect(plan.name, '力量基础计划');
    expect(plan.entries, hasLength(4));
    expect(plan.entries.first.defaultSets, 3);
    expect(plan.entries.first.defaultReps, 5);
  });
}
