import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'app/router.dart';
import 'config/supabase_config.dart';

late final ProviderContainer globalContainer;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SupabaseConfig.init();

  globalContainer = ProviderContainer();
  final refresh = RouterRefreshStream(globalContainer);
  final router = buildRouter(refresh: refresh, container: globalContainer);

  runApp(
    UncontrolledProviderScope(
      container: globalContainer,
      child: FitTrackApp(routerConfig: router),
    ),
  );
}

class FitTrackApp extends StatelessWidget {
  const FitTrackApp({super.key, required this.routerConfig});

  final GoRouter routerConfig;

  @override
  Widget build(BuildContext context) {
    const seedColor = Color(0xFF79BE23);
    final colorScheme = ColorScheme.fromSeed(seedColor: seedColor);

    return MaterialApp.router(
      title: 'FitTrack',
      theme: ThemeData(
        colorScheme: colorScheme,
        scaffoldBackgroundColor: const Color(0xFFF5F6F1),
        useMaterial3: true,
        inputDecorationTheme: InputDecorationTheme(
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 17,
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: Color(0xFFD6D9D0)),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: Color(0xFF477A0E), width: 2),
          ),
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFF11210C),
            foregroundColor: const Color(0xFFF4FFE8),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
            ),
            textStyle: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            minimumSize: const Size.fromHeight(52),
            foregroundColor: const Color(0xFF11210C),
            side: const BorderSide(color: Color(0xFFD6D9D0)),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
            ),
          ),
        ),
      ),
      routerConfig: routerConfig,
    );
  }
}
