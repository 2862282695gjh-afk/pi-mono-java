import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/auth_repository.dart';
import '../../providers/auth_providers.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isSubmitting = false;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _signInWithEmail() async {
    if (_emailController.text.trim().isEmpty ||
        _passwordController.text.isEmpty) {
      _showMessage('请填写邮箱和密码');
      return;
    }

    await _runRequest(
      () => ref
          .read(authRepositoryProvider)
          .signInWithEmail(
            _emailController.text.trim(),
            _passwordController.text,
          ),
      failurePrefix: '登录失败',
    );
  }

  Future<void> _signInWithGoogle() async {
    await _runRequest(
      () => ref.read(authRepositoryProvider).signInWithGoogle(),
      failurePrefix: 'Google 登录失败',
    );
  }

  Future<void> _runRequest(
    Future<void> Function() request, {
    required String failurePrefix,
  }) async {
    setState(() => _isSubmitting = true);
    try {
      await request();
    } on AuthException catch (error) {
      _showMessage('$failurePrefix：${error.message}');
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
                    const _AuthHeader(),
                    const SizedBox(height: 40),
                    Text(
                      '继续你的训练记录',
                      style: Theme.of(context).textTheme.headlineMedium
                          ?.copyWith(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '每一次完成，都让下一次更有底气。',
                      style: Theme.of(context).textTheme.bodyLarge
                          ?.copyWith(color: const Color(0xFF667064)),
                    ),
                    const SizedBox(height: 28),
                    _AuthField(
                      controller: _emailController,
                      label: '邮箱',
                      hint: 'name@example.com',
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [AutofillHints.email],
                    ),
                    const SizedBox(height: 16),
                    _AuthField(
                      controller: _passwordController,
                      label: '密码',
                      hint: '输入你的密码',
                      obscureText: true,
                      autofillHints: const [AutofillHints.password],
                      onSubmitted: (_) =>
                          _isSubmitting ? null : _signInWithEmail(),
                    ),
                    const SizedBox(height: 24),
                    SizedBox(
                      height: 52,
                      child: FilledButton(
                        onPressed: _isSubmitting ? null : _signInWithEmail,
                        child: _isSubmitting
                            ? const SizedBox(
                                height: 22,
                                width: 22,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Text('登录并开始训练'),
                      ),
                    ),
                    const SizedBox(height: 14),
                    OutlinedButton.icon(
                      onPressed: _isSubmitting ? null : _signInWithGoogle,
                      icon: const Text(
                        'G',
                        style: TextStyle(fontWeight: FontWeight.w800),
                      ),
                      label: const Text('使用 Google 登录'),
                    ),
                    const SizedBox(height: 20),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Text('第一次使用？'),
                        TextButton(
                          onPressed: _isSubmitting
                              ? null
                              : () => context.go('/signup'),
                          child: const Text('创建账号'),
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

class _AuthHeader extends StatelessWidget {
  const _AuthHeader();

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          height: 44,
          width: 44,
          alignment: Alignment.center,
          decoration: const BoxDecoration(
            color: Color(0xFFB7F34B),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.bolt_rounded, color: Color(0xFF11210C)),
        ),
        const SizedBox(width: 12),
        Text(
          'FitTrack',
          style: Theme.of(context).textTheme.titleLarge
              ?.copyWith(fontWeight: FontWeight.w900, letterSpacing: -0.8),
        ),
      ],
    );
  }
}

class _AuthField extends StatelessWidget {
  const _AuthField({
    required this.controller,
    required this.label,
    required this.hint,
    required this.autofillHints,
    this.keyboardType,
    this.obscureText = false,
    this.onSubmitted,
  });

  final TextEditingController controller;
  final String label;
  final String hint;
  final Iterable<String> autofillHints;
  final TextInputType? keyboardType;
  final bool obscureText;
  final ValueChanged<String>? onSubmitted;

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      autofillHints: autofillHints,
      keyboardType: keyboardType,
      obscureText: obscureText,
      onSubmitted: onSubmitted,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        filled: true,
        fillColor: Colors.white,
        border: const OutlineInputBorder(),
      ),
    );
  }
}
