import 'package:fittrack/models/workout_history.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('历史训练从嵌套 session 数据计算组数、体量和估算 1RM', () {
    final session = WorkoutSessionSummary.fromJson({
      'id': 'session-1',
      'name': '上肢力量',
      'started_at': '2026-08-24T10:00:00Z',
      'ended_at': '2026-08-24T10:45:00Z',
      'session_exercises': [
        {
          'id': 'session-exercise-1',
          'sort_order': 0,
          'exercises': {'name': '杠铃卧推'},
          'session_sets': [
            {
              'set_index': 1,
              'weight': 60,
              'reps': 8,
              'completed_at': '2026-08-24T10:20:00Z',
            },
            {
              'set_index': 0,
              'weight': 55,
              'reps': 10,
              'completed_at': '2026-08-24T10:10:00Z',
            },
          ],
        },
      ],
    });

    expect(session.completedSets, 2);
    expect(session.volume, 1030);
    expect(session.exercises.single.sets.first.weight, 55);
    expect(session.estimatedOneRepMax, closeTo(76, .01));
    expect(session.durationMinutes, 45);
  });

  test('体重记录解析 date 与 numeric 值', () {
    final log = BodyweightLog.fromJson({
      'logged_on': '2026-08-24',
      'weight': 72.5,
    });

    expect(log.loggedOn, DateTime(2026, 8, 24));
    expect(log.weight, 72.5);
  });
}
