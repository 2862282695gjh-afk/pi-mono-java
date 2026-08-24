class Exercise {
  const Exercise({
    required this.id,
    required this.name,
    required this.category,
    required this.muscleGroup,
    required this.isCustom,
  });

  final String id;
  final String name;
  final String category;
  final String muscleGroup;
  final bool isCustom;

  factory Exercise.fromJson(Map<String, dynamic> json) {
    return Exercise(
      id: json['id'] as String,
      name: json['name'] as String,
      category: json['category'] as String,
      muscleGroup: json['muscle_group'] as String,
      isCustom: json['is_custom'] as bool,
    );
  }
}
