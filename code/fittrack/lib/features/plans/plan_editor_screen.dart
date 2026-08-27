import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/fittrack_theme.dart';
import '../../models/exercise.dart';
import '../../models/workout_plan.dart';
import '../../providers/plan_providers.dart';

class PlanEditorScreen extends ConsumerStatefulWidget {
  const PlanEditorScreen({super.key, this.planId});

  final String? planId;

  @override
  ConsumerState<PlanEditorScreen> createState() => _PlanEditorScreenState();
}

class _PlanEditorScreenState extends ConsumerState<PlanEditorScreen> {
  final _nameController = TextEditingController();
  final _descriptionController = TextEditingController();
  final List<PlanExerciseDraft> _entries = [];
  bool _isSaving = false;
  bool _isInitialized = false;

  @override
  void dispose() {
    _nameController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  WorkoutPlan? _existingPlan(List<WorkoutPlan> plans) {
    for (final plan in plans) {
      if (plan.id == widget.planId) {
        return plan;
      }
    }
    return null;
  }

  void _initialize(WorkoutPlan? plan) {
    if (_isInitialized || plan == null) {
      return;
    }
    _isInitialized = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _nameController.text = plan.name;
        _descriptionController.text = plan.description;
        _entries.addAll(
          plan.entries
              .map(
                (entry) => PlanExerciseDraft(
                  exercise: entry.exercise,
                  defaultSets: entry.defaultSets,
                  defaultReps: entry.defaultReps,
                  defaultWeight: entry.defaultWeight,
                ),
              )
              .toList(growable: false),
        );
      });
    });
  }

  Future<void> _save() async {
    if (_nameController.text.trim().isEmpty) {
      _showMessage('请为计划起一个名字');
      return;
    }
    if (_entries.isEmpty) {
      _showMessage('至少添加一个动作');
      return;
    }

    setState(() => _isSaving = true);
    try {
      await ref
          .read(workoutPlanRepositoryProvider)
          .savePlan(
            planId: widget.planId,
            name: _nameController.text,
            description: _descriptionController.text,
            entries: _entries,
          );
      ref.invalidate(workoutPlansProvider);
      if (mounted) {
        context.go('/');
      }
    } catch (_) {
      _showMessage('计划保存失败，请稍后重试');
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
      }
    }
  }

  Future<void> _delete() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除这个计划？'),
        content: const Text('计划中的动作顺序会被删除，但动作库不会受影响。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || widget.planId == null) {
      return;
    }
    try {
      await ref.read(workoutPlanRepositoryProvider).deletePlan(widget.planId!);
      ref.invalidate(workoutPlansProvider);
      if (mounted) {
        context.go('/');
      }
    } catch (_) {
      _showMessage('删除失败，请稍后重试');
    }
  }

  Future<void> _addExercise() async {
    try {
      final exercises = await ref.read(exercisesProvider.future);
      if (!mounted) {
        return;
      }
      final selected = await showModalBottomSheet<Exercise>(
        context: context,
        showDragHandle: true,
        isScrollControlled: true,
        builder: (context) => _ExercisePicker(exercises: exercises),
      );
      if (selected != null && mounted) {
        setState(() => _entries.add(PlanExerciseDraft(exercise: selected)));
      }
    } catch (_) {
      _showMessage('动作库暂时不可用，请检查网络');
    }
  }

  Future<void> _createCustomExercise() async {
    final result = await showDialog<_CustomExerciseInput>(
      context: context,
      builder: (context) => const _CustomExerciseDialog(),
    );
    if (result == null) {
      return;
    }
    try {
      final exercise = await ref
          .read(exerciseRepositoryProvider)
          .createCustomExercise(
            name: result.name,
            category: result.category,
            muscleGroup: result.muscleGroup,
          );
      ref.invalidate(exercisesProvider);
      if (mounted) {
        setState(() => _entries.add(PlanExerciseDraft(exercise: exercise)));
      }
    } catch (_) {
      _showMessage('自定义动作创建失败，请稍后重试');
    }
  }

  Future<void> _editDefaults(int index) async {
    final updated = await showDialog<PlanExerciseDraft>(
      context: context,
      builder: (context) => _DefaultsDialog(entry: _entries[index]),
    );
    if (updated != null && mounted) {
      setState(() => _entries[index] = updated);
    }
  }

  void _move(int index, int offset) {
    final target = index + offset;
    if (target < 0 || target >= _entries.length) {
      return;
    }
    setState(() {
      final entry = _entries.removeAt(index);
      _entries.insert(target, entry);
    });
  }

  void _showMessage(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final plans =
        ref.watch(workoutPlansProvider).valueOrNull ?? const <WorkoutPlan>[];
    final existing = _existingPlan(plans);
    _initialize(existing);
    final isEditing = widget.planId != null;

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(isEditing ? '编排计划' : '新建计划'),
            const Text(
              'TRAINING TEMPLATE',
              style: TextStyle(
                color: FitTrackTheme.muted,
                fontSize: 9,
                fontWeight: FontWeight.w900,
                letterSpacing: 1.1,
              ),
            ),
          ],
        ),
        actions: [
          if (isEditing)
            IconButton(
              onPressed: _isSaving ? null : _delete,
              tooltip: '删除计划',
              icon: const Icon(Icons.delete_outline_rounded),
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 120),
        children: [
          const Text(
            '把意图写下来',
            style: TextStyle(
              color: FitTrackTheme.signal,
              fontSize: 12,
              fontWeight: FontWeight.w900,
              letterSpacing: .8,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            isEditing ? '把计划打磨得更顺手。' : '为下一次训练设定节奏。',
            style: Theme.of(context).textTheme.headlineSmall
                ?.copyWith(fontSize: 28),
          ),
          const SizedBox(height: 24),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: FitTrackTheme.mist,
              borderRadius: BorderRadius.circular(24),
            ),
            child: Column(
              children: [
                TextField(
                  controller: _nameController,
                  textCapitalization: TextCapitalization.sentences,
                  decoration: const InputDecoration(
                    labelText: '计划名称',
                    hintText: '例如：上肢力量',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _descriptionController,
                  maxLines: 2,
                  maxLength: 500,
                  decoration: const InputDecoration(
                    labelText: '训练备注（可选）',
                    hintText: '记录这份计划的重点',
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 30),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '动作顺序',
                style: Theme.of(context).textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.w900),
              ),
              Text(
                '${_entries.length} 个',
                style: const TextStyle(color: FitTrackTheme.muted),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (_entries.isEmpty)
            const _EditorEmptyState()
          else
            ...List.generate(
              _entries.length,
              (index) => _PlanEntryCard(
                entry: _entries[index],
                index: index,
                isFirst: index == 0,
                isLast: index == _entries.length - 1,
                onEdit: () => _editDefaults(index),
                onMoveUp: () => _move(index, -1),
                onMoveDown: () => _move(index, 1),
                onRemove: () => setState(() => _entries.removeAt(index)),
              ),
            ),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: _addExercise,
            icon: const Icon(Icons.add_rounded),
            label: const Text('从动作库添加动作'),
          ),
          TextButton.icon(
            onPressed: _createCustomExercise,
            icon: const Icon(Icons.add_circle_outline_rounded),
            label: const Text('创建自定义动作'),
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(20, 8, 20, 16),
        child: SizedBox(
          height: 52,
          child: FilledButton(
            onPressed: _isSaving ? null : _save,
            child: _isSaving
                ? const SizedBox(
                    height: 22,
                    width: 22,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(isEditing ? '保存这份计划' : '保存并开始编排'),
          ),
        ),
      ),
    );
  }
}

class _EditorEmptyState extends StatelessWidget {
  const _EditorEmptyState();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: FitTrackTheme.ink,
        borderRadius: BorderRadius.circular(24),
      ),
      child: const Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.add_task_rounded, color: FitTrackTheme.lime),
          SizedBox(width: 14),
          Expanded(
            child: Text(
              '从动作库加入第一个动作。组数、次数和默认重量都可以随后调整。',
              style: TextStyle(color: Color(0xFFD5E1CE), height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}

class _PlanEntryCard extends StatelessWidget {
  const _PlanEntryCard({
    required this.entry,
    required this.index,
    required this.isFirst,
    required this.isLast,
    required this.onEdit,
    required this.onMoveUp,
    required this.onMoveDown,
    required this.onRemove,
  });

  final PlanExerciseDraft entry;
  final int index;
  final bool isFirst;
  final bool isLast;
  final VoidCallback onEdit;
  final VoidCallback onMoveUp;
  final VoidCallback onMoveDown;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        borderRadius: BorderRadius.circular(22),
        onTap: onEdit,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 15, 10, 15),
          child: Row(
            children: [
              Container(
                width: 39,
                height: 39,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: FitTrackTheme.lime,
                  borderRadius: BorderRadius.circular(13),
                ),
                child: Text(
                  '${index + 1}'.padLeft(2, '0'),
                  style: const TextStyle(fontWeight: FontWeight.w900),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      entry.exercise.name,
                      style: const TextStyle(fontWeight: FontWeight.w900),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${entry.defaultSets} 组 × ${entry.defaultReps} 次  ·  ${entry.defaultWeight} kg',
                      style: const TextStyle(color: FitTrackTheme.muted),
                    ),
                  ],
                ),
              ),
              PopupMenuButton<_EntryAction>(
                onSelected: (action) {
                  switch (action) {
                    case _EntryAction.edit:
                      onEdit();
                    case _EntryAction.moveUp:
                      onMoveUp();
                    case _EntryAction.moveDown:
                      onMoveDown();
                    case _EntryAction.remove:
                      onRemove();
                  }
                },
                itemBuilder: (context) => [
                  const PopupMenuItem(
                    value: _EntryAction.edit,
                    child: Text('调整默认值'),
                  ),
                  if (!isFirst)
                    const PopupMenuItem(
                      value: _EntryAction.moveUp,
                      child: Text('上移'),
                    ),
                  if (!isLast)
                    const PopupMenuItem(
                      value: _EntryAction.moveDown,
                      child: Text('下移'),
                    ),
                  const PopupMenuItem(
                    value: _EntryAction.remove,
                    child: Text('移除动作'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

enum _EntryAction { edit, moveUp, moveDown, remove }

class _ExercisePicker extends StatelessWidget {
  const _ExercisePicker({required this.exercises});

  final List<Exercise> exercises;

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.82,
      maxChildSize: 0.94,
      builder: (context, controller) => ListView.builder(
        controller: controller,
        itemCount: exercises.length + 1,
        itemBuilder: (context, index) {
          if (index == 0) {
            return const Padding(
              padding: EdgeInsets.fromLTRB(24, 4, 24, 12),
              child: Text(
                '选择动作',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900),
              ),
            );
          }
          final exercise = exercises[index - 1];
          return ListTile(
            title: Text(exercise.name),
            subtitle: Text(
              '${exercise.muscleGroup} · ${exercise.category}${exercise.isCustom ? ' · 自定义' : ''}',
            ),
            trailing: const Icon(Icons.add_circle_outline_rounded),
            onTap: () => Navigator.pop(context, exercise),
          );
        },
      ),
    );
  }
}

class _DefaultsDialog extends StatefulWidget {
  const _DefaultsDialog({required this.entry});

  final PlanExerciseDraft entry;

  @override
  State<_DefaultsDialog> createState() => _DefaultsDialogState();
}

class _DefaultsDialogState extends State<_DefaultsDialog> {
  late final TextEditingController _sets;
  late final TextEditingController _reps;
  late final TextEditingController _weight;

  @override
  void initState() {
    super.initState();
    _sets = TextEditingController(text: '${widget.entry.defaultSets}');
    _reps = TextEditingController(text: '${widget.entry.defaultReps}');
    _weight = TextEditingController(text: '${widget.entry.defaultWeight}');
  }

  @override
  void dispose() {
    _sets.dispose();
    _reps.dispose();
    _weight.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.entry.exercise.name),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _sets,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: '默认组数'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _reps,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: '默认次数'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _weight,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: '默认重量（kg）'),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () {
            final sets = int.tryParse(_sets.text);
            final reps = int.tryParse(_reps.text);
            final weight = double.tryParse(_weight.text);
            if (sets == null ||
                reps == null ||
                weight == null ||
                sets < 1 ||
                reps < 1 ||
                weight < 0) {
              return;
            }
            Navigator.pop(
              context,
              widget.entry.copyWith(
                defaultSets: sets,
                defaultReps: reps,
                defaultWeight: weight,
              ),
            );
          },
          child: const Text('确认'),
        ),
      ],
    );
  }
}

