import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path_provider/path_provider.dart';

import '../../models/workout_draft.dart';

part 'workout_buffer_database.g.dart';

class WorkoutDraftRows extends Table {
  TextColumn get localId => text()();
  TextColumn get ownerId => text()();
  TextColumn get planId => text().nullable()();
  TextColumn get name => text()();
  DateTimeColumn get startedAt => dateTime()();
  DateTimeColumn get endedAt => dateTime().nullable()();
  TextColumn get payloadJson => text()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column<Object>> get primaryKey => {localId};

  @override
  String get tableName => 'workout_drafts';
}

@DriftDatabase(tables: [WorkoutDraftRows])
class WorkoutBufferDatabase extends _$WorkoutBufferDatabase {
  WorkoutBufferDatabase(super.executor);

  factory WorkoutBufferDatabase.open() =>
      WorkoutBufferDatabase(_openConnection());

  @override
  int get schemaVersion => 1;

  Future<void> saveDraft(WorkoutDraft draft) {
    return into(workoutDraftRows).insertOnConflictUpdate(
      WorkoutDraftRowsCompanion.insert(
        localId: draft.localId,
        ownerId: draft.ownerId,
        name: draft.name,
        startedAt: draft.startedAt.toUtc(),
        payloadJson: draft.toPayloadJson(),
        updatedAt: DateTime.now().toUtc(),
        planId: Value(draft.planId),
        endedAt: Value(draft.endedAt?.toUtc()),
      ),
    );
  }

  Future<List<WorkoutDraft>> readDrafts(String ownerId) async {
    final rows =
        await (select(workoutDraftRows)
              ..where((row) => row.ownerId.equals(ownerId))
              ..orderBy([(row) => OrderingTerm.desc(row.updatedAt)]))
            .get();
    return rows.map(_toDraft).toList(growable: false);
  }

  Future<void> deleteDraft(String localId) {
    return (delete(
      workoutDraftRows,
    )..where((row) => row.localId.equals(localId))).go();
  }

  WorkoutDraft _toDraft(WorkoutDraftRow row) {
    return WorkoutDraft.fromPayloadJson(
      localId: row.localId,
      ownerId: row.ownerId,
      planId: row.planId,
      name: row.name,
      startedAt: row.startedAt.toLocal(),
      endedAt: row.endedAt?.toLocal(),
      payloadJson: row.payloadJson,
    );
  }
}

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final directory = await getApplicationDocumentsDirectory();
    final file = File('${directory.path}/fittrack_workout_buffer.sqlite');
    return NativeDatabase.createInBackground(file);
  });
}
