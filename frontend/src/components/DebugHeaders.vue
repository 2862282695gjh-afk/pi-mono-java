<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { createDebugHeaderPresets, revealFirstDebugHeaderError, validateDebugHeaders } from '../debugHeaders';
import type { DebugHeaderInput } from '../debugHeaders';

interface DebugHeaderRow extends DebugHeaderInput {
  id: number;
}

let nextRowId = 1;
const rows = ref<DebugHeaderRow[]>(createDebugHeaderPresets().map(createRow));
const root = ref<HTMLDetailsElement | null>(null);
const addButton = ref<HTMLButtonElement | null>(null);
const validation = computed(() => validateDebugHeaders(rows.value));

function createRow(input: DebugHeaderInput): DebugHeaderRow {
  return { ...input, id: nextRowId++ };
}

function appendRow(): void {
  rows.value.push(createRow({ enabled: true, key: '', value: '' }));
  focusRow(rows.value.length - 1);
}

function deleteRow(id: number): void {
  const index = rows.value.findIndex((row) => row.id === id);
  rows.value = rows.value.filter((row) => row.id !== id);
  focusRow(Math.min(index, rows.value.length - 1));
}

function clearRows(): void {
  rows.value = [];
  focusRow(-1);
}

function focusRow(index: number): void {
  void nextTick(() => {
    const input = root.value?.querySelectorAll<HTMLInputElement>('.debug-header-key')[index];
    (input ?? addButton.value)?.focus();
  });
}

async function snapshot(): Promise<Headers | null> {
  const result = validation.value;
  if (!result.valid) {
    await revealFirstDebugHeaderError(root.value, result, nextTick);
    return null;
  }
  return new Headers(result.headers);
}

defineExpose({ snapshot });
</script>

<template>
  <details ref="root" class="debug-headers">
    <summary>
      <span>Headers</span>
      <span class="debug-header-count">({{ validation.enabledCount }})</span>
    </summary>
    <div class="debug-headers-body">
      <div class="debug-header-actions">
        <button ref="addButton" type="button" @click="appendRow">增加请求头</button>
        <button type="button" @click="clearRows">全部清空</button>
      </div>
      <div class="debug-header-grid-head" aria-hidden="true">
        <span></span><span>Key</span><span>Value</span><span></span>
      </div>
      <div class="debug-header-rows" aria-label="自定义请求 Headers">
        <div
          v-for="(row, index) in rows"
          :key="row.id"
          class="debug-header-row"
          :class="{ 'is-disabled': !row.enabled, 'has-error': Boolean(validation.errors[index]) }"
          data-debug-header-row
        >
          <label class="debug-header-enabled">
            <input v-model="row.enabled" type="checkbox" aria-label="启用此请求头">
          </label>
          <label class="debug-header-field">
            <span class="sr-only">Header Key</span>
            <input
              v-model="row.key"
              class="debug-header-key"
              type="text"
              placeholder="Key"
              autocomplete="off"
              spellcheck="false"
              :aria-invalid="validation.errorFields[index] === 'key'"
            >
          </label>
          <label class="debug-header-field">
            <span class="sr-only">Header Value</span>
            <input
              v-model="row.value"
              class="debug-header-value"
              type="text"
              placeholder="Value"
              autocomplete="off"
              spellcheck="false"
              :aria-invalid="validation.errorFields[index] === 'value'"
            >
            <span v-if="validation.errors[index]" class="debug-header-error" role="alert">
              {{ validation.errors[index] }}
            </span>
          </label>
          <button class="debug-header-delete" type="button" aria-label="删除此请求头" @click="deleteRow(row.id)">×</button>
        </div>
      </div>
      <span class="sr-only" aria-live="polite">
        已启用 {{ validation.enabledCount }} 个自定义请求头
      </span>
    </div>
  </details>
</template>
