import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/fittrack_theme.dart';
import '../../models/workout_history.dart';
import '../../providers/history_providers.dart';

class HistoryScreen extends ConsumerWidget {
  const HistoryScreen({super.key});

  Future<void> _addBodyweight(BuildContext context, WidgetRef ref) async {
    final input = await showModalBottomSheet<_WeightInput>(
      context: context,
      isScrollControlled: true,
      builder: (context) => const _WeightEntrySheet(),
    );
    if (input == null) {
      return;
    }
    try {
      await ref
          .read(workoutHistoryRepositoryProvider)
          .saveBodyweight(loggedOn: input.loggedOn, weight: input.weight);
      ref.invalidate(bodyweightLogsProvider);
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('体重记录已保存。')));
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('体重记录暂时无法保存，请稍后重试。')));
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sessions = ref.watch(workoutHistoryProvider);
    final weights = ref.watch(bodyweightLogsProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('训练历史'),
            Text(
              'YOUR PROGRESS LOG',
              style: TextStyle(
                color: FitTrackTheme.muted,
                fontSize: 9,
                fontWeight: FontWeight.w900,
                letterSpacing: 1.1,
              ),
            ),
          ],
        ),
      ),
      body: sessions.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _HistoryError(
          onRetry: () {
            ref.invalidate(workoutHistoryProvider);
            ref.invalidate(bodyweightLogsProvider);
          },
        ),
        data: (items) => ListView(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 32),
          children: [
            _BodyweightPanel(
              logs: weights.valueOrNull ?? const [],
              onAdd: () => _addBodyweight(context, ref),
            ),
            const SizedBox(height: 30),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('已完成训练', style: Theme.of(context).textTheme.titleLarge),
                Text(
                  '${items.length.toString().padLeft(2, '0')} 次',
                  style: const TextStyle(
                    color: FitTrackTheme.muted,
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 13),
            if (items.isEmpty)
              const _HistoryEmpty()
            else
              ...items.map(
                (session) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _HistoryCard(session: session),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class HistoryDetailScreen extends ConsumerWidget {
  const HistoryDetailScreen({super.key, required this.sessionId});

  final String sessionId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(workoutSessionProvider(sessionId));
    return Scaffold(
      appBar: AppBar(title: const Text('训练详情')),
      body: session.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => const _HistoryError(),
        data: (item) => ListView(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 32),
          children: [
            _DetailHero(session: item),
            const SizedBox(height: 26),
            const Text(
              '动作明细',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900),
            ),
            const SizedBox(height: 12),
            ...item.exercises.map(
              (exercise) => _ExerciseHistoryCard(exercise: exercise),
            ),
          ],
        ),
      ),
    );
  }
}

class _BodyweightPanel extends StatelessWidget {
  const _BodyweightPanel({required this.logs, required this.onAdd});

  final List<BodyweightLog> logs;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final latest = logs.isEmpty ? null : logs.first;
    final change = logs.length >= 2 ? latest!.weight - logs[1].weight : null;
    final range = logs.take(7).toList(growable: false).reversed.toList();
    final minimum = range.isEmpty
        ? 0.0
        : range.map((item) => item.weight).reduce((a, b) => a < b ? a : b);
    final maximum = range.isEmpty
        ? 1.0
        : range.map((item) => item.weight).reduce((a, b) => a > b ? a : b);
    final spread = (maximum - minimum).abs() < .01 ? 1.0 : maximum - minimum;

