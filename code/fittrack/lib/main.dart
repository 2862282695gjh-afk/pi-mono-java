import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'config/supabase_config.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SupabaseConfig.init();
  runApp(const ProviderScope(child: FitTrackApp()));
}

class FitTrackApp extends StatelessWidget {
  const FitTrackApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FitTrack',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF58CC02), useMaterial3: true),
      home: const _ConnectivityPage(),
    );
  }
}

/// 里程碑 0 验收页：显示 Supabase 连通状态
class _ConnectivityPage extends StatelessWidget {
  const _ConnectivityPage();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('FitTrack')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('Supabase 连通性检查', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 8),
            Text(SupabaseConfig.hasEnv ? '✅ 已配置 URL/Key' : '❌ 未配置 supabase_env.dart'),
            Text(SupabaseConfig.isInitialized ? '✅ SDK 初始化成功' : '❌ SDK 未初始化'),
          ],
        ),
      ),
    );
  }
}
