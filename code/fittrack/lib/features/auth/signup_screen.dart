import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/auth_repository.dart';
import '../../providers/auth_providers.dart';

class SignupScreen extends ConsumerStatefulWidget {
  const SignupScreen({super.key});

  @override
  ConsumerState<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends ConsumerState<SignupScreen> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isSubmitting = false;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _signUp() async {
    if (_emailController.text.trim().isEmpty) {
      _showMessage('请填写邮箱');
      return;
    }
    if (_passwordController.text.length < 6) {
      _showMessage('密码至少需要 6 位');
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      await ref
          .read(authRepositoryProvider)
          .signUpWithEmail(
            _emailController.text.trim(),
            _passwordController.text,
          );
      if (mounted) {
        _showMessage('注册成功，现在登录开始训练吧');
        context.go('/login');
      }
    } on AuthException catch (error) {
      _showMessage('注册失败：${error.message}');
    } finally {
      if (mounted) {
        setState(() => _isSubmitting = false);
      }
    }
  }

  void _showMessage(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F6F1),
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        title: const Text('创建账号'),
      ),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: AutofillGroup(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '把今天的训练，变成长期的改变。',
                      style: Theme.of(context).textTheme.headlineMedium
                          ?.copyWith(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      '创建账号后，你的训练数据将安全同步到云端。',
                      style: Theme.of(context).textTheme.bodyLarge
                          ?.copyWith(color: const Color(0xFF667064)),
                    ),
                    const SizedBox(height: 32),
                    TextField(
                      controller: _emailController,
                      autofocus: true,
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [AutofillHints.newUsername],
                      decoration: const InputDecoration(
                        labelText: '邮箱',
                        hintText: 'name@example.com',
                        filled: true,
                        fillColor: Colors.white,
                        border: OutlineInputBorder(),
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: _passwordController,
                      obscureText: true,
                      autofillHints: const [AutofillHints.newPassword],
                      onSubmitted: (_) => _isSubmitting ? null : _signUp(),
                      decoration: const InputDecoration(
                        labelText: '设置密码',
                        hintText: '至少 6 位',
                        helperText: '使用 6 位以上密码保护你的训练记录',
                        filled: true,
                        fillColor: Colors.white,
                        border: OutlineInputBorder(),
                      ),
                    ),
                    const SizedBox(height: 24),
                    SizedBox(
                      height: 52,
                      child: FilledButton(
                        onPressed: _isSubmitting ? null : _signUp,
                        child: _isSubmitting
                            ? const SizedBox(
                                height: 22,
                                width: 22,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Text('创建账号'),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Text('已有账号？'),
                        TextButton(
                          onPressed: _isSubmitting
                              ? null
                              : () => context.go('/login'),
                          child: const Text('去登录'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
