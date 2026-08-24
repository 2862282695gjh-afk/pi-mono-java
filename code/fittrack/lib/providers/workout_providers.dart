import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/local/workout_buffer_database.dart';
import '../data/workout_buffer_repository.dart';
import '../data/workout_sync_repository.dart';

final workoutBufferDatabaseProvider = Provider<WorkoutBufferDatabase>((ref) {
  final database = WorkoutBufferDatabase.open();
  ref.onDispose(database.close);
  return database;
});

final workoutSyncRepositoryProvider = Provider<WorkoutSyncRepository>((ref) {
  return SupabaseWorkoutSyncRepository();
});

final workoutBufferRepositoryProvider = Provider<WorkoutBufferRepository>((
  ref,
) {
  return WorkoutBufferRepository(
    ref.watch(workoutBufferDatabaseProvider),
    ref.watch(workoutSyncRepositoryProvider),
  );
});
