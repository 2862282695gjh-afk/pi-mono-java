# FitTrack

商用训练记录 app（对标 Strong/Hevy）。Flutter + Supabase，Android 先行，全功能免费。

- 设计 spec：`docs/superpowers/specs/2026-08-14-fittrack-commercial-refactor-design.md`
- 架构分层：UI → Riverpod → Repository → supabase_flutter（UI 不得直接 import supabase_flutter）
- 常用命令：`flutter pub get` / `flutter analyze` / `flutter test`
