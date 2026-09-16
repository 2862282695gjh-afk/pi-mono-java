<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue';
import { useModeMenu } from '../composables/useModeMenu';
import OverlayHost from './OverlayHost.vue';
import type { OverlayCloseReason } from '../composables/useOverlayHost';

const props = defineProps<{ mode: 'build' | 'plan'; disabled: boolean }>();
const emit = defineEmits<{ select: [mode: 'build' | 'plan'] }>();
const trigger = ref<HTMLButtonElement | null>(null);
const initialFocus = ref<'first' | 'last'>('first');
const id = 'mode-menu-' + useId();
const labels = { build: 'Build', plan: 'Plan' } as const;

const { open, show: showNow, close: closeNow, choose, triggerKeydown } = useModeMenu({
  disabled: () => props.disabled, current: () => props.mode, emit: (mode) => emit('select', mode),
  focusChoice: (index) => { initialFocus.value = index === 0 ? 'first' : 'last'; },
  focusTrigger: () => void nextTick(() => trigger.value?.focus()),
});
function show(index = props.mode === 'build' ? 0 : 1): void { showNow(index); }
function close(focusTrigger = false): void { closeNow(focusTrigger); }
function closeOverlay(reason: OverlayCloseReason): void {
  close(reason === 'escape');
}
watch(() => props.disabled, (disabled) => { if (disabled) close(); });
</script>

<template>
  <div class="mode-menu-control">
    <button ref="trigger" class="mode-menu-trigger" type="button" aria-haspopup="menu" :aria-controls="id"
      :aria-expanded="open" :disabled="disabled" @click="open ? close() : show()" @keydown="triggerKeydown">
      {{ labels[mode] }} <span aria-hidden="true">⌄</span>
    </button>
    <OverlayHost :open="open" :anchor="trigger" placement="top-start" :initial-focus="initialFocus"
      :id="id" class="mode-menu" role="menu" aria-label="选择执行模式" @request-close="closeOverlay">
      <button v-for="(label, value) in labels" :key="value" type="button" role="menuitemradio"
        :aria-checked="mode === value" @click="choose(value)">
        <span>{{ label }}</span><span aria-hidden="true">{{ mode === value ? '✓' : '' }}</span>
      </button>
    </OverlayHost>
  </div>
</template>
