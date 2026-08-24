import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/data/local/workout_buffer_database.dart';
import 'package:fittrack/models/workout_draft.dart';

void main() {
  test('训练草稿 JSON 保留每组的完成时间和重量', () {
    final startedAt = DateTime.utc(2026, 8, 24, 10);
    final completedAt = DateTime.utc(2026, 8, 24, 10, 5);
    final draft = WorkoutDraft(
      localId: 'local-1',
      ownerId: 'user-1',
      planId: 'plan-1',
      name: '上肢力量',
      startedAt: startedAt,
      endedAt: completedAt,
      exercises: [
        WorkoutExerciseDraft(
          exerciseId: 'exercise-1',
          exerciseName: '杠铃卧推',
          sortOrder: 0,
          sets: [
            WorkoutSetDraft(
              setIndex: 0,
              weight: 60,
              reps: 8,
              completedAt: completedAt,
            ),
          ],
        ),
      ],
    );

    final restored = WorkoutDraft.fromPayloadJson(
      localId: draft.localId,
      ownerId: draft.ownerId,
      planId: draft.planId,
      name: draft.name,
      startedAt: draft.startedAt,
      endedAt: draft.endedAt,
      payloadJson: draft.toPayloadJson(),
    );

    expect(restored.exercises.single.sets.single.weight, 60);
    expect(
      restored.exercises.single.sets.single.completedAt,
      completedAt.toLocal(),
    );
    expect(restored.isFinished, isTrue);
  });

  test('Drift 缓冲在删除前保留训练草稿', () async {
    final database = WorkoutBufferDatabase(NativeDatabase.memory());
    addTearDown(database.close);
    final draft = WorkoutDraft(
      localId: 'local-1',
      ownerId: 'user-1',
      name: '下肢力量',
      startedAt: DateTime.utc(2026, 8, 24, 11),
      exercises: const [],
    );

    await database.saveDraft(draft);
    expect((await database.readDrafts('user-1')).single.name, '下肢力量');

    await database.deleteDraft(draft.localId);
    expect(await database.readDrafts('user-1'), isEmpty);
  });
}
