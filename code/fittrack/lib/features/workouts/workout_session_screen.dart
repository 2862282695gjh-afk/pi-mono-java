import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/fittrack_theme.dart';
import '../../models/workout_draft.dart';
import '../../models/workout_plan.dart';
import '../../providers/auth_providers.dart';
import '../../providers/plan_providers.dart';
import '../../providers/workout_providers.dart';

class WorkoutSessionScreen extends ConsumerStatefulWidget {
  const WorkoutSessionScreen({super.key, required this.planId});

  final String planId;

  @override
  ConsumerState<WorkoutSessionScreen> createState() =>
      _WorkoutSessionScreenState();
}

class _WorkoutSessionScreenState extends ConsumerState<WorkoutSessionScreen> {
  WorkoutDraft? _draft;
  Future<void> _writeQueue = Future.value();
  Timer? _clock;
  DateTime _now = DateTime.now();
  DateTime? _restEndsAt;
  bool _isFinishing = false;

  @override
  void initState() {
    super.initState();
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) {
        setState(() => _now = DateTime.now());
      }
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    super.dispose();
  }

  void _ensureDraft(WorkoutPlan plan) {
    if (_draft != null) {
      return;
    }
    final ownerId = ref.read(authRepositoryProvider).currentUserId();
    if (ownerId == null) {
      return;
    }
    final draft = WorkoutDraft(
      localId: '$ownerId-${DateTime.now().microsecondsSinceEpoch}',
      ownerId: ownerId,
      planId: plan.id,
      name: plan.name,
      startedAt: DateTime.now(),
      exercises: plan.entries
          .map(
            (entry) => WorkoutExerciseDraft(
              exerciseId: entry.exercise.id,
              exerciseName: entry.exercise.name,
              sortOrder: entry.sortOrder,
              sets: List.generate(
                entry.defaultSets,
                (index) => WorkoutSetDraft(
                  setIndex: index,
                  weight: entry.defaultWeight,
                  reps: entry.defaultReps,
                ),
              ),
            ),
          )
          .toList(growable: false),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _draft != null) {
        return;
      }
      setState(() => _draft = draft);
      unawaited(_persist(draft));
    });
  }

  Future<void> _persist(WorkoutDraft draft) {
    _writeQueue = _writeQueue
        .catchError((Object _) {})
        .then(
          (_) => ref.read(workoutBufferRepositoryProvider).saveDraft(draft),
        );
    return _writeQueue;
  }

  Future<void> _editSet(int exerciseIndex, int setIndex) async {
    final draft = _draft;
    if (draft == null) {
      return;
    }
    final set = draft.exercises[exerciseIndex].sets[setIndex];
    final input = await showModalBottomSheet<_SetInput>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _SetEditorSheet(set: set),
    );
    if (input == null || !mounted) {
      return;
    }
    _replaceSet(
      exerciseIndex,
      setIndex,
      set.copyWith(weight: input.weight, reps: input.reps),
    );
  }

  void _toggleSet(int exerciseIndex, int setIndex) {
    final draft = _draft;
    if (draft == null) {
      return;
    }
    final oldSet = draft.exercises[exerciseIndex].sets[setIndex];
    final willComplete = !oldSet.isCompleted;
    _replaceSet(
      exerciseIndex,
      setIndex,
      WorkoutSetDraft(
        setIndex: oldSet.setIndex,
        weight: oldSet.weight,
        reps: oldSet.reps,
        completedAt: willComplete ? DateTime.now() : null,
      ),
      startRest: willComplete,
    );
  }

  void _replaceSet(
    int exerciseIndex,
    int setIndex,
    WorkoutSetDraft replacement, {
    bool startRest = false,
  }) {
    final draft = _draft;
    if (draft == null) {
      return;
    }
    final sets = List<WorkoutSetDraft>.from(
      draft.exercises[exerciseIndex].sets,
    );
    sets[setIndex] = replacement;
    final exercises = List<WorkoutExerciseDraft>.from(draft.exercises);
    exercises[exerciseIndex] = exercises[exerciseIndex].copyWith(sets: sets);
    final updated = draft.copyWith(exercises: exercises);
    setState(() {
      _draft = updated;
      if (startRest) {
        _restEndsAt = DateTime.now().add(const Duration(seconds: 90));
      }
    });
    unawaited(_persist(updated));
  }

  Future<void> _finish() async {
    final draft = _draft;
    if (draft == null || _isFinishing) {
      return;
    }
    final completedSets = _completedSetCount(draft);
    if (completedSets == 0) {
      _showMessage('至少完成一组后再结束训练');
      return;
    }

    final finished = draft.copyWith(endedAt: DateTime.now());
    setState(() {
      _draft = finished;
      _isFinishing = true;
    });
    try {
      await _persist(finished);
      await ref
          .read(workoutBufferRepositoryProvider)
          .syncFinishedDraft(finished);
      if (mounted) {
        _showMessage('训练已同步到云端，干得漂亮。');
        context.go('/');
      }
    } catch (_) {
      if (mounted) {
        _showMessage('训练已安全保存在本机，网络恢复后可同步。');
        context.go('/');
      }
    } finally {
      if (mounted) {
        setState(() => _isFinishing = false);
      }
    }
  }

  int _completedSetCount(WorkoutDraft draft) => draft.exercises
      .expand((exercise) => exercise.sets)
      .where((set) => set.isCompleted)
      .length;

  int get _restSecondsLeft {
    final endsAt = _restEndsAt;
    if (endsAt == null) {
      return 0;
    }
    return endsAt.difference(_now).inSeconds.clamp(0, 90);
  }

  String _elapsedLabel(DateTime startedAt) {
    final duration = _now.difference(startedAt);
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '${duration.inHours.toString().padLeft(2, '0')}:$minutes:$seconds';
  }

  void _showMessage(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final plans = ref.watch(workoutPlansProvider);
    return plans.when(
      loading: () =>
          const Scaffold(body: Center(child: CircularProgressIndicator())),
      error: (_, _) => const _WorkoutUnavailable(),
      data: (items) {
        final plan = items
            .where((item) => item.id == widget.planId)
            .firstOrNull;
        if (plan == null) {
          return const _WorkoutUnavailable();
        }
        _ensureDraft(plan);
        final draft = _draft;
        if (draft == null) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }
        final completed = _completedSetCount(draft);
        return Scaffold(
          body: SafeArea(
            child: Column(
              children: [
                _WorkoutHeader(
                  name: draft.name,
                  elapsed: _elapsedLabel(draft.startedAt),
                  onClose: () => context.pop(),
                ),
                if (_restSecondsLeft > 0)
                  _RestTicker(
                    secondsLeft: _restSecondsLeft,
                    onSkip: () => setState(() => _restEndsAt = null),
                  ),
                Expanded(
                  child: ListView.builder(
                    padding: const EdgeInsets.fromLTRB(20, 18, 20, 120),
                    itemCount: draft.exercises.length,
                    itemBuilder: (context, index) => _SessionExerciseCard(
                      exercise: draft.exercises[index],
                      number: index + 1,
                      onToggle: (setIndex) => _toggleSet(index, setIndex),
                      onEdit: (setIndex) => _editSet(index, setIndex),
                    ),
                  ),
                ),
              ],
            ),
          ),
          bottomNavigationBar: SafeArea(
            minimum: const EdgeInsets.fromLTRB(20, 8, 20, 16),
            child: FilledButton.icon(
              onPressed: _isFinishing ? null : _finish,
              icon: _isFinishing
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.check_rounded),
              label: Text(
                completed == 0 ? '完成一组后结束训练' : '结束训练  ·  已完成 $completed 组',
              ),
            ),
          ),
        );
      },
    );
  }
}

