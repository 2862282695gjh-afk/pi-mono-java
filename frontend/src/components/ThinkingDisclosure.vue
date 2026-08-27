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
      {{ turn.summary || (turn.status === 'running'
        ? '分析进行中，暂未收到面向用户的摘要。'
        : '已收到分析事件，但当前环境未提供面向用户的摘要。') }}
    </p>
  </details>
</template>
