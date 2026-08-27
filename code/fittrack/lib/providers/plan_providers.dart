import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/exercise_repository.dart';
import '../data/workout_plan_repository.dart';
import '../models/exercise.dart';
import '../models/workout_plan.dart';

final exerciseRepositoryProvider = Provider<ExerciseRepository>((ref) {
  return SupabaseExerciseRepository();
});

final workoutPlanRepositoryProvider = Provider<WorkoutPlanRepository>((ref) {
  return SupabaseWorkoutPlanRepository();
});

final exercisesProvider = FutureProvider<List<Exercise>>((ref) {
  return ref.watch(exerciseRepositoryProvider).listAvailableExercises();
});

final workoutPlansProvider = FutureProvider<List<WorkoutPlan>>((ref) {
  return ref.watch(workoutPlanRepositoryProvider).listPlans();
});
