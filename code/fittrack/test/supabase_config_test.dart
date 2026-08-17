import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/config/supabase_config.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('init 后 hasEnv 为 true 且初始化成功', () async {
    await SupabaseConfig.init();
    expect(SupabaseConfig.hasEnv, isTrue);
    expect(SupabaseConfig.isInitialized, isTrue);
  });
}