class _WorkoutHeader extends StatelessWidget {
  const _WorkoutHeader({
    required this.name,
    required this.elapsed,
    required this.onClose,
  });

  final String name;
  final String elapsed;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      padding: const EdgeInsets.fromLTRB(18, 16, 12, 16),
      decoration: BoxDecoration(
        color: FitTrackTheme.ink,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '训练进行中',
                  style: TextStyle(
                    color: FitTrackTheme.lime,
                    fontSize: 12,
                    fontWeight: FontWeight.w900,
                    letterSpacing: .8,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFFF7FFE9),
                    fontSize: 21,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ],
            ),
          ),
          Column(
            children: [
              Text(
                elapsed,
                style: const TextStyle(
                  color: Color(0xFFF7FFE9),
                  fontFeatures: [FontFeature.tabularFigures()],
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 6),
              IconButton(
                onPressed: onClose,
                tooltip: '退出训练',
                color: FitTrackTheme.lime,
                icon: const Icon(Icons.close_rounded),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _RestTicker extends StatelessWidget {
  const _RestTicker({required this.secondsLeft, required this.onSkip});

  final int secondsLeft;
  final VoidCallback onSkip;

  @override
  Widget build(BuildContext context) {
    final minutes = (secondsLeft ~/ 60).toString().padLeft(2, '0');
    final seconds = (secondsLeft % 60).toString().padLeft(2, '0');
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFE0D5),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          const Icon(Icons.timer_outlined, color: FitTrackTheme.signal),
          const SizedBox(width: 10),
          Text(
            '组间休息  $minutes:$seconds',
            style: const TextStyle(fontWeight: FontWeight.w900),
          ),
          const Spacer(),
          TextButton(onPressed: onSkip, child: const Text('跳过')),
        ],
      ),
    );
  }
}

