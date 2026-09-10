<script setup lang="ts">
import BrandMark from './BrandMark.vue';
import type { ThreadSummary } from '../types/product';

defineProps<{
  threads: ThreadSummary[];
  currentSessionId?: string;
  compact: boolean;
  unavailable?: string[];
}>();

const emit = defineEmits<{
  new: [];
  select: [sessionId: string];
  toggle: [];
  forget: [sessionId: string];
}>();

function relativeDate(value: string): string {
  const date = new Date(value);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) return '今天';
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
}
</script>

<template>
  <aside class="sidebar" :class="{ compact }">
    <div class="brand-row">
      <BrandMark />
      <strong>CampusClaw</strong>
      <button class="icon-button sidebar-toggle" type="button" aria-label="收起导航" @click="emit('toggle')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 6 9 12l6 6" />
        </svg>
      </button>
    </div>

    <button class="new-thread" type="button" @click="emit('new')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
      <span>新建会话</span>
    </button>

    <nav class="thread-nav" aria-label="最近会话">
      <p v-if="threads.length" class="nav-label">最近</p>
      <div v-for="thread in threads" :key="thread.sessionId" class="thread-item">
        <button
          class="thread-link"
          :class="{ active: currentSessionId === thread.sessionId }"
          type="button"
          @click="emit('select', thread.sessionId)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M7 8h10M7 12h7M6 19l-3 2v-15a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6Z" />
          </svg>
          <span class="thread-copy">
            <strong>{{ thread.title }}</strong>
            <small>{{ thread.agentName }}</small>
          </span>
          <time>{{ relativeDate(thread.updatedAt) }}</time>
        </button>
        <button v-if="unavailable?.includes(thread.sessionId)" class="thread-forget" type="button" @click="emit('forget', thread.sessionId)">无法恢复 · 从列表移除</button>
      </div>
      <div v-if="threads.length === 0" class="nav-empty">
        新建会话后，它会出现在这里。
      </div>
    </nav>

    <div class="sidebar-footer">
      <button class="footer-link" type="button" disabled title="Agent 中心需要公共目录接口">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" /><circle cx="12" cy="12" r="3" /></svg>
        <span>Agent 中心</span>
      </button>
    </div>
  </aside>
</template>
