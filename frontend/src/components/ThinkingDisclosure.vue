<script setup lang="ts">
import SafeRichText from './SafeRichText';
import type { ThinkingTurn } from '../types/product';

defineProps<{ turn: ThinkingTurn }>();
</script>

<template>
  <details class="thinking-disclosure" :open="turn.status === 'running'">
    <summary>
      <span v-if="turn.status === 'running'" class="spinner" aria-hidden="true"></span>
      <span v-else class="thinking-complete" aria-hidden="true"></span>
      <strong>{{ turn.title }}</strong>
      <span class="thinking-raw-label">原始推理</span>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <div class="thinking-content" :class="{ 'thinking-empty': !turn.content }">
      <SafeRichText
        v-if="turn.content"
        :source="turn.content"
        :streaming="turn.status === 'running'"
        completion-message="分析已完成"
      />
      <p v-else>
        {{ turn.status === 'running' ? '等待原始推理片段…' : '本次分析未返回推理文本。' }}
      </p>
    </div>
  </details>
</template>
