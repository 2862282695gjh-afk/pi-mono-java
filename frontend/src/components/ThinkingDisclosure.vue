<script setup lang="ts">
import type { ThinkingTurn } from '../types/product';

defineProps<{ turn: ThinkingTurn }>();
</script>

<template>
  <details class="thinking-disclosure" :open="turn.status === 'running'">
    <summary>
      <span v-if="turn.status === 'running'" class="spinner" aria-hidden="true"></span>
      <span v-else class="thinking-complete" aria-hidden="true"></span>
      <strong>{{ turn.title }}</strong>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <p :class="{ 'thinking-empty': !turn.summary }">
      {{ turn.summary || '当前运行环境未提供可安全展示的分析摘要。' }}
    </p>
  </details>
</template>