    return Container(
      padding: const EdgeInsets.fromLTRB(20, 19, 14, 17),
      decoration: BoxDecoration(
        color: FitTrackTheme.ink,
        borderRadius: BorderRadius.circular(26),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text(
                '身体体重',
                style: TextStyle(
                  color: FitTrackTheme.lime,
                  fontWeight: FontWeight.w900,
                  letterSpacing: .5,
                ),
              ),
              const Spacer(),
              TextButton(
                onPressed: onAdd,
                style: TextButton.styleFrom(
                  foregroundColor: FitTrackTheme.lime,
                ),
                child: const Text('记录'),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                latest == null ? '—' : _formatNumber(latest.weight),
                style: const TextStyle(
                  color: Color(0xFFF7FFE9),
                  fontSize: 36,
                  fontWeight: FontWeight.w900,
                  height: 1,
                ),
              ),
              const Padding(
                padding: EdgeInsets.only(left: 7, bottom: 4),
                child: Text('kg', style: TextStyle(color: Color(0xFFB8C5B2))),
              ),
              const Spacer(),
              if (change != null)
                Text(
                  '${change >= 0 ? '+' : ''}${_formatNumber(change)} kg',
                  style: const TextStyle(
                    color: Color(0xFFB8C5B2),
                    fontWeight: FontWeight.w800,
                  ),
                ),
            ],
          ),
          const SizedBox(height: 18),
          SizedBox(
            height: 40,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: range.isEmpty
                  ? List.generate(
                      7,
                      (_) => const Expanded(
                        child: Padding(
                          padding: EdgeInsets.symmetric(horizontal: 3),
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: Color(0xFF314733),
                              borderRadius: BorderRadius.vertical(
                                top: Radius.circular(4),
                              ),
                            ),
                          ),
                        ),
                      ),
                    )
                  : range
                        .map(
                          (log) => Expanded(
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 3,
                              ),
                              child: Align(
                                alignment: Alignment.bottomCenter,
                                child: Container(
                                  height:
                                      10 + 30 * (log.weight - minimum) / spread,
                                  decoration: const BoxDecoration(
                                    color: FitTrackTheme.lime,
                                    borderRadius: BorderRadius.vertical(
                                      top: Radius.circular(4),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        )
                        .toList(growable: false),
            ),
          ),
        ],
      ),
    );
  }
}

class _HistoryCard extends StatelessWidget {
  const _HistoryCard({required this.session});

  final WorkoutSessionSummary session;

  @override
  Widget build(BuildContext context) {
    final date = session.startedAt;
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(22),
      child: InkWell(
        borderRadius: BorderRadius.circular(22),
        onTap: () => context.go('/history/${session.id}'),
        child: Padding(
          padding: const EdgeInsets.all(17),
          child: Row(
            children: [
              Container(
                height: 50,
                width: 50,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: FitTrackTheme.mist,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      '${date.day}',
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    Text(
                      '${date.month}月',
                      style: const TextStyle(
                        fontSize: 10,
                        color: FitTrackTheme.muted,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      session.name,
                      style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      '${session.completedSets} 组  ·  ${session.durationMinutes} 分钟  ·  ${_formatNumber(session.volume)} kg',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: FitTrackTheme.muted,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(
                Icons.chevron_right_rounded,
                color: FitTrackTheme.muted,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DetailHero extends StatelessWidget {
  const _DetailHero({required this.session});

  final WorkoutSessionSummary session;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: FitTrackTheme.ink,
        borderRadius: BorderRadius.circular(26),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${session.startedAt.year} 年 ${session.startedAt.month} 月 ${session.startedAt.day} 日',
            style: const TextStyle(
              color: FitTrackTheme.lime,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            session.name,
            style: const TextStyle(
              color: Color(0xFFF7FFE9),
              fontSize: 27,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 22),
          Row(
            children: [
              _HeroMetric(value: '${session.completedSets}', label: '完成组数'),
              const SizedBox(width: 26),
              _HeroMetric(
                value: _formatNumber(session.volume),
                label: '总训练量 kg',
              ),
              const SizedBox(width: 26),
              _HeroMetric(value: '${session.durationMinutes}', label: '分钟'),
            ],
          ),
        ],
      ),
    );
  }
}

class _HeroMetric extends StatelessWidget {
  const _HeroMetric({required this.value, required this.label});
  final String value;
  final String label;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        value,
        style: const TextStyle(
          color: Color(0xFFF7FFE9),
          fontSize: 18,
          fontWeight: FontWeight.w900,
        ),
      ),
      const SizedBox(height: 4),
      Text(
        label,
        style: const TextStyle(color: Color(0xFFB8C5B2), fontSize: 11),
      ),
    ],
  );
}

class _ExerciseHistoryCard extends StatelessWidget {
  const _ExerciseHistoryCard({required this.exercise});
  final WorkoutSessionExercise exercise;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(17),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: FitTrackTheme.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            exercise.name,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: exercise.sets
                .map(
                  (set) => Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: FitTrackTheme.mist,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      '${set.weight} kg × ${set.reps}',
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                  ),
                )
                .toList(growable: false),
          ),
          if (exercise.estimatedOneRepMax > 0) ...[
            const SizedBox(height: 12),
            Text(
              '估算 1RM  ${_formatNumber(exercise.estimatedOneRepMax)} kg',
              style: const TextStyle(color: FitTrackTheme.muted, fontSize: 13),
            ),
          ],
        ],
      ),
    );
  }
}

