import 'package:supabase_flutter/supabase_flutter.dart';

// UI 层捕获认证错误用这个类型（经本文件 re-export，UI 不必 import supabase_flutter）
export 'package:supabase_flutter/supabase_flutter.dart' show AuthException;

/// 认证数据访问。UI 层只依赖此抽象，不直接 import supabase_flutter。
abstract class AuthRepository {
  Future<void> signUpWithEmail(String email, String password);
  Future<void> signInWithEmail(String email, String password);
  Future<void> signInWithGoogle();
  Future<void> signOut();
  Stream<String?> authStateChanges();
  String? currentUserId();
}

class SupabaseAuthRepository implements AuthRepository {
  SupabaseAuthRepository();

  GoTrueClient get _auth {
    try {
      return Supabase.instance.client.auth;
    } catch (_) {
      // 测试环境未初始化 Supabase 时，走空实现分支
      throw StateError('Supabase 未初始化');
    }
  }

  @override
  Future<void> signUpWithEmail(String email, String password) =>
      _auth.signUp(email: email, password: password);

  @override
  Future<void> signInWithEmail(String email, String password) =>
      _auth.signInWithPassword(email: email, password: password);

  @override
  Future<void> signInWithGoogle() async {
    await _auth.signInWithOAuth(
      OAuthProvider.google,
      redirectTo: 'io.supabase.fittrack://login-callback',
    );
  }

  @override
  Future<void> signOut() => _auth.signOut();

  @override
  Stream<String?> authStateChanges() {
    try {
      return Supabase.instance.client.auth.onAuthStateChange
          .map((event) => event.session?.user.id);
    } catch (_) {
      return const Stream.empty();
    }
  }

  @override
  String? currentUserId() {
    try {
      return Supabase.instance.client.auth.currentUser?.id;
    } catch (_) {
      return null;
    }
  }
}
