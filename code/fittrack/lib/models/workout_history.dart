class WorkoutSessionSummary {
  const WorkoutSessionSummary({
    required this.id,
    required this.name,
    required this.startedAt,
    required this.endedAt,
    required this.exercises,
  });

  final String id;
  final String name;
  final DateTime startedAt;
  final DateTime endedAt;
  final List<WorkoutSessionExercise> exercises;

  int get durationMinutes => endedAt.difference(startedAt).inMinutes;
  int get completedSets =>
      exercises.fold(0, (total, exercise) => total + exercise.sets.length);
  double get volume =>
      exercises.fold(0, (total, exercise) => total + exercise.volume);
  double get estimatedOneRepMax => exercises.fold(
    0,
    (best, exercise) =>
        best > exercise.estimatedOneRepMax ? best : exercise.estimatedOneRepMax,
  );

  factory WorkoutSessionSummary.fromJson(Map<String, dynamic> json) {
    return WorkoutSessionSummary(
      id: json['id'] as String,
      name: json['name'] as String,
      startedAt: DateTime.parse(json['started_at'] as String).toLocal(),
      endedAt: DateTime.parse(json['ended_at'] as String).toLocal(),
      exercises: List<Map<String, dynamic>>.from(
        (json['session_exercises'] as List<dynamic>? ?? const []).map(
          (item) => Map<String, dynamic>.from(item as Map),
        ),
      ).map(WorkoutSessionExercise.fromJson).toList(growable: false),
    );
  }
}

class WorkoutSessionExercise {
  const WorkoutSessionExercise({
    required this.id,
    required this.name,
    required this.sortOrder,
    required this.sets,
  });

  final String id;
  final String name;
  final int sortOrder;
  final List<WorkoutHistorySet> sets;

  double get volume =>
      sets.fold(0, (total, set) => total + set.weight * set.reps);
  double get estimatedOneRepMax => sets.fold(
    0,
    (best, set) =>
        best > set.estimatedOneRepMax ? best : set.estimatedOneRepMax,
  );

  factory WorkoutSessionExercise.fromJson(Map<String, dynamic> json) {
    final exercise = Map<String, dynamic>.from(json['exercises'] as Map);
    final sets =
        List<Map<String, dynamic>>.from(
          (json['session_sets'] as List<dynamic>? ?? const []).map(
            (item) => Map<String, dynamic>.from(item as Map),
          ),
        )..sort(
          (left, right) =>
              (left['set_index'] as num).compareTo(right['set_index'] as num),
        );
    return WorkoutSessionExercise(
      id: json['id'] as String,
      name: exercise['name'] as String,
      sortOrder: (json['sort_order'] as num).toInt(),
      sets: sets.map(WorkoutHistorySet.fromJson).toList(growable: false),
    );
  }
}

class WorkoutHistorySet {
  const WorkoutHistorySet({
    required this.index,
    required this.weight,
    required this.reps,
    required this.completedAt,
  });

  final int index;
  final double weight;
  final int reps;
  final DateTime completedAt;

  double get estimatedOneRepMax => weight * (1 + reps / 30);

  factory WorkoutHistorySet.fromJson(Map<String, dynamic> json) {
    return WorkoutHistorySet(
      index: (json['set_index'] as num).toInt(),
      weight: (json['weight'] as num).toDouble(),
      reps: (json['reps'] as num).toInt(),
      completedAt: DateTime.parse(json['completed_at'] as String).toLocal(),
    );
  }
}

class BodyweightLog {
  const BodyweightLog({required this.loggedOn, required this.weight});

  final DateTime loggedOn;
  final double weight;

  factory BodyweightLog.fromJson(Map<String, dynamic> json) {
    return BodyweightLog(
      loggedOn: DateTime.parse(json['logged_on'] as String),
      weight: (json['weight'] as num).toDouble(),
    );
  }
}
