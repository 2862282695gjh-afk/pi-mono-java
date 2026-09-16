<script setup lang="ts">
import { computed, ref } from 'vue';
import { analyzeStructuredText, analyzeStructuredValue } from '../runtime/structuredValue';
import StructuredValueNode from './StructuredValueNode.vue';

const props = defineProps<{ source?: string; value?: unknown; label: string }>();
const view = ref<'structure' | 'raw'>('structure');
const copyStatus = ref('');
const analysis = computed(() => props.source === undefined
  ? analyzeStructuredValue(props.value)
  : analyzeStructuredText(props.source));

async function copyRaw(): Promise<void> {
  try {
    if (!navigator.clipboard) throw new Error('Clipboard API unavailable');
    await navigator.clipboard.writeText(analysis.value.raw);
    copyStatus.value = '已复制';
  } catch {
    copyStatus.value = '复制失败，请手动选择原文';
  }
}
</script>

<template>
  <section class="structured-value" :class="{ 'structured-json': analysis.format === 'json' }" :aria-label="label">
    <div class="structured-toolbar">
      <div v-if="analysis.node" role="group" aria-label="显示方式">
        <button type="button" :aria-pressed="view === 'structure'" @click="view = 'structure'">结构</button>
        <button type="button" :aria-pressed="view === 'raw'" @click="view = 'raw'">原文</button>
      </div>
      <button type="button" class="structured-copy" @click="copyRaw">复制</button>
    </div>
    <p v-if="analysis.tooLarge" class="structured-notice">内容较长，已使用原文模式。</p>
    <div class="structured-body" tabindex="0">
      <StructuredValueNode v-if="analysis.node && view === 'structure'" :node="analysis.node" :depth="0" />
      <pre v-else>{{ analysis.raw }}</pre>
    </div>
    <span class="sr-only" aria-live="polite">{{ copyStatus }}</span>
  </section>
</template>

<style scoped>
.structured-value {
  width: 100%;
  min-width: 0;
  font: 14px/1.6 var(--font-ui, Inter, ui-sans-serif, sans-serif);
}
.structured-value.structured-json {
  font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.structured-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 36px;
  gap: 8px;
}
.structured-toolbar > div {
  display: flex;
  gap: 3px;
}
.structured-toolbar button {
  min-height: 32px;
  padding: 4px 9px;
  border: 1px solid var(--border-default);
  border-radius: 7px;
  color: var(--text-secondary);
  background: var(--surface-primary);
  font: 12px/1.5 var(--font-ui, Inter, ui-sans-serif, sans-serif);
}
.structured-toolbar button[aria-pressed="true"] {
  color: var(--text-primary);
  background: var(--surface-selected);
}
.structured-copy {
  margin-left: auto;
}
.structured-body {
  max-height: 320px;
  padding: 9px 11px;
  overflow: auto;
  border-radius: 7px;
  color: var(--text-secondary);
  background: var(--surface-tertiary);
}
.structured-body pre {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.structured-notice {
  margin: 0 0 5px;
  color: var(--text-secondary);
  font: 12px/1.5 var(--font-ui, ui-sans-serif, sans-serif);
}
</style>

<style scoped>
:deep(.structured-node) {
  min-width: 0;
}
:deep(.structured-node details > summary) {
  min-height: auto;
  padding: 0;
  cursor: pointer;
  list-style-position: outside;
}
:deep(.structured-children) {
  padding-left: 18px;
}
:deep(.structured-line),
:deep(.structured-close) {
  display: block;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
:deep(.structured-key) { color: #745235; }
:deep(.structured-string) { color: #176a4b; }
:deep(.structured-number) { color: #7b3fa1; }
:deep(.structured-boolean),
:deep(.structured-null) { color: #9a4c22; }
</style>
