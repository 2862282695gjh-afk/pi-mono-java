<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { RuntimeTodo } from '../types/runtime';

const props = defineProps<{ todos: RuntimeTodo[]; terminal: boolean }>();
const expanded = ref(!props.terminal);
const completed = computed(() => props.todos.filter((todo) => todo.status === 'completed').length);
const active = computed(() => props.todos.find((todo) => todo.status === 'in_progress')?.content);
watch(() => props.terminal, (terminal) => { if (terminal) expanded.value = false; });
const label = computed(() => {
  if (!props.todos.length) return '任务清单已清空';
  const progress = `已完成 ${completed.value} / ${props.todos.length}`;
  if (active.value) return `${progress} · 正在${active.value}`;
  if (completed.value === props.todos.length && !props.terminal) return '清单已完成，任务仍在执行';
  return progress;
});
const statusLabel = (status: RuntimeTodo['status']) => ({ pending: '待处理', in_progress: '进行中', completed: '已完成' })[status];
</script>

<template>
  <section class="progress-disclosure" aria-label="实施任务清单">
    <button type="button" :aria-expanded="expanded" @click="expanded = !expanded">
      <strong>{{ label }}</strong><span aria-hidden="true">{{ expanded ? '收起' : '展开' }}</span>
    </button>
    <ol v-if="expanded && todos.length">
      <li v-for="(todo, index) in todos" :key="`${index}:${todo.content}`" :class="todo.status">
        <span aria-hidden="true">{{ todo.status === 'completed' ? '✓' : todo.status === 'in_progress' ? '●' : '○' }}</span>
        <span>{{ todo.content }}</span><small>{{ statusLabel(todo.status) }}</small>
      </li>
    </ol>
    <p v-else-if="expanded">任务清单已清空。</p>
  </section>
</template>
