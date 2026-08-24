import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'app/fittrack_theme.dart';
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
    return MaterialApp.router(
      title: 'FitTrack',
      theme: FitTrackTheme.build(),
      routerConfig: routerConfig,
    );
  }
}