class _CustomExerciseInput {
  const _CustomExerciseInput({
    required this.name,
    required this.category,
    required this.muscleGroup,
  });

  final String name;
  final String category;
  final String muscleGroup;
}

class _CustomExerciseDialog extends StatefulWidget {
  const _CustomExerciseDialog();

  @override
  State<_CustomExerciseDialog> createState() => _CustomExerciseDialogState();
}

class _CustomExerciseDialogState extends State<_CustomExerciseDialog> {
  final _name = TextEditingController();
  final _muscleGroup = TextEditingController();
  String _category = '复合';

  @override
  void dispose() {
    _name.dispose();
    _muscleGroup.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('创建自定义动作'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _name,
            autofocus: true,
            decoration: const InputDecoration(labelText: '动作名称'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _muscleGroup,
            decoration: const InputDecoration(labelText: '主要肌群'),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            initialValue: _category,
            decoration: const InputDecoration(labelText: '动作类型'),
            items: const ['复合', '孤立', '有氧', '拉伸']
                .map((item) => DropdownMenuItem(value: item, child: Text(item)))
                .toList(growable: false),
            onChanged: (value) =>
                setState(() => _category = value ?? _category),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () {
            if (_name.text.trim().isEmpty || _muscleGroup.text.trim().isEmpty) {
              return;
            }
            Navigator.pop(
              context,
              _CustomExerciseInput(
                name: _name.text,
                category: _category,
                muscleGroup: _muscleGroup.text,
              ),
            );
          },
          child: const Text('创建'),
        ),
      ],
    );
  }
}
