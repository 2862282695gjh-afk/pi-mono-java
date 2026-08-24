import 'dart:convert';

class WorkoutDraft {
  const WorkoutDraft({
    required this.localId,
    required this.ownerId,
    required this.name,
    required this.startedAt,
    required this.exercises,
    this.planId,
    this.endedAt,
  });

  final String localId;
  final String ownerId;
  final String? planId;
  final String name;
  final DateTime startedAt;
  final DateTime? endedAt;
  final List<WorkoutExerciseDraft> exercises;

  bool get isFinished => endedAt != null;

  WorkoutDraft copyWith({
    String? name,
    DateTime? endedAt,
    List<WorkoutExerciseDraft>? exercises,
  }) {
    return WorkoutDraft(
      localId: localId,
      ownerId: ownerId,
      planId: planId,
      name: name ?? this.name,
      startedAt: startedAt,
      endedAt: endedAt ?? this.endedAt,
      exercises: exercises ?? this.exercises,
    );
  }

  String toPayloadJson() => jsonEncode({
    'exercises': exercises.map((exercise) => exercise.toJson()).toList(),
  });

  factory WorkoutDraft.fromPayloadJson({
    required String localId,
    required String ownerId,
    required String? planId,
    required String name,
    required DateTime startedAt,
    required DateTime? endedAt,
    required String payloadJson,
  }) {
    final payload = Map<String, dynamic>.from(jsonDecode(payloadJson) as Map);
    return WorkoutDraft(
      localId: localId,
      ownerId: ownerId,
      planId: planId,
      name: name,
      startedAt: startedAt,
      endedAt: endedAt,
      exercises: List<Map<String, dynamic>>.from(
        (payload['exercises'] as List<dynamic>? ?? const []).map(
          (item) => Map<String, dynamic>.from(item as Map),
        ),
      ).map(WorkoutExerciseDraft.fromJson).toList(growable: false),
    );
  }
}

class WorkoutExerciseDraft {
  const WorkoutExerciseDraft({
    required this.exerciseId,
    required this.exerciseName,
    required this.sortOrder,
    required this.sets,
  });

  final String exerciseId;
  final String exerciseName;
  final int sortOrder;
  final List<WorkoutSetDraft> sets;

  WorkoutExerciseDraft copyWith({List<WorkoutSetDraft>? sets}) {
    return WorkoutExerciseDraft(
      exerciseId: exerciseId,
      exerciseName: exerciseName,
      sortOrder: sortOrder,
      sets: sets ?? this.sets,
    );
  }

  Map<String, Object> toJson() => {
    'exercise_id': exerciseId,
    'exercise_name': exerciseName,
    'sort_order': sortOrder,
    'sets': sets.map((set) => set.toJson()).toList(),
  };

  factory WorkoutExerciseDraft.fromJson(Map<String, dynamic> json) {
    return WorkoutExerciseDraft(
      exerciseId: json['exercise_id'] as String,
      exerciseName: json['exercise_name'] as String,
      sortOrder: (json['sort_order'] as num).toInt(),
      sets: List<Map<String, dynamic>>.from(
        (json['sets'] as List<dynamic>? ?? const []).map(
          (item) => Map<String, dynamic>.from(item as Map),
        ),
      ).map(WorkoutSetDraft.fromJson).toList(growable: false),
    );
  }
}

class WorkoutSetDraft {
  const WorkoutSetDraft({
    required this.setIndex,
    required this.weight,
    required this.reps,
    this.completedAt,
  });

  final int setIndex;
  final double weight;
  final int reps;
  final DateTime? completedAt;

  bool get isCompleted => completedAt != null;

  WorkoutSetDraft copyWith({double? weight, int? reps, DateTime? completedAt}) {
    return WorkoutSetDraft(
      setIndex: setIndex,
      weight: weight ?? this.weight,
      reps: reps ?? this.reps,
      completedAt: completedAt ?? this.completedAt,
    );
  }

  Map<String, Object?> toJson() => {
    'set_index': setIndex,
    'weight': weight,
    'reps': reps,
    'completed_at': completedAt?.toUtc().toIso8601String(),
  };

  factory WorkoutSetDraft.fromJson(Map<String, dynamic> json) {
    final completedAt = json['completed_at'] as String?;
    return WorkoutSetDraft(
      setIndex: (json['set_index'] as num).toInt(),
      weight: (json['weight'] as num).toDouble(),
      reps: (json['reps'] as num).toInt(),
      completedAt: completedAt == null
          ? null
          : DateTime.parse(completedAt).toLocal(),
    );
  }
}
