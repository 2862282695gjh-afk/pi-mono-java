import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fittrack/app/router.dart';
import 'package:fittrack/providers/auth_providers.dart';

void main() {
  test('未登录时 authStateProvider 提供加载态或空用户 ID', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    final subscription = container.listen(authStateProvider, (_, _) {});

    expect(
      subscription.read().hasValue || subscription.read().isLoading,
      isTrue,
    );
  });

  testWidgets('未登录用户会停留在登录页，并可进入注册页', (tester) async {
    final container = ProviderContainer();
    final refresh = RouterRefreshStream(container);
    final router = buildRouter(refresh: refresh, container: container);
    addTearDown(() {
      router.dispose();
      refresh.dispose();
      container.dispose();
    });

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('继续你的训练记录'), findsOneWidget);

    router.go('/');
    await tester.pumpAndSettle();
    expect(find.text('继续你的训练记录'), findsOneWidget);

    await tester.tap(find.text('创建账号'));
    await tester.pumpAndSettle();
    expect(find.text('把今天的训练，变成长期的改变。'), findsOneWidget);
  });
}
