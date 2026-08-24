import 'package:supabase_flutter/supabase_flutter.dart';

import '../models/workout_draft.dart';

abstract class WorkoutSyncRepository {
  Future<String> syncFinishedWorkout(WorkoutDraft draft);
}

class SupabaseWorkoutSyncRepository implements WorkoutSyncRepository {
  SupabaseWorkoutSyncRepository({SupabaseClient? client})
    : _client = client ?? Supabase.instance.client;

  final SupabaseClient _client;

  @override
  Future<String> syncFinishedWorkout(WorkoutDraft draft) async {
    if (!draft.isFinished) {
      throw StateError('未结束的训练不能同步');
    }
    final sessionId = await _client.rpc(
      'sync_workout_session',
      params: {
        'p_client_id': draft.localId,
        'p_plan_id': draft.planId,
        'p_name': draft.name,
        'p_started_at': draft.startedAt.toUtc().toIso8601String(),
        'p_ended_at': draft.endedAt!.toUtc().toIso8601String(),
        'p_exercises': draft.exercises
            .map((exercise) => exercise.toJson())
            .toList(),
      },
    );
    return sessionId as String;
  }
}
