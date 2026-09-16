<script setup lang="ts">
import { computed, ref } from 'vue';
import type { StructuredNode } from '../runtime/structuredValue';

const props = defineProps<{ node: StructuredNode; label?: string; depth: number }>();
const expanded = ref(props.depth < 2);
const container = computed(() => props.node.kind === 'array' || props.node.kind === 'object');
const children = computed(() => {
  if (props.node.kind === 'array') return props.node.items.map((value, index) => ({ label: String(index), value }));
  if (props.node.kind === 'object') return props.node.entries.map((entry) => ({ label: entry.key, value: entry.value }));
  return [];
});
const emptyContainer = computed(() => container.value && children.value.length === 0);
const containerMark = computed(() => props.node.kind === 'array' ? ['[', ']'] : ['{', '}']);
const scalarRaw = computed(() => props.node.kind === 'array' || props.node.kind === 'object' ? '' : props.node.raw);
</script>

<template>
  <div class="structured-node">
    <template v-if="container">
      <span v-if="emptyContainer" class="structured-line">
        <span v-if="label !== undefined" class="structured-key">{{ JSON.stringify(label) }}: </span>{{ containerMark.join('') }}
      </span>
      <details v-else :open="expanded" @toggle="expanded = ($event.target as HTMLDetailsElement).open">
        <summary>
          <span v-if="label !== undefined" class="structured-key">{{ JSON.stringify(label) }}: </span>
          <span>{{ containerMark[0] }}{{ expanded ? '' : ` … ${children.length} 项 ` }}{{ expanded ? '' : containerMark[1] }}</span>
        </summary>
        <div v-if="expanded" class="structured-children">
          <StructuredValueNode v-for="(child, index) in children" :key="`${child.label}-${index}`"
            :node="child.value" :label="child.label" :depth="depth + 1" />
          <span class="structured-close">{{ containerMark[1] }}</span>
        </div>
      </details>
    </template>
    <span v-else class="structured-line">
      <span v-if="label !== undefined" class="structured-key">{{ JSON.stringify(label) }}: </span>
      <span :class="`structured-${node.kind}`">{{ scalarRaw }}</span>
    </span>
  </div>
</template>