class _HistoryEmpty extends StatelessWidget {
  const _HistoryEmpty();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(24),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(22),
    ),
    child: const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.insights_rounded, color: FitTrackTheme.forest, size: 31),
        SizedBox(height: 16),
        Text(
          '第一条记录还在路上',
          style: TextStyle(fontSize: 19, fontWeight: FontWeight.w900),
        ),
        SizedBox(height: 7),
        Text(
          '完成一次训练后，体量和每一组数据都会在这里沉淀下来。',
          style: TextStyle(color: FitTrackTheme.muted),
        ),
      ],
    ),
  );
}

class _HistoryError extends StatelessWidget {
  const _HistoryError({this.onRetry});
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(
          Icons.cloud_off_rounded,
          size: 42,
          color: FitTrackTheme.muted,
        ),
        const SizedBox(height: 14),
        const Text('历史记录暂时无法读取', style: TextStyle(fontWeight: FontWeight.w900)),
        if (onRetry != null) ...[
          const SizedBox(height: 14),
          OutlinedButton(onPressed: onRetry, child: const Text('重新加载')),
        ],
      ],
    ),
  );
}

class _WeightInput {
  const _WeightInput({required this.loggedOn, required this.weight});
  final DateTime loggedOn;
  final double weight;
}

class _WeightEntrySheet extends StatefulWidget {
  const _WeightEntrySheet();

  @override
  State<_WeightEntrySheet> createState() => _WeightEntrySheetState();
}

class _WeightEntrySheetState extends State<_WeightEntrySheet> {
  final _weight = TextEditingController();
  DateTime _date = DateTime.now();

  @override
  void dispose() {
    _weight.dispose();
    super.dispose();
  }

  Future<void> _pickDate() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
    );
    if (date != null && mounted) setState(() => _date = date);
  }

  void _save() {
    final weight = double.tryParse(_weight.text);
    if (weight == null || weight <= 0 || weight > 500) return;
    Navigator.pop(context, _WeightInput(loggedOn: _date, weight: weight));
  }

  @override
  Widget build(BuildContext context) => Padding(
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
          '记录体重',
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900),
        ),
        const SizedBox(height: 20),
        TextField(
          controller: _weight,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(
            labelText: '体重（kg）',
            hintText: '例如 72.5',
          ),
          onSubmitted: (_) => _save(),
        ),
        const SizedBox(height: 10),
        TextButton.icon(
          onPressed: _pickDate,
          icon: const Icon(Icons.calendar_today_outlined),
          label: Text('${_date.year} 年 ${_date.month} 月 ${_date.day} 日'),
        ),
        const SizedBox(height: 14),
        FilledButton(onPressed: _save, child: const Text('保存体重记录')),
      ],
    ),
  );
}

String _formatNumber(double value) => value == value.roundToDouble()
    ? value.toInt().toString()
    : value.toStringAsFixed(1);
