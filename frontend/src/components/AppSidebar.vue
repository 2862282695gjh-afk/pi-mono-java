<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import BrandMark from './BrandMark.vue';
import type { ThreadSummary } from '../types/product';
import { useSessionRename } from '../composables/useSessionRename';

const props = defineProps<{
  threads: ThreadSummary[];
  currentSessionId?: string;
  compact: boolean;
  unavailable?: string[];
  renameThread: (sessionId: string, displayName: string) => Promise<void>;
  togglePinned: (sessionId: string) => void;
}>();

const emit = defineEmits<{
  new: [];
  select: [sessionId: string];
  toggle: [];
  forget: [sessionId: string];
}>();

const query = ref('');
const openMenuId = ref('');
const renameInput = ref<HTMLInputElement | null>(null);
const { renameId, renameDraft, renameError, renamePending, begin, cancel, save } = useSessionRename(
  (sessionId, displayName) => props.renameThread(sessionId, displayName), () => renameInput.value?.focus(),
);
const filtered = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase('zh-CN');
  return needle ? props.threads.filter((thread) => `${thread.title}\n${thread.agentName}`.toLocaleLowerCase('zh-CN').includes(needle))
    : props.threads;
});
const pinnedThreads = computed(() => filtered.value.filter((thread) => thread.pinned));
const recentThreads = computed(() => filtered.value.filter((thread) => !thread.pinned));

function relativeDate(value: string): string {
  const date = new Date(value);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) return '今天';
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
}
function beginRename(thread: ThreadSummary): void {
  if (!begin(thread.sessionId, thread.title)) return;
  openMenuId.value = '';
  void nextTick(() => { renameInput.value?.focus(); renameInput.value?.select(); });
}
function setRenameInput(element: unknown): void {
  renameInput.value = element instanceof HTMLInputElement ? element : null;
}
function cancelRename(): void {
  cancel();
}
async function saveRename(): Promise<void> { await save(); }
function pin(thread: ThreadSummary): void {
  props.togglePinned(thread.sessionId); openMenuId.value = '';
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

    <nav class="thread-nav" aria-label="会话">
      <label v-if="threads.length" class="thread-search">
        <span class="sr-only">搜索本地会话</span>
        <input v-model="query" type="search" placeholder="搜索会话" autocomplete="off">
      </label>
      <template v-for="group in [{ label: '置顶', items: pinnedThreads }, { label: '最近', items: recentThreads }]" :key="group.label">
        <p v-if="group.items.length" class="nav-label">{{ group.label }}</p>
        <div v-for="thread in group.items" :key="thread.sessionId" class="thread-item">
          <form v-if="renameId === thread.sessionId" class="thread-rename" @submit.prevent="saveRename">
            <label>
              <span class="sr-only">会话名称</span>
              <input :ref="setRenameInput" v-model="renameDraft" :disabled="renamePending" maxlength="80"
                @keydown.esc.prevent="cancelRename">
            </label>
            <div class="thread-rename-actions">
              <button type="submit" :disabled="renamePending">{{ renamePending ? '保存中…' : '保存' }}</button>
              <button type="button" :disabled="renamePending" @click="cancelRename">取消</button>
            </div>
            <p v-if="renameError" role="alert">{{ renameError }}</p>
          </form>
          <template v-else>
            <button class="thread-link" :class="{ active: currentSessionId === thread.sessionId }" type="button"
              @click="emit('select', thread.sessionId)">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M7 8h10M7 12h7M6 19l-3 2v-15a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6Z" />
              </svg>
              <span class="thread-copy">
                <strong>{{ thread.title }}</strong>
                <small>{{ thread.agentName }}</small>
              </span>
              <time>{{ relativeDate(thread.lastActivityAt) }}</time>
            </button>
            <button class="thread-menu-button" type="button" aria-label="会话操作"
              :aria-expanded="openMenuId === thread.sessionId" @click="openMenuId = openMenuId === thread.sessionId ? '' : thread.sessionId">•••</button>
            <div v-if="openMenuId === thread.sessionId" class="thread-menu" role="menu">
              <button type="button" role="menuitem" @click="beginRename(thread)">重命名</button>
              <button type="button" role="menuitem" @click="pin(thread)">{{ thread.pinned ? '取消置顶' : '置顶' }}</button>
            </div>
          </template>
          <button v-if="unavailable?.includes(thread.sessionId)" class="thread-forget" type="button"
            @click="emit('forget', thread.sessionId)">无法恢复 · 从列表移除</button>
        </div>
      </template>
      <div v-if="threads.length === 0" class="nav-empty">
        新建会话后，它会出现在这里。
      </div>
      <div v-else-if="filtered.length === 0" class="nav-empty">没有匹配的本地会话。</div>
    </nav>

    <div class="sidebar-footer">
      <button class="footer-link" type="button" disabled title="Agent 中心需要公共目录接口">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" /><circle cx="12" cy="12" r="3" /></svg>
        <span>Agent 中心</span>
      </button>
    </div>
  </aside>
</template>
