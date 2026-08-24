import 'package:supabase_flutter/supabase_flutter.dart';

import '../models/workout_history.dart';

abstract class WorkoutHistoryRepository {
  Future<List<WorkoutSessionSummary>> listSessions();
  Future<WorkoutSessionSummary> getSession(String sessionId);
  Future<List<BodyweightLog>> listBodyweightLogs();
  Future<void> saveBodyweight({
    required DateTime loggedOn,
    required double weight,
  });
}

class SupabaseWorkoutHistoryRepository implements WorkoutHistoryRepository {
  SupabaseWorkoutHistoryRepository({SupabaseClient? client})
    : _client = client ?? Supabase.instance.client;

  final SupabaseClient _client;

  static const _sessionSelect =
      'id, name, started_at, ended_at, '
      'session_exercises('
      'id, sort_order, exercises(name), '
      'session_sets(set_index, weight, reps, completed_at)'
      ')';

  @override
  Future<List<WorkoutSessionSummary>> listSessions() async {
    final rows = await _client
        .from('workout_sessions')
        .select(_sessionSelect)
        .order('started_at', ascending: false);
    return List<Map<String, dynamic>>.from(
      rows.map((row) => Map<String, dynamic>.from(row)),
    ).map(WorkoutSessionSummary.fromJson).toList(growable: false);
  }

  @override
  Future<WorkoutSessionSummary> getSession(String sessionId) async {
    final row = await _client
        .from('workout_sessions')
        .select(_sessionSelect)
        .eq('id', sessionId)
        .single();
    return WorkoutSessionSummary.fromJson(Map<String, dynamic>.from(row));
  }

  @override
  Future<List<BodyweightLog>> listBodyweightLogs() async {
    final rows = await _client
        .from('bodyweight_logs')
        .select('logged_on, weight')
        .order('logged_on', ascending: false);
    return List<Map<String, dynamic>>.from(
      rows.map((row) => Map<String, dynamic>.from(row)),
    ).map(BodyweightLog.fromJson).toList(growable: false);
  }

  @override
  Future<void> saveBodyweight({
    required DateTime loggedOn,
    required double weight,
  }) async {
    final ownerId = _client.auth.currentUser?.id;
    if (ownerId == null) {
      throw StateError('请先登录后再记录体重');
    }
    final date = DateTime(loggedOn.year, loggedOn.month, loggedOn.day);
    await _client.from('bodyweight_logs').upsert({
      'owner_id': ownerId,
      'logged_on': date.toIso8601String().substring(0, 10),
      'weight': weight,
    }, onConflict: 'owner_id,logged_on');
  }
}
