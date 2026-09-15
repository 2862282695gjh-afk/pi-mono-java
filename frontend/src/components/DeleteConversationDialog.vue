<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import type { DeletionTarget } from '../composables/useConversationDeletion';

const props = defineProps<{ target: DeletionTarget | null; phase: string; error: string; pending: boolean; canDelete: boolean }>();
const emit = defineEmits<{ cancel: []; confirm: []; check: [] }>();
const dialog = ref<HTMLDialogElement | null>(null);
const cancelButton = ref<HTMLButtonElement | null>(null);
let previousFocus: HTMLElement | null = null;
watch(() => props.target, async (selected) => {
  if (selected) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    await nextTick();
    if (!props.target) return;
    dialog.value?.showModal();
    cancelButton.value?.focus();
  } else {
    dialog.value?.close();
    if (previousFocus?.isConnected) previousFocus.focus();
  }
}, { immediate: true });
onBeforeUnmount(() => dialog.value?.close());
function cancel(): void { if (props.phase !== 'deleting') emit('cancel'); }
function outside(event: MouseEvent): void {
  const element = dialog.value;
  if (!element || event.target !== element) return;
  const bounds = element.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) cancel();
}
</script>

<template>
  <dialog ref="dialog" class="delete-conversation-dialog" aria-labelledby="delete-conversation-title" @cancel.prevent="cancel" @click="outside">
    <h2 id="delete-conversation-title">删除会话？</h2>
    <p class="delete-conversation-name">{{ target?.title }}</p>
    <p>会话内容将被删除，无法恢复。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="pending" role="status">{{ phase === 'deleting' ? '删除中…' : '正在读取状态…' }}</p>
    <div class="delete-conversation-actions">
      <button ref="cancelButton" type="button" class="secondary-button" :disabled="phase === 'deleting'" @click="cancel">取消</button>
      <button v-if="canDelete || pending" type="button" class="delete-confirm-button" :disabled="!canDelete || pending" @click="emit('confirm')">删除</button>
      <button v-else type="button" class="secondary-button" @click="emit('check')">刷新状态</button>
    </div>
  </dialog>
</template>

<style scoped>
.delete-conversation-dialog { width: min(420px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); overflow: auto; margin: auto; padding: 24px; color: var(--text-primary); background: var(--surface-primary); border: 1px solid var(--border-default); border-radius: 16px; }
.delete-conversation-dialog::backdrop { background: rgb(40 32 28 / 25%); }
h2 { margin: 0 0 16px; font-size: 18px; }
p { font-size: 14px; line-height: 1.6; }
.delete-conversation-name { font-weight: 600; overflow-wrap: anywhere; }
.delete-conversation-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 24px; }
.delete-confirm-button { min-height: 44px; padding: 10px 18px; border: 0; border-radius: 10px; color: white; background: #a33126; cursor: pointer; }
.delete-confirm-button:disabled { opacity: .5; cursor: default; }
</style>
