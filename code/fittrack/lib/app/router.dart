import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/auth/login_screen.dart';
import '../features/auth/signup_screen.dart';
import '../features/home/home_screen.dart';
import '../features/plans/plan_editor_screen.dart';
import '../features/workouts/workout_session_screen.dart';
import '../providers/auth_providers.dart';

class RouterRefreshStream extends ChangeNotifier {
  RouterRefreshStream(ProviderContainer container) {
    _subscription = container.listen<AsyncValue<String?>>(
      authStateProvider,
      (_, _) => notifyListeners(),
    );
  }

  late final ProviderSubscription<AsyncValue<String?>> _subscription;

  @override
  void dispose() {
    _subscription.close();
    super.dispose();
  }
}

GoRouter buildRouter({
  required RouterRefreshStream refresh,
  required ProviderContainer container,
}) {
  return GoRouter(
    initialLocation: '/login',
    refreshListenable: refresh,
    redirect: (context, state) {
      final userId = container.read(authStateProvider).valueOrNull;
      final isLoggedIn = userId != null;
      final isAuthRoute =
          state.matchedLocation == '/login' ||
          state.matchedLocation == '/signup';

      if (!isLoggedIn && !isAuthRoute) {
        return '/login';
      }
      if (isLoggedIn && isAuthRoute) {
        return '/';
      }
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(
        path: '/signup',
        builder: (context, state) => const SignupScreen(),
      ),
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
      GoRoute(
        path: '/plans/new',
        builder: (context, state) => const PlanEditorScreen(),
      ),
      GoRoute(
        path: '/plans/:planId/edit',
        builder: (context, state) =>
            PlanEditorScreen(planId: state.pathParameters['planId']),
      ),
      GoRoute(
        path: '/workouts/:planId',
        builder: (context, state) =>
            WorkoutSessionScreen(planId: state.pathParameters['planId']!),
      ),
    ],
  );
}
