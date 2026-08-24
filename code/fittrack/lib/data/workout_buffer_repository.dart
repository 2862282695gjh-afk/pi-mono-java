import '../models/workout_draft.dart';
import 'local/workout_buffer_database.dart';
import 'workout_sync_repository.dart';

class WorkoutBufferRepository {
  WorkoutBufferRepository(this._database, this._syncRepository);

  final WorkoutBufferDatabase _database;
  final WorkoutSyncRepository _syncRepository;

  Future<void> saveDraft(WorkoutDraft draft) => _database.saveDraft(draft);

  Future<List<WorkoutDraft>> pendingDrafts(String ownerId) =>
      _database.readDrafts(ownerId);

  Future<void> syncFinishedDraft(WorkoutDraft draft) async {
    await _syncRepository.syncFinishedWorkout(draft);
    await _database.deleteDraft(draft.localId);
  }
}
