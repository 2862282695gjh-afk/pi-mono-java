import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/auth_repository.dart';
import '../../providers/auth_providers.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  Future<void> _signOut(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(authRepositoryProvider).signOut();
    } on AuthException catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('登出失败：${error.message}')));
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final userId = ref.watch(authStateProvider).valueOrNull;

    return Scaffold(
      appBar: AppBar(
        title: const Text('FitTrack'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout_rounded),
            tooltip: '登出',
            onPressed: () => _signOut(context, ref),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
              decoration: BoxDecoration(
                color: const Color(0xFF11210C),
                borderRadius: BorderRadius.circular(99),
              ),
              child: const Text(
                '训练记录，从现在开始',
                style: TextStyle(
                  color: Color(0xFFB7F34B),
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            const SizedBox(height: 24),
            Text(
              '欢迎回来',
              style: Theme.of(context).textTheme.displaySmall
                  ?.copyWith(fontWeight: FontWeight.w900, letterSpacing: -1.2),
            ),
            const SizedBox(height: 12),
            Text(
              '你的下一次训练，会从这里开始。',
              style: Theme.of(context).textTheme.titleMedium
                  ?.copyWith(color: const Color(0xFF667064)),
            ),
            const Spacer(),
            Text(
              '已登录账户',
              style: Theme.of(context).textTheme.labelLarge
                  ?.copyWith(color: const Color(0xFF667064)),
            ),
            const SizedBox(height: 6),
            SelectableText(
              userId ?? '正在同步登录状态…',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}
