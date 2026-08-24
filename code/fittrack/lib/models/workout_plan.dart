import 'exercise.dart';

class WorkoutPlan {
  const WorkoutPlan({
    required this.id,
    required this.name,
    required this.description,
    required this.entries,
  });

  final String id;
  final String name;
  final String description;
  final List<PlanExercise> entries;

  factory WorkoutPlan.fromJson(Map<String, dynamic> json) {
    final rawEntries =
        List<Map<String, dynamic>>.from(
          (json['plan_exercises'] as List<dynamic>? ?? const []).map(
            (item) => Map<String, dynamic>.from(item as Map),
          ),
        )..sort(
          (left, right) =>
              (left['sort_order'] as num).compareTo(right['sort_order'] as num),
        );

    return WorkoutPlan(
      id: json['id'] as String,
      name: json['name'] as String,
      description: json['description'] as String? ?? '',
      entries: rawEntries.map(PlanExercise.fromJson).toList(growable: false),
    );
  }
}

class PlanExercise {
  const PlanExercise({
    required this.id,
    required this.exercise,
    required this.sortOrder,
    required this.defaultSets,
    required this.defaultReps,
    required this.defaultWeight,
  });

  final String id;
  final Exercise exercise;
  final int sortOrder;
  final int defaultSets;
  final int defaultReps;
  final double defaultWeight;

  factory PlanExercise.fromJson(Map<String, dynamic> json) {
    return PlanExercise(
      id: json['id'] as String,
      exercise: Exercise.fromJson(
        Map<String, dynamic>.from(json['exercises'] as Map),
      ),
      sortOrder: (json['sort_order'] as num).toInt(),
      defaultSets: (json['default_sets'] as num).toInt(),
      defaultReps: (json['default_reps'] as num).toInt(),
      defaultWeight: (json['default_weight'] as num).toDouble(),
    );
  }
}

class PlanExerciseDraft {
  const PlanExerciseDraft({
    required this.exercise,
    this.defaultSets = 3,
    this.defaultReps = 10,
    this.defaultWeight = 0,
  });

  final Exercise exercise;
  final int defaultSets;
  final int defaultReps;
  final double defaultWeight;

  PlanExerciseDraft copyWith({
    int? defaultSets,
    int? defaultReps,
    double? defaultWeight,
  }) {
    return PlanExerciseDraft(
      exercise: exercise,
      defaultSets: defaultSets ?? this.defaultSets,
      defaultReps: defaultReps ?? this.defaultReps,
      defaultWeight: defaultWeight ?? this.defaultWeight,
    );
  }

  Map<String, Object> toJson() {
    return {
      'exercise_id': exercise.id,
      'default_sets': defaultSets,
      'default_reps': defaultReps,
      'default_weight': defaultWeight,
    };
  }
}

class PlanSaveDraft {
  const PlanSaveDraft({
    required this.name,
    required this.description,
    required this.entries,
  });
  final String name;
  final String description;
  final List<PlanExerciseDraft> entries;
  Map<String, Object> toJson() => {
    'name': name,
    'description': description,
    'exercises': entries.map((entry) => entry.toJson()).toList(),
  };
}
