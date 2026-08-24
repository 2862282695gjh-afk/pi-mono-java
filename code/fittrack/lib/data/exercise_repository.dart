import 'package:supabase_flutter/supabase_flutter.dart';

import '../models/exercise.dart';

abstract class ExerciseRepository {
  Future<List<Exercise>> listAvailableExercises();
  Future<Exercise> createCustomExercise({
    required String name,
    required String category,
    required String muscleGroup,
  });
}

class SupabaseExerciseRepository implements ExerciseRepository {
  SupabaseExerciseRepository({SupabaseClient? client})
    : _client = client ?? Supabase.instance.client;

  final SupabaseClient _client;

  @override
  Future<List<Exercise>> listAvailableExercises() async {
    final rows = await _client
        .from('exercises')
        .select()
        .order('is_custom')
        .order('name');

    return List<Map<String, dynamic>>.from(
      rows.map((row) => Map<String, dynamic>.from(row)),
    ).map(Exercise.fromJson).toList(growable: false);
  }

  @override
  Future<Exercise> createCustomExercise({
    required String name,
    required String category,
    required String muscleGroup,
  }) async {
    final userId = _client.auth.currentUser?.id;
    if (userId == null) {
      throw StateError('请先登录后再创建自定义动作');
    }

    final row = await _client
        .from('exercises')
        .insert({
          'name': name.trim(),
          'category': category,
          'muscle_group': muscleGroup.trim(),
          'is_custom': true,
          'owner_id': userId,
        })
        .select()
        .single();
    return Exercise.fromJson(Map<String, dynamic>.from(row));
  }
}
