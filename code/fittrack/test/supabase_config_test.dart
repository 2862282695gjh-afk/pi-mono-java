import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/config/supabase_config.dart';

void main() {
  test('hasEnv 检查 env 配置', () {
    // 验证 env 文件已正确配置
    expect(SupabaseConfig.hasEnv, isTrue);
  });
}
