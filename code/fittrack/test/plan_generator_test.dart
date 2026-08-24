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
    final bundle = const PlanGenerator().generateBundle(
      const PlanGenerationRequest(
        goal: TrainingGoal.strength,
        experience: TrainingExperience.beginner,
        daysPerWeek: 2,
      ),
      exercises,
    );
    expect(bundle.plans, hasLength(2));
    expect(bundle.plans.map((plan) => plan.entries.length), [2, 2]);
    expect(bundle.plans.first.entries.first.defaultSets, 3);
    expect(bundle.plans.first.entries.first.defaultReps, 5);
  });

  test('多日计划均有动作、不会重复，并按经验调整组数', () {
    const exercises = [
      Exercise(
        id: '1',
        name: '高杠深蹲',
        category: '复合',
        muscleGroup: '腿',
        isCustom: false,
      ),
      Exercise(
        id: '2',
        name: '上斜哑铃卧推',
        category: '复合',
        muscleGroup: '胸',
        isCustom: false,
      ),
      Exercise(
        id: '3',
        name: '高位下拉',
        category: '复合',
        muscleGroup: '背',
        isCustom: false,
      ),
      Exercise(
        id: '4',
        name: '腿屈伸',
        category: '孤立',
        muscleGroup: '腿',
        isCustom: false,
      ),
      Exercise(
        id: '5',
        name: '哑铃侧平举',
        category: '孤立',
        muscleGroup: '肩',
        isCustom: false,
      ),
      Exercise(
        id: '6',
        name: '绳索下压',
        category: '孤立',
        muscleGroup: '臂',
        isCustom: false,
      ),
      Exercise(
        id: '7',
        name: '卷腹',
        category: '孤立',
        muscleGroup: '核心',
        isCustom: false,
      ),
    ];
    final bundle = const PlanGenerator().generateBundle(
      const PlanGenerationRequest(
        goal: TrainingGoal.muscle,
        experience: TrainingExperience.intermediate,
        daysPerWeek: 3,
      ),
      exercises,
    );

    final entries = bundle.plans.expand((plan) => plan.entries).toList();
    expect(bundle.plans, hasLength(3));
    expect(bundle.plans.every((plan) => plan.entries.isNotEmpty), isTrue);
    expect(
      entries.map((entry) => entry.exercise.id).toSet(),
      hasLength(entries.length),
    );
    expect(entries.every((entry) => entry.defaultSets == 4), isTrue);
    expect(entries.every((entry) => entry.defaultReps == 10), isTrue);
  });

  test('动作数量不足时拒绝生成空白训练日', () {
    const exercise = Exercise(
      id: '1',
      name: '杠铃深蹲',
      category: '复合',
      muscleGroup: '股四头肌',
      isCustom: false,
    );

    expect(
      () => const PlanGenerator().generateBundle(
        const PlanGenerationRequest(
          goal: TrainingGoal.strength,
          experience: TrainingExperience.beginner,
          daysPerWeek: 2,
        ),
        [exercise],
      ),
      throwsA(isA<StateError>()),
    );
  });
}
