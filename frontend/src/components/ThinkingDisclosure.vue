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
      <span class="thinking-raw-label">{{ turn.status === 'unconfirmed' ? '预览待核对' : '公开摘要' }}</span>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <div class="thinking-content" :class="{ 'thinking-empty': !turn.content }">
      <p v-if="turn.content" class="plain-result">{{ turn.content }}</p>
      <p v-else>
        {{ turn.status === 'running' ? '等待可公开的思考摘要…' : '本次未返回摘要文本。' }}
      </p>
    </div>
  </details>
</template>
