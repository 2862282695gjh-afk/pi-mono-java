<script setup lang="ts">
import BrandMark from './BrandMark.vue';
import type { ConversationTurn } from '../types/product';

defineProps<{
  turns: ConversationTurn[];
  running: boolean;
}>();

function activityLabel(status: 'running' | 'completed' | 'error'): string {
  if (status === 'running') return '正在执行';
  if (status === 'error') return '执行失败';
  return '已完成';
}
</script>

<template>
  <div class="timeline">
    <div v-if="turns.length === 0" class="conversation-empty">
      <p>描述目标、约束和期望输出，Agent 会在这里展示处理过程。</p>
      <div class="prompt-examples">
        <span>分析最近的异常订单</span>
        <span>整理一份校园活动方案</span>
        <span>检查数据并给出改进建议</span>
      </div>
    </div>

    <template v-for="turn in turns" :key="turn.key">
      <article v-if="turn.kind === 'user'" class="turn user-turn">
        <div class="user-bubble">{{ turn.text || '已提交附件' }}</div>
        <div v-if="turn.fileIds.length" class="attachment-summary">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 12 5-5a3 3 0 0 1 4 4l-7 7a5 5 0 0 1-7-7l7-7" /></svg>
          {{ turn.fileIds.length }} 个附件
        </div>
      </article>

      <article v-else-if="turn.kind === 'assistant'" class="turn assistant-turn">
        <BrandMark />
        <div class="assistant-body">
          <details v-if="turn.thinking" class="thinking-block">
            <summary>查看思考过程</summary>
            <p>{{ turn.thinking }}</p>
          </details>
          <div v-if="turn.text" class="assistant-message" :aria-live="turn.streaming ? 'off' : 'polite'">{{ turn.text }}</div>
          <div v-else-if="turn.streaming" class="assistant-working">
            <span class="spinner" aria-hidden="true"></span>
            正在思考…
          </div>
        </div>
      </article>

      <article v-else class="turn activity-turn">
        <div class="activity-icon" :class="turn.status" aria-hidden="true">
          <svg v-if="turn.status === 'completed'" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
          <svg v-else-if="turn.status === 'error'" viewBox="0 0 24 24"><path d="m7 7 10 10M17 7 7 17" /></svg>
          <span v-else class="spinner"></span>
        </div>
        <details class="activity-card" :open="turn.status === 'error'">
          <summary>
            <span><strong>{{ turn.toolName }}</strong><small>{{ activityLabel(turn.status) }}</small></span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
          </summary>
          <p v-if="turn.result">{{ turn.result }}</p>
          <p v-else>Agent 正在使用该能力处理任务。</p>
        </details>
      </article>
    </template>

    <article v-if="running && turns.every((turn) => turn.kind !== 'assistant' || !turn.streaming)" class="turn assistant-turn running-placeholder">
      <BrandMark />
      <div class="assistant-working"><span class="spinner" aria-hidden="true"></span>正在处理新的要求…</div>
    </article>
  </div>
</template>
