<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import BrandMark from './BrandMark.vue';
import OverlayHost from './OverlayHost.vue';
import type { ThreadSummary } from '../types/product';
import type { OverlayCloseReason } from '../composables/useOverlayHost';
import { useSessionRename } from '../composables/useSessionRename';

const props = defineProps<{
  threads: ThreadSummary[];
  currentSessionId?: string;
  compact: boolean;
  unavailable?: string[];
  showDeveloperSettings?: boolean;
  renameThread: (sessionId: string, displayName: string) => Promise<void>;
  togglePinned: (sessionId: string) => void;
}>();

const emit = defineEmits<{
  new: [];
  select: [sessionId: string];
  settings: [];
  toggle: [];
  delete: [sessionId: string];
  forget: [sessionId: string];
}>();

const query = ref('');
const openMenuId = ref('');
const renameInput = ref<HTMLInputElement | null>(null);
const menuTriggers = new Map<string, HTMLButtonElement>();
const menuInitialFocus = ref<'first' | 'last'>('first');
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
const openMenuThread = computed(() => props.threads.find((thread) => thread.sessionId === openMenuId.value));

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
function setMenuTrigger(sessionId: string, element: unknown): void {
  if (element instanceof HTMLButtonElement) menuTriggers.set(sessionId, element);
  else menuTriggers.delete(sessionId);
}
function menuTrigger(sessionId: string): HTMLButtonElement | null {
  return menuTriggers.get(sessionId) ?? null;
}
function toggleMenu(sessionId: string): void {
  if (openMenuId.value === sessionId) { closeMenu(); return; }
  menuInitialFocus.value = 'first';
  openMenuId.value = sessionId;
}
function menuTriggerKeydown(event: KeyboardEvent, sessionId: string): void {
  if (event.key === 'Escape' && openMenuId.value === sessionId) {
    event.preventDefault();
    closeMenu(true);
    return;
  }
  if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
  event.preventDefault();
  menuInitialFocus.value = event.key === 'ArrowUp' ? 'last' : 'first';
  openMenuId.value = sessionId;
}
function closeMenu(focusTrigger = false): void {
  const sessionId = openMenuId.value;
  openMenuId.value = '';
  if (focusTrigger && sessionId) void nextTick(() => menuTriggers.get(sessionId)?.focus({ preventScroll: true }));
}
function closeOverlay(reason: OverlayCloseReason): void {
  closeMenu(reason === 'escape');
}
function cancelRename(): void {
  cancel();
}
async function saveRename(): Promise<void> { await save(); }
function pin(thread: ThreadSummary): void {
  closeMenu(true);
  props.togglePinned(thread.sessionId);
}
function requestDelete(thread: ThreadSummary): void {
  const sessionId = thread.sessionId;
  closeMenu();
  emit('delete', sessionId);
}
function closeMobileSidebar(event: KeyboardEvent): void {
  if (!props.compact && window.matchMedia('(max-width: 900px)').matches) {
    event.preventDefault();
    emit('toggle');
  }
}
watch(() => props.currentSessionId, () => closeMenu());
watch(() => filtered.value.map((thread) => thread.sessionId).join('\n'), () => {
  if (openMenuId.value && !filtered.value.some((thread) => thread.sessionId === openMenuId.value)) closeMenu();
});
</script>

<template>
  <aside class="sidebar" :class="{ compact }" @keydown.esc="closeMobileSidebar">
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

    <div v-if="threads.length" class="thread-search-region">
      <label class="thread-search">
        <span class="sr-only">搜索本地会话</span>
        <input v-model="query" type="search" placeholder="搜索会话" autocomplete="off">
      </label>
      <small aria-live="polite">{{ query.trim() ? `${filtered.length} 条结果` : '会话列表' }}</small>
    </div>

    <nav class="thread-nav" aria-label="会话">
      <template v-for="group in [{ label: '置顶', items: pinnedThreads }, { label: '最近', items: recentThreads }]" :key="group.label">
        <p v-if="group.items.length" class="nav-label">{{ group.label }}</p>
        <div v-for="thread in group.items" :key="thread.sessionId" class="thread-item">
          <form v-if="renameId === thread.sessionId" class="thread-rename" @submit.prevent="saveRename">
            <label>
              <span class="sr-only">会话名称</span>
              <input :ref="setRenameInput" v-model="renameDraft" :disabled="renamePending" maxlength="80"
                @keydown.esc.prevent.stop="cancelRename">
            </label>
            <div class="thread-rename-actions">
              <button type="submit" :disabled="renamePending">{{ renamePending ? '保存中…' : '保存' }}</button>
              <button type="button" :disabled="renamePending" @click="cancelRename">取消</button>
            </div>
            <p v-if="renameError" role="alert">{{ renameError }}</p>
          </form>
          <template v-else>
            <div class="thread-item-row" :class="{ active: currentSessionId === thread.sessionId }">
              <button class="thread-link" type="button" :aria-current="currentSessionId === thread.sessionId ? 'page' : undefined"
                :aria-label="`${thread.title}，${thread.agentName}，${relativeDate(thread.lastActivityAt)}`"
                :title="thread.title" @click="emit('select', thread.sessionId)">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M7 8h10M7 12h7M6 19l-3 2v-15a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6Z" />
                </svg>
                <span class="thread-copy">
                  <strong>{{ thread.title }}</strong>
                  <small>{{ thread.agentName }}</small>
                </span>
              </button>
              <time :datetime="thread.lastActivityAt">{{ relativeDate(thread.lastActivityAt) }}</time>
              <button :ref="(element) => setMenuTrigger(thread.sessionId, element)" class="thread-menu-button" type="button"
                :aria-label="`会话操作：${thread.title}`" aria-haspopup="menu" :aria-controls="`thread-menu-${thread.sessionId}`"
                :aria-expanded="openMenuId === thread.sessionId" @click.stop="toggleMenu(thread.sessionId)"
                @keydown="menuTriggerKeydown($event, thread.sessionId)">•••</button>
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

    <OverlayHost :open="Boolean(openMenuThread)" :anchor="menuTrigger(openMenuId)" placement="bottom-end"
      :initial-focus="menuInitialFocus" :id="`thread-menu-${openMenuId}`" class="thread-menu" role="menu"
      :aria-label="openMenuThread ? `会话操作：${openMenuThread.title}` : '会话操作'" @request-close="closeOverlay">
      <template v-if="openMenuThread">
        <button type="button" role="menuitem" @click="beginRename(openMenuThread)">重命名</button>
        <button type="button" role="menuitem" @click="pin(openMenuThread)">{{ openMenuThread.pinned ? '取消置顶' : '置顶' }}</button>
        <button class="danger-menu-item" type="button" role="menuitem" @click="requestDelete(openMenuThread)">删除</button>
      </template>
    </OverlayHost>

    <div class="sidebar-footer">
      <button v-if="showDeveloperSettings" class="footer-link" type="button" @click="emit('settings')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
          <circle cx="12" cy="12" r="3" />
        </svg>
        <span>开发者设置</span>
      </button>
      <button class="footer-link" type="button" disabled title="Agent 中心需要公共目录接口">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" /><circle cx="12" cy="12" r="3" /></svg>
        <span>Agent 中心</span>
      </button>
    </div>
  </aside>
  <Teleport to="body">
    <button v-if="!compact" class="sidebar-backdrop" type="button" aria-label="关闭导航" @click="emit('toggle')"></button>
  </Teleport>
</template>
