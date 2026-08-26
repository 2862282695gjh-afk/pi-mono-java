<script setup lang="ts">
import type { ThinkingTurn } from '../types/product';

defineProps<{ turn: ThinkingTurn }>();
</script>

<template>
  <details v-if="turn.summary" class="thinking-disclosure" :open="turn.status === 'running'">
    <summary>
      <span v-if="turn.status === 'running'" class="spinner" aria-hidden="true"></span>
      <span v-else class="thinking-complete" aria-hidden="true"></span>
      <strong>{{ turn.title }}</strong>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <p>{{ turn.summary }}</p>
  </details>
  <div v-else class="thinking-disclosure thinking-status" role="status">
    <span v-if="turn.status === 'running'" class="spinner" aria-hidden="true"></span>
    <span v-else class="thinking-complete" aria-hidden="true"></span>
    <strong>{{ turn.title }}</strong>
  </div>
</template>