class _SessionExerciseCard extends StatelessWidget {
  const _SessionExerciseCard({
    required this.exercise,
    required this.number,
    required this.onToggle,
    required this.onEdit,
  });

  final WorkoutExerciseDraft exercise;
  final int number;
  final ValueChanged<int> onToggle;
  final ValueChanged<int> onEdit;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: FitTrackTheme.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                '${number.toString().padLeft(2, '0')}.',
                style: const TextStyle(
                  color: FitTrackTheme.signal,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(width: 9),
              Expanded(
                child: Text(
                  exercise.exerciseName,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 13),
          const Padding(
            padding: EdgeInsets.only(left: 12, right: 5),
            child: Row(
              children: [
                Expanded(child: Text('组', style: _SetLabelStyle())),
                Expanded(child: Text('重量', style: _SetLabelStyle())),
                Expanded(child: Text('次数', style: _SetLabelStyle())),
                SizedBox(width: 46),
              ],
            ),
          ),
          ...List.generate(
            exercise.sets.length,
            (index) => _SetRow(
              set: exercise.sets[index],
              onToggle: () => onToggle(index),
              onEdit: () => onEdit(index),
            ),
          ),
        ],
      ),
    );
  }
}

class _SetLabelStyle extends TextStyle {
  const _SetLabelStyle()
    : super(
        color: FitTrackTheme.muted,
        fontSize: 12,
        fontWeight: FontWeight.w800,
      );
}

class _SetRow extends StatelessWidget {
  const _SetRow({
    required this.set,
    required this.onToggle,
    required this.onEdit,
  });

  final WorkoutSetDraft set;
  final VoidCallback onToggle;
  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: set.isCompleted ? FitTrackTheme.mist : Colors.transparent,
      borderRadius: BorderRadius.circular(13),
      child: InkWell(
        onTap: onEdit,
        borderRadius: BorderRadius.circular(13),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '${set.setIndex + 1}'.padLeft(2, '0'),
                  style: const TextStyle(fontWeight: FontWeight.w900),
                ),
              ),
              Expanded(child: Text('${set.weight} kg')),
              Expanded(child: Text('${set.reps} 次')),
              SizedBox(
                width: 46,
                child: IconButton(
                  onPressed: onToggle,
                  icon: Icon(
                    set.isCompleted
                        ? Icons.check_circle_rounded
                        : Icons.radio_button_unchecked_rounded,
                    color: set.isCompleted
                        ? FitTrackTheme.forest
                        : FitTrackTheme.muted,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SetInput {
  const _SetInput({required this.weight, required this.reps});

  final double weight;
  final int reps;
}

class _SetEditorSheet extends StatefulWidget {
  const _SetEditorSheet({required this.set});

  final WorkoutSetDraft set;

  @override
  State<_SetEditorSheet> createState() => _SetEditorSheetState();
}

class _SetEditorSheetState extends State<_SetEditorSheet> {
  late final TextEditingController _weight;
  late final TextEditingController _reps;

  @override
  void initState() {
    super.initState();
    _weight = TextEditingController(text: '${widget.set.weight}');
    _reps = TextEditingController(text: '${widget.set.reps}');
  }

  @override
  void dispose() {
    _weight.dispose();
    _reps.dispose();
    super.dispose();
  }

  void _save() {
    final weight = double.tryParse(_weight.text);
    final reps = int.tryParse(_reps.text);
    if (weight == null || weight < 0 || reps == null || reps < 1) {
      return;
    }
    Navigator.pop(context, _SetInput(weight: weight, reps: reps));
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(
        20,
        14,
        20,
        20 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '调整本组数据',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900),
          ),
          const SizedBox(height: 20),
          TextField(
            controller: _weight,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: '重量（kg）'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _reps,
            keyboardType: TextInputType.number,
            onSubmitted: (_) => _save(),
            decoration: const InputDecoration(labelText: '次数'),
          ),
          const SizedBox(height: 20),
          FilledButton(onPressed: _save, child: const Text('保存本组')),
        ],
      ),
    );
  }
}

class _WorkoutUnavailable extends StatelessWidget {
  const _WorkoutUnavailable();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.fitness_center_rounded, size: 42),
              const SizedBox(height: 16),
              const Text(
                '这份计划暂时无法开始',
                style: TextStyle(fontWeight: FontWeight.w900),
              ),
              const SizedBox(height: 16),
              OutlinedButton(
                onPressed: () => context.go('/'),
                child: const Text('返回计划列表'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
