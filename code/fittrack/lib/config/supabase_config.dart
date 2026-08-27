import 'package:supabase_flutter/supabase_flutter.dart';

import 'supabase_env.dart';

/// Supabase 初始化入口。main() 中 await 调用。
class SupabaseConfig {
  static bool _initialized = false;

  /// env 是否已加载（诊断用）
  static bool get hasEnv =>
      kSupabaseUrl.startsWith('https://') && kSupabaseAnonKey.length > 20;

  static Future<void> init() async {
    await Supabase.initialize(
      url: kSupabaseUrl,
      publishableKey: kSupabaseAnonKey,
    );
    _initialized = true;
  }

  static bool get isInitialized => _initialized;
}
