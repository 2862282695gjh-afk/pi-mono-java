import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/local/workout_buffer_database.dart';
import '../data/workout_buffer_repository.dart';
import '../data/workout_sync_repository.dart';
import '../models/workout_draft.dart';
import 'auth_providers.dart';

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

final finishedWorkoutDraftsProvider = FutureProvider<List<WorkoutDraft>>((
  ref,
) async {
  final ownerId = ref.watch(authRepositoryProvider).currentUserId();
  if (ownerId == null) {
    return const [];
  }
  final drafts = await ref
      .watch(workoutBufferRepositoryProvider)
      .pendingDrafts(ownerId);
  return drafts.where((draft) => draft.isFinished).toList(growable: false);
});
