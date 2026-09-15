<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue';
import { useModeMenu } from '../composables/useModeMenu';

const props = defineProps<{ mode: 'build' | 'plan'; disabled: boolean }>();
const emit = defineEmits<{ select: [mode: 'build' | 'plan'] }>();
const root = ref<HTMLElement | null>(null);
const trigger = ref<HTMLButtonElement | null>(null);
const choices = ref<HTMLButtonElement[]>([]);
const id = 'mode-menu-' + useId();
const labels = { build: '普通模式', plan: '计划模式' } as const;

function rememberChoice(element: unknown, index: number): void {
  if (element instanceof HTMLButtonElement) choices.value[index] = element;
}
const { open, show: showNow, close: closeNow, choose, triggerKeydown, menuKeydown } = useModeMenu({
  disabled: () => props.disabled, current: () => props.mode, emit: (mode) => emit('select', mode),
  focusChoice: (index) => void nextTick(() => choices.value[index]?.focus()),
  focusTrigger: () => void nextTick(() => trigger.value?.focus()),
});
function show(index = 0): void { showNow(index); }
function close(focusTrigger = false): void { closeNow(focusTrigger); }
function outside(event: MouseEvent): void {
  if (root.value && !event.composedPath().includes(root.value)) close();
}
function focusout(event: FocusEvent): void {
  if (!(event.relatedTarget instanceof Node) || !root.value?.contains(event.relatedTarget)) close();
}
watch(() => props.disabled, (disabled) => { if (disabled) close(); });
onMounted(() => document.addEventListener('click', outside));
onUnmounted(() => document.removeEventListener('click', outside));
</script>

<template>
  <div ref="root" class="mode-menu-control" @focusout="focusout">
    <button ref="trigger" class="mode-menu-trigger" type="button" aria-haspopup="menu" :aria-controls="id"
      :aria-expanded="open" :disabled="disabled" @click="open ? close() : show()" @keydown="triggerKeydown">
      {{ labels[mode] }} <span aria-hidden="true">⌄</span>
    </button>
    <div v-if="open" :id="id" class="mode-menu" role="menu" aria-label="选择执行模式">
      <button v-for="(label, value, index) in labels" :key="value" :ref="(element) => rememberChoice(element, index)"
        type="button" role="menuitemradio" :aria-checked="mode === value" @click="choose(value)" @keydown="menuKeydown($event, index)">
        <span>{{ label }}</span><span aria-hidden="true">{{ mode === value ? '✓' : '' }}</span>
      </button>
    </div>
  </div>
</template>
