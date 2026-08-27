import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:fittrack/config/supabase_config.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    // supabase_flutter 的 init 依赖 SharedPreferences（原生通道），
    // 单元测试环境用官方 mock 值注入
    SharedPreferences.setMockInitialValues({});
  });

  test('init 后 hasEnv 为 true 且初始化成功', () async {
    await SupabaseConfig.init();
    expect(SupabaseConfig.hasEnv, isTrue);
    expect(SupabaseConfig.isInitialized, isTrue);
  });
}
