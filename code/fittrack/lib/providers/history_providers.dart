import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/workout_history_repository.dart';
import '../models/workout_history.dart';

final workoutHistoryRepositoryProvider = Provider<WorkoutHistoryRepository>((
  ref,
) {
  return SupabaseWorkoutHistoryRepository();
});

final workoutHistoryProvider = FutureProvider<List<WorkoutSessionSummary>>((
  ref,
) {
  return ref.watch(workoutHistoryRepositoryProvider).listSessions();
});

final bodyweightLogsProvider = FutureProvider<List<BodyweightLog>>((ref) {
  return ref.watch(workoutHistoryRepositoryProvider).listBodyweightLogs();
});

final workoutSessionProvider =
    FutureProvider.family<WorkoutSessionSummary, String>((ref, sessionId) {
      return ref.watch(workoutHistoryRepositoryProvider).getSession(sessionId);
    });
