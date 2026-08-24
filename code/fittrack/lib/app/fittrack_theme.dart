import 'package:flutter/material.dart';

/// Visual language for FitTrack: a focused training log, not a generic form app.
abstract final class FitTrackTheme {
  static const ink = Color(0xFF102018);
  static const forest = Color(0xFF183323);
  static const lime = Color(0xFFC6FF57);
  static const paper = Color(0xFFF4F3ED);
  static const mist = Color(0xFFE7E9E1);
  static const line = Color(0xFFD6D9D0);
  static const muted = Color(0xFF667064);
  static const signal = Color(0xFFFF7448);

  static ThemeData build() {
    final colors = ColorScheme.fromSeed(
      seedColor: lime,
      brightness: Brightness.light,
      primary: forest,
      onPrimary: const Color(0xFFF7FFE9),
      surface: paper,
      onSurface: ink,
      error: const Color(0xFFB42318),
    );

    return ThemeData(
      colorScheme: colors,
      scaffoldBackgroundColor: paper,
      useMaterial3: true,
      fontFamilyFallback: const [
        'PingFang SC',
        'Hiragino Sans GB',
        'Avenir Next',
      ],
      appBarTheme: const AppBarTheme(
        backgroundColor: paper,
        foregroundColor: ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        titleTextStyle: TextStyle(
          color: ink,
          fontSize: 19,
          fontWeight: FontWeight.w900,
          letterSpacing: -0.5,
        ),
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(
          color: ink,
          fontWeight: FontWeight.w900,
          letterSpacing: -1.6,
          height: .98,
        ),
        headlineMedium: TextStyle(
          color: ink,
          fontWeight: FontWeight.w900,
          letterSpacing: -1.2,
          height: 1.02,
        ),
        headlineSmall: TextStyle(
          color: ink,
          fontWeight: FontWeight.w900,
          letterSpacing: -.8,
          height: 1.08,
        ),
        titleLarge: TextStyle(
          color: ink,
          fontWeight: FontWeight.w900,
          letterSpacing: -.55,
        ),
        bodyLarge: TextStyle(color: ink, height: 1.45),
        bodyMedium: TextStyle(color: ink, height: 1.42),
      ),
      inputDecorationTheme: InputDecorationTheme(
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 17,
          vertical: 17,
        ),
        filled: true,
        fillColor: Colors.white,
        labelStyle: const TextStyle(color: muted, fontWeight: FontWeight.w700),
        hintStyle: const TextStyle(color: Color(0xFF9BA39A)),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: line),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: forest, width: 2),
        ),
      ),
      cardTheme: CardThemeData(
        color: Colors.white,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      ),
      dividerTheme: const DividerThemeData(color: line, space: 1),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: paper,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: ink,
        contentTextStyle: const TextStyle(color: Color(0xFFF7FFE9)),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(54),
          backgroundColor: ink,
          foregroundColor: const Color(0xFFF7FFE9),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w900),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          foregroundColor: ink,
          side: const BorderSide(color: line),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w800),
        ),
      ),
      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: lime,
        foregroundColor: ink,
        shape: StadiumBorder(),
      ),
    );
  }
}
