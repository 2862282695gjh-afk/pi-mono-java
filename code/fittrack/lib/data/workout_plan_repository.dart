import 'package:supabase_flutter/supabase_flutter.dart';

import '../models/workout_plan.dart';

abstract class WorkoutPlanRepository {
  Future<List<WorkoutPlan>> listPlans();
  Future<String> savePlan({
    String? planId,
    required String name,
    required String description,
    required List<PlanExerciseDraft> entries,
  });
  Future<void> deletePlan(String planId);
  Future<List<String>> savePlanBundle(List<PlanSaveDraft> plans);
}

class SupabaseWorkoutPlanRepository implements WorkoutPlanRepository {
  SupabaseWorkoutPlanRepository({SupabaseClient? client})
    : _client = client ?? Supabase.instance.client;

  final SupabaseClient _client;

  @override
  Future<List<WorkoutPlan>> listPlans() async {
    final rows = await _client
        .from('workout_plans')
        .select(
          'id, name, description, '
          'plan_exercises('
          'id, sort_order, default_sets, default_reps, default_weight, '
          'exercises(id, name, category, muscle_group, is_custom)'
          ')',
        )
        .order('updated_at', ascending: false);

    return List<Map<String, dynamic>>.from(
      rows.map((row) => Map<String, dynamic>.from(row)),
    ).map(WorkoutPlan.fromJson).toList(growable: false);
  }

  @override
  Future<String> savePlan({
    String? planId,
    required String name,
    required String description,
    required List<PlanExerciseDraft> entries,
  }) async {
    final id = await _client.rpc(
      'save_workout_plan',
      params: {
        'p_plan_id': planId,
        'p_name': name,
        'p_description': description,
        'p_exercises': entries.map((entry) => entry.toJson()).toList(),
      },
    );
    return id as String;
  }

  @override
  Future<void> deletePlan(String planId) {
    return _client.from('workout_plans').delete().eq('id', planId);
  }

  @override
  Future<List<String>> savePlanBundle(List<PlanSaveDraft> plans) async {
    final ids = await _client.rpc(
      'save_generated_plan_bundle',
      params: {'p_plans': plans.map((plan) => plan.toJson()).toList()},
    );
    return List<String>.from(ids as List);
  }
}
