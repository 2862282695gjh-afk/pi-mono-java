import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/auth_repository.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return SupabaseAuthRepository();
});

final authStateProvider = StreamProvider<String?>((ref) {
  return ref.watch(authRepositoryProvider).authStateChanges();
});
