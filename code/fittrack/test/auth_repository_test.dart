import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/data/auth_repository.dart';

void main() {
  test('SupabaseAuthRepository 实现了 AuthRepository 接口', () {
    // 接口契约测试：确保实现类存在且方法签名正确
    final AuthRepository repo = SupabaseAuthRepository();
    expect(repo, isNotNull);
    expect(repo.currentUserId(), isNull); // 未初始化 Supabase 时安全返回 null
  });
}
